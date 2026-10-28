package online.whoisthis.plugin.microcontroller

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import online.whoisthis.capture.Capability
import online.whoisthis.capture.DeviceEvent
import online.whoisthis.capture.DeviceInfo
import online.whoisthis.capture.DeviceState
import online.whoisthis.capture.FrameInfo
import online.whoisthis.capture.FrameKind
import online.whoisthis.capture.FrameRing
import online.whoisthis.capture.ICaptureSink
import online.whoisthis.capture.Presentation
import online.whoisthis.capture.RingWriter
import online.whoisthis.capture.StreamRequest
import java.io.IOException
import java.util.UUID

/**
 * One capture at a time, owned by the process. Unlike the glasses plugin nothing here needs an
 * Activity: the board is an HTTP server on the LAN, so a start from the app runs straight away.
 * The plugin screen only adds boards (mDNS scan, typed address) and mirrors the capture.
 */
object Engine {
    private const val TAG = "wit.mcu"
    private const val THUMBNAIL_EVERY = 10
    private const val EVENT_POLL_MS = 400L
    private const val EVENT_RETRY_MS = 2_000L
    private const val RECONNECT_MS = 1_000L
    private const val MAX_CONSECUTIVE_FAILURES = 5
    private const val DISCOVERY_WINDOW_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class Capture(val board: Board, val request: StreamRequest, val sink: ICaptureSink) {
        var live: Board = board
        var job: Job? = null
        var memory: SharedMemory? = null
        var writer: RingWriter? = null
        val ringLock = Any()
        var sequence = 0L
        var dropped = 0L
        /** Timing: when this capture started, parts received, and when the last one arrived. */
        val startedNanos = System.nanoTime()
        var parts = 0L
        var lastPartNanos = 0L
        /** Open stream to close on teardown so a blocked read returns at once. */
        @Volatile var open: java.io.Closeable? = null
        @Volatile var state: String = DeviceState.CONNECTING
    }

    /** Test seam: the board transport; [HttpBoard] in production. */
    internal var transport: BoardTransport = HttpBoard()

    private var appContext: Context? = null
    private var discovery: Discovery? = null
    private var discoveryStop: Job? = null
    @Volatile private var manualHost: String? = null
    @Volatile private var current: Capture? = null

    /** Boards found so far (mDNS + typed); for the screen and [listDevices]. */
    private val _boards = MutableStateFlow<List<Board>>(emptyList())
    val boards: StateFlow<List<Board>> get() = _boards

    /** What the plugin screen shows: last state + detail, and text sent to the board. */
    val status = MutableStateFlow("idle")
    val display: StateFlow<String> get() = _display
    private val _display = MutableStateFlow("")

    /** Live capture facts for the plugin screen: state, counts, last frame, last error, latest image. */
    val facts = MutableStateFlow(CaptureFacts())

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        manualHost = Prefs.manualHost(app)
        val d = runCatching { Discovery(app) }.onFailure { Log.w(TAG, "no NSD", it) }.getOrNull()
        discovery = d
        if (d != null) scope.launch { d.boards.collect { recompute() } }
        recompute()
    }

    /** Scans the network for [DISCOVERY_WINDOW_MS]; called on every device listing and from the screen. */
    fun refresh() {
        val d = discovery ?: return
        scope.launch {
            d.start()
            discoveryStop?.cancel()
            discoveryStop = launch { delay(DISCOVERY_WINDOW_MS); d.stop() }
        }
    }

    fun setManualHost(host: String) {
        manualHost = host.trim().takeIf { it.isNotBlank() }
        appContext?.let { Prefs.setManualHost(it, host) }
        recompute()
    }

    private fun recompute() {
        _boards.value = Devices.merge(discovery?.boards?.value.orEmpty(), manualHost)
    }

    fun listDevices(): List<DeviceInfo> {
        refresh()
        val list = _boards.value
        if (list.isEmpty()) return listOf(Devices.setup())
        val c = current
        return list.map { b ->
            if (c != null && c.board.host == b.host) Devices.info(c.live, c.state, "", c.live.capabilities)
            else Devices.info(b, DeviceState.READY, "")
        }
    }

    fun start(deviceId: String, request: StreamRequest, sink: ICaptureSink) = scope.launch {
        teardown(DeviceState.STOPPED, "replaced")
        if (deviceId == Devices.SETUP) {
            status.value = "setup_required open_plugin"
            runCatching { sink.onState(deviceId, DeviceState.SETUP_REQUIRED, "open_plugin") }
            return@launch
        }
        // A host the app remembers from an earlier listing is still worth trying after a restart.
        val board = _boards.value.firstOrNull { it.host == deviceId } ?: Board(host = deviceId, name = deviceId)
        launch(Capture(board, request, sink))
    }

    fun stop() = scope.launch { teardown(DeviceState.STOPPED, "stopped") }

    fun release(slot: Int) {
        val c = current ?: return
        synchronized(c.ringLock) { c.writer?.release(slot) }
    }

    fun present(p: Presentation) = scope.launch {
        val c = current ?: return@launch
        if (!p.show && !p.speak) return@launch
        val host = c.board.host
        withContext(Dispatchers.IO) { runCatching { transport.present(host, p.text, p.show, p.speak) } }
            .onSuccess { _display.value = Panel.presented(p.text) }
            .onFailure { Log.w(TAG, "present failed", it); _display.value = Panel.presentFailed(describe(it)) }
    }

    /** Connect from the plugin screen itself (no app bound): checks the board answers. */
    fun connectLocally(host: String) = scope.launch {
        teardown(DeviceState.STOPPED, "replaced")
        val board = _boards.value.firstOrNull { it.host == host } ?: Board(host = host, name = host)
        launch(Capture(board, StreamRequest(), NoSink))
    }

    fun disconnectLocally() = scope.launch { teardown(DeviceState.STOPPED, "stopped") }

    private fun launch(c: Capture) {
        current = c
        facts.value = CaptureFacts(device = c.board.name.ifBlank { c.board.host })
        c.job = scope.launch(Dispatchers.IO) {
            try {
                run(c)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "capture failed", e)
                emit(c, DeviceState.ERROR, describe(e))
                closeCapture(c)
                if (current === c) current = null
            }
        }
    }

    private suspend fun run(c: Capture) {
        emit(c, DeviceState.CONNECTING, "")
        val s = Timing.timed("board.status", "total_ms" to Timing.sinceNanos(c.startedNanos)) { transport.status(c.board.host) }
        c.live = c.board.withStatus(s)
        val caps = c.live.capabilities
        emit(c, DeviceState.CONNECTED, caps.joinToString(","))
        _display.value = Panel.connected(c.live, caps)
        s.battery?.let { runCatching { c.sink.onEvent(c.board.host, DeviceEvent.BATTERY, it.toString()) } }
        if (caps.any { it == Capability.INPUT_TAP || it == Capability.INPUT_LONG_PRESS || it == Capability.BATTERY }) {
            scope.launch(Dispatchers.IO) { pollEvents(c) }.also { ev -> c.job?.invokeOnCompletion { ev.cancel() } }
        }
        if (c.request.preferStream && Capability.CAMERA_STREAM in caps) stream(c) else stills(c)
    }

    private suspend fun pollEvents(c: Capture) {
        var after = 0L
        while (currentCoroutineIsActive()) {
            try {
                val e = transport.events(c.board.host, after)
                after = e.next
                e.events.forEach { ev -> runCatching { c.sink.onEvent(c.board.host, ev.name, ev.detail) } }
                delay(EVENT_POLL_MS)
            } catch (e: IOException) {
                Log.d(TAG, "events: ${describe(e)}")
                delay(EVENT_RETRY_MS)
            }
        }
    }

    private suspend fun stream(c: Capture) {
        val slotBytes = FrameRing.DEFAULT_SLOT_BYTES
        val memory = FrameRing.create("wit-mcu-${c.board.host}", slotBytes)
        synchronized(c.ringLock) {
            c.memory = memory
            c.writer = RingWriter(memory, slotBytes)
        }
        c.sink.onRing(memory, slotBytes)
        var failures = 0
        while (true) {
            val mjpeg = try {
                Timing.timed("stream.open", "total_ms" to Timing.sinceNanos(c.startedNanos)) {
                    transport.stream(c.board.host, c.live.streamPort, c.request.maxLongSide)
                }
            } catch (e: IOException) {
                if (++failures > MAX_CONSECUTIVE_FAILURES) throw e
                facts.value = facts.value.copy(lastError = "${describe(e)} (retry $failures/$MAX_CONSECUTIVE_FAILURES)")
                delay(RECONNECT_MS)
                continue
            }
            c.open = mjpeg
            emit(c, DeviceState.STREAMING, "")
            try {
                while (true) {
                    val waitStart = System.nanoTime()
                    val part = mjpeg.next() ?: throw IOException("stream ended")
                    val receivedAt = System.currentTimeMillis()
                    val now = System.nanoTime()
                    failures = 0
                    val n = ++c.parts
                    val gapMs = if (c.lastPartNanos == 0L) null else (now - c.lastPartNanos) / 1_000_000
                    c.lastPartNanos = now
                    val boardMs = part.headers["x-timestamp"]?.toLongOrNull()
                    if (n == 1L) Timing.mark("stream.first_part", "total_ms" to Timing.sinceNanos(c.startedNanos), "bytes" to part.body.size)
                    if (Timing.every(n)) {
                        Timing.mark(
                            "frame.received", "src" to "board", "n" to n, "bytes" to part.body.size,
                            "read_ms" to (now - waitStart) / 1_000_000, "gap_ms" to gapMs, "board_ms" to boardMs,
                        )
                    }
                    deliver(c, part.body, part.headers["x-rotation"]?.toIntOrNull() ?: 0, receivedAt, n)
                }
            } catch (e: IOException) {
                currentCoroutineContextEnsureActive()
                if (++failures > MAX_CONSECUTIVE_FAILURES) throw e
                Log.w(TAG, "stream lost; reconnecting ($failures)", e)
                facts.value = facts.value.copy(lastError = "${describe(e)} (reconnect $failures/$MAX_CONSECUTIVE_FAILURES)")
                delay(RECONNECT_MS)
            } finally {
                c.open = null
                mjpeg.close()
            }
        }
    }

    /** [receivedAt] is when the part finished arriving from the board (epoch ms); [n] its ordinal. */
    private fun deliver(c: Capture, jpeg: ByteArray, rotation: Int, receivedAt: Long, n: Long) {
        val writeStart = System.nanoTime()
        val slot = synchronized(c.ringLock) { c.writer?.write(jpeg) }
        if (slot == null) {
            c.dropped++
            Timing.mark(
                "frame.dropped", "reason" to "ring_full", "n" to n, "seq" to c.sequence, "dropped" to c.dropped,
                "inflight" to synchronized(c.ringLock) { c.writer?.inFlightCount() },
            )
            facts.value = facts.value.copy(dropped = c.dropped)
            runCatching { c.sink.onEvent(c.board.host, DeviceEvent.DROPPED, c.dropped.toString()) }
            return
        }
        val writeMs = Timing.sinceNanos(writeStart)
        if (writeMs > 250 || Timing.every(n)) Timing.mark("ring.write", "ms" to writeMs, "slot" to slot, "bytes" to jpeg.size, "n" to n)
        val (w, h) = Jpeg.dimensions(jpeg) ?: (0 to 0)
        record(w, h, jpeg, still = false)
        val info = info(c, FrameKind.FRAME, w, h, rotation)
        val ageMs = info.timestampMs - receivedAt
        if (ageMs > 250 || Timing.every(n)) {
            Timing.mark("frame.handover", "t" to info.timestampMs, "age_ms" to ageMs, "seq" to info.sequence, "bytes" to jpeg.size, "w" to w, "h" to h)
        }
        c.sink.onFrame(info, slot, jpeg.size)
    }

    private suspend fun stills(c: Capture) {
        emit(c, DeviceState.STREAMING, "stills")
        var failures = 0
        while (currentCoroutineIsActive()) {
            val jpeg = try {
                Timing.timed("still.capture", "total_ms" to Timing.sinceNanos(c.startedNanos)) {
                    transport.capture(c.board.host, c.request.maxLongSide)
                }
            } catch (e: IOException) {
                if (++failures > MAX_CONSECUTIVE_FAILURES) throw e
                Log.w(TAG, "capture failed; retry $failures/$MAX_CONSECUTIVE_FAILURES", e)
                facts.value = facts.value.copy(lastError = "${describe(e)} (retry $failures/$MAX_CONSECUTIVE_FAILURES)")
                delay(c.request.stillIntervalMs.toLong())
                continue
            }
            failures = 0
            val (w, h) = Jpeg.dimensions(jpeg) ?: (0 to 0)
            record(w, h, jpeg, still = true)
            // The writer runs alongside the app's read (a pipe holds 64 KB; a frame is bigger), and
            // our read end closes only afterwards so an in-process sink can still drain it.
            val pipe = ParcelFileDescriptor.createPipe()
            val writer = scope.launch(Dispatchers.IO) {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(jpeg) }
            }
            try {
                val info = info(c, FrameKind.STILL, w, h, 0)
                Timing.timed("still.handover", "t" to info.timestampMs, "bytes" to jpeg.size, "w" to w, "h" to h) {
                    c.sink.onStill(info, pipe[0])
                    writer.join()
                }
            } finally {
                writer.cancel()
                pipe[0].close()
            }
            delay(c.request.stillIntervalMs.toLong())
        }
    }

    /** Stream frames refresh the thumbnail every [THUMBNAIL_EVERY] frames; stills always do. */
    private fun record(w: Int, h: Int, jpeg: ByteArray, still: Boolean) {
        val f = facts.value
        val frames = f.frames + if (still) 0 else 1
        facts.value = f.copy(
            photos = f.photos + if (still) 1 else 0,
            frames = frames,
            lastAt = System.currentTimeMillis(),
            lastWidth = w,
            lastHeight = h,
            lastBytes = jpeg.size,
            thumbnail = if (still || frames % THUMBNAIL_EVERY == 1) jpeg else f.thumbnail,
        )
    }

    private fun info(c: Capture, kind: String, w: Int, h: Int, rot: Int) =
        FrameInfo(UUID.randomUUID().toString(), c.board.host, kind, c.sequence++, System.currentTimeMillis(), "image/jpeg", w, h, rot)

    private suspend fun teardown(state: String, detail: String) {
        val c = current ?: return
        current = null
        c.open?.close()
        c.job?.cancelAndJoin()
        closeCapture(c)
        emit(c, state, detail)
        _display.value = ""
    }

    private fun closeCapture(c: Capture) {
        c.open?.close()
        synchronized(c.ringLock) {
            c.memory?.let { m -> c.writer?.close(m) }
            c.writer = null
            c.memory = null
        }
    }

    private fun emit(c: Capture, state: String, detail: String) {
        c.state = state
        Timing.mark("device.state", "state" to state, "detail" to detail.ifEmpty { null }, "total_ms" to Timing.sinceNanos(c.startedNanos))
        status.value = "${c.board.host}: $state $detail".trim()
        facts.value = facts.value.copy(
            state = state,
            detail = detail,
            lastError = if (state == DeviceState.ERROR) detail else facts.value.lastError,
        )
        runCatching { c.sink.onState(c.board.host, state, detail) }
    }

    private suspend fun currentCoroutineIsActive(): Boolean = kotlin.coroutines.coroutineContext.isActive
    private suspend fun currentCoroutineContextEnsureActive() = kotlin.coroutines.coroutineContext.ensureActive()

    private object NoSink : ICaptureSink.Stub() {
        override fun onState(deviceId: String, state: String, detail: String) = Unit
        override fun onStill(info: FrameInfo, jpeg: ParcelFileDescriptor) = jpeg.close()
        override fun onRing(memory: SharedMemory, slotBytes: Int) = Unit
        override fun onFrame(info: FrameInfo, slot: Int, length: Int) = Unit
        override fun onEvent(deviceId: String, name: String, detail: String) = Unit
    }
}

/** Short, never-empty detail for state events and the plugin screen. */
internal fun describe(e: Throwable): String = when (e) {
    is java.net.SocketTimeoutException -> "timeout"
    is java.net.ConnectException -> "unreachable"
    is java.net.UnknownHostException -> "unknown_host:${e.message.orEmpty()}"
    else -> generateSequence(e) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?: e.javaClass.simpleName.ifBlank { "error" }
}
