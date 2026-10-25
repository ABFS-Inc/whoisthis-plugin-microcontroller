package online.whoisthis.plugin.microcontroller

import android.os.Looper.getMainLooper
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import online.whoisthis.capture.Capability
import online.whoisthis.capture.DeviceEvent
import online.whoisthis.capture.DeviceState
import online.whoisthis.capture.FrameInfo
import online.whoisthis.capture.FrameKind
import online.whoisthis.capture.ICaptureSink
import online.whoisthis.capture.Presentation
import online.whoisthis.capture.RingReader
import online.whoisthis.capture.StreamRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineTest {
    private class Sink : ICaptureSink.Stub() {
        val states = CopyOnWriteArrayList<Pair<String, String>>()
        val stills = CopyOnWriteArrayList<Pair<FrameInfo, ByteArray>>()
        val frames = CopyOnWriteArrayList<Triple<FrameInfo, Int, Int>>()
        val events = CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var reader: RingReader? = null
        override fun onState(deviceId: String, state: String, detail: String) { states += state to detail }
        // Robolectric backs createPipe() with a file, so a read can land before the writer is done:
        // keep reading until the JPEG's end marker (a real pipe blocks instead).
        override fun onStill(info: FrameInfo, jpeg: ParcelFileDescriptor) {
            val out = ByteArrayOutputStream()
            val input = java.io.FileInputStream(jpeg.fileDescriptor)
            val until = System.currentTimeMillis() + 2_000
            while (System.currentTimeMillis() < until) {
                out.write(input.readBytes())
                val b = out.toByteArray()
                if (b.size >= 2 && b[b.size - 2] == 0xFF.toByte() && b[b.size - 1] == 0xD9.toByte()) break
                Thread.sleep(5)
            }
            stills += info to out.toByteArray()
        }
        override fun onRing(memory: SharedMemory, slotBytes: Int) { reader = RingReader(memory, slotBytes) }
        override fun onFrame(info: FrameInfo, slot: Int, length: Int) { frames += Triple(info, slot, length) }
        override fun onEvent(deviceId: String, name: String, detail: String) { events += name to detail }
    }

    private class FakeBoard : BoardTransport {
        var caps = listOf(Capability.CAMERA_STILL, Capability.CAMERA_STREAM, Capability.INPUT_TAP, Capability.BATTERY)
        var battery: Int? = 77
        val still = fakeJpeg(640, 480, payload = 300)
        val streamFrames = listOf(fakeJpeg(1280, 720, payload = 50), fakeJpeg(1280, 720, payload = 60))
        val presented = CopyOnWriteArrayList<String>()
        val hosts = CopyOnWriteArrayList<String>()
        @Volatile var eventsServed = false
        @Volatile var streamsOpened = 0
        /** `capture` the status reports; on_tap makes [capture] behave like the fob firmware's long-poll. */
        @Volatile var captureMode = Protocol.CAPTURE_CONTINUOUS
        val waits = CopyOnWriteArrayList<Int>()
        /** Presses the fob answers the next long-poll with; drained one per poll. */
        val presses = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

        override fun status(host: String): BoardStatus {
            hosts += host
            if (host == "down.local") throw IOException("unreachable")
            return BoardStatus("xiao-1", "XIAO ESP32-S3 Sense", "1.0.0", 1, caps, battery, 81, captureMode)
        }
        override fun capture(host: String, maxLongSide: Int, waitMs: Int): ByteArray? {
            waits += waitMs
            if (waitMs == 0) return still
            // A fob holds the request until a press or the end of the wait (shortened here); 204 → null.
            return presses.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        override fun stream(host: String, streamPort: Int, maxLongSide: Int): Mjpeg {
            streamsOpened++
            assertEquals(81, streamPort)
            val out = ByteArrayOutputStream()
            streamFrames.forEach { f ->
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                out.write(f); out.write("\r\n".toByteArray())
            }
            // The fake stream ends after its frames; the engine then reconnects, which the test counts.
            if (streamsOpened > 1) Thread.sleep(200)
            return Mjpeg(ByteArrayInputStream(out.toByteArray()), "frame")
        }
        override fun events(host: String, after: Long): BoardEvents {
            if (eventsServed) return BoardEvents(after, emptyList())
            eventsServed = true
            return BoardEvents(2, listOf(BoardEvent(1, DeviceEvent.TAP, "1"), BoardEvent(2, DeviceEvent.LONG_PRESS, "")))
        }
        override fun present(host: String, text: String, show: Boolean, speak: Boolean) { presented += text }
    }

    private val board = FakeBoard()
    private val sink = Sink()

    @Before
    fun setUp() {
        Engine.init(RuntimeEnvironment.getApplication())
        Engine.transport = board
        Engine.setManualHost("")
    }

    @After
    fun tearDown() {
        Engine.stop()
        settle { true }
        Engine.setManualHost("")
        Engine.transport = HttpBoard()
    }

    /** Pumps the main looper until [done] or a timeout; background HTTP work runs on IO threads. */
    private fun settle(timeoutMs: Long = 5_000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            shadowOf(getMainLooper()).idle()
            if (done()) return
            Thread.sleep(20)
        }
        shadowOf(getMainLooper()).idle()
    }

    @Test
    fun withoutBoardsTheOnlyDeviceAsksForSetup() {
        assertEquals(listOf(Devices.SETUP), Engine.listDevices().map { it.id })
        Engine.start(Devices.SETUP, StreamRequest(), sink)
        settle { sink.states.isNotEmpty() }
        assertEquals(DeviceState.SETUP_REQUIRED to "open_plugin", sink.states.single())
    }

    @Test
    fun typedHostBecomesAReadyDevice() {
        Engine.setManualHost("cam.local")
        val d = Engine.listDevices().single()
        assertEquals("cam.local", d.id)
        assertEquals(DeviceState.READY, d.state)
    }

    @Test
    fun stillsTravelOverAPipeAndEventsAreForwarded() {
        Engine.setManualHost("cam.local")
        Engine.start("cam.local", StreamRequest(preferStream = false, stillIntervalMs = 100), sink)
        settle { sink.stills.size >= 2 && sink.events.size >= 3 }
        assertEquals(listOf(DeviceState.CONNECTING, DeviceState.CONNECTED, DeviceState.STREAMING), sink.states.map { it.first }.take(3))
        assertEquals(board.caps.joinToString(","), sink.states[1].second)
        val (info, bytes) = sink.stills.first()
        assertArrayEquals(board.still, bytes)
        assertEquals(FrameKind.STILL, info.kind)
        assertEquals(640, info.width)
        assertEquals(480, info.height)
        assertEquals("cam.local", info.deviceId)
        assertTrue(sink.events.contains(DeviceEvent.BATTERY to "77"))
        assertTrue(sink.events.contains(DeviceEvent.TAP to "1"))
        assertTrue(sink.events.contains(DeviceEvent.LONG_PRESS to ""))
        assertEquals(DeviceState.STREAMING, Engine.listDevices().first { it.id == "cam.local" }.state)

        Engine.present(Presentation("Probably Ana"))
        settle { board.presented.isNotEmpty() }
        assertEquals(listOf("Probably Ana"), board.presented.toList())
        assertEquals(Panel.presented("Probably Ana"), Engine.display.value)

        Engine.stop()
        settle { sink.states.last().first == DeviceState.STOPPED }
        assertEquals(DeviceState.STOPPED to "stopped", sink.states.last())
    }

    @Test
    fun captureOnTapBoardDeliversOneStillPerPressAndNothingInBetween() {
        board.captureMode = Protocol.CAPTURE_ON_TAP
        board.caps = listOf(Capability.CAMERA_STILL, Capability.INPUT_TAP, Capability.INPUT_LONG_PRESS)
        Engine.setManualHost("fob.local")
        Engine.start("fob.local", StreamRequest(preferStream = true, stillIntervalMs = 50), sink)
        settle { board.waits.size >= 3 }
        assertEquals(listOf(DeviceState.CONNECTING, DeviceState.CONNECTED, DeviceState.STREAMING), sink.states.map { it.first }.take(3))
        assertEquals("on_tap", sink.states[2].second)
        assertTrue("every poll is a long-poll: ${board.waits}", board.waits.all { it == Engine.ON_TAP_WAIT_MS })
        assertTrue("no still without a press", sink.stills.isEmpty())
        assertTrue("screen explains the mode: ${Engine.display.value}", Engine.display.value.startsWith(Panel.ON_TAP))

        board.presses.put(board.still)
        settle { sink.stills.size >= 1 }
        val (info, bytes) = sink.stills.single()
        assertArrayEquals(board.still, bytes)
        assertEquals(FrameKind.STILL, info.kind)
        assertEquals(640, info.width)
        val polls = board.waits.size
        settle(400) { false }
        assertEquals("one press, one still", 1, sink.stills.size)
        assertTrue("polling continues while idle", board.waits.size > polls)
        assertEquals(DeviceState.STREAMING, Engine.listDevices().first { it.id == "fob.local" }.state)
        Engine.stop()
        settle { sink.states.last().first == DeviceState.STOPPED }
    }

    @Test
    fun streamFramesGoThroughTheRingAndReleaseFreesSlots() {
        Engine.start("cam.local", StreamRequest(preferStream = true), sink)
        settle { sink.frames.size >= 2 }
        val reader = sink.reader!!
        val (info, slot, length) = sink.frames[0]
        assertEquals(FrameKind.FRAME, info.kind)
        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        assertArrayEquals(board.streamFrames[0], reader.read(slot, length))
        assertEquals(0L, info.sequence)
        // Two slots in flight: the third frame (from the reconnected stream) is dropped until a release.
        settle(4_000) { sink.events.any { it.first == DeviceEvent.DROPPED } }
        assertTrue("expected a drop: ${sink.events}", sink.events.any { it.first == DeviceEvent.DROPPED })
        val before = sink.frames.size
        Engine.release(slot)
        settle { sink.frames.size > before }
        assertTrue(sink.frames.size > before)
        Engine.stop()
        settle { sink.states.last().first == DeviceState.STOPPED }
    }

    @Test
    fun unreachableBoardEndsInError() {
        Engine.start("down.local", StreamRequest(), sink)
        settle { sink.states.any { it.first == DeviceState.ERROR } }
        assertEquals(DeviceState.ERROR to "unreachable", sink.states.last())
        Engine.setManualHost("down.local")
        assertEquals(DeviceState.READY, Engine.listDevices().first { it.id == "down.local" }.state)
    }
}
