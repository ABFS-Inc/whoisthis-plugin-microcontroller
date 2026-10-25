package online.whoisthis.plugin.microcontroller

import online.whoisthis.capture.Capability
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * The HTTP protocol the WhoIsThis firmware speaks (see firmware/README.md). Pure parsing, no I/O:
 *
 * - `GET /whoisthis`        → [BoardStatus] JSON
 * - `GET /capture?max=N`    → one `image/jpeg`; `&wait=MS` → one taken after the next button press, or 204
 * - `GET :81/stream?max=N`  → `multipart/x-mixed-replace`, one JPEG per part ([Mjpeg])
 * - `GET /events?after=N`   → queued button/battery events ([BoardEvents])
 * - `POST /present`         → `{"text","show","speak"}`
 */
object Protocol {
    /** Protocol generation; the firmware reports its own in [BoardStatus.protocol]. */
    const val VERSION = 1

    /** mDNS service type boards advertise; TXT carries `id`, `model`, `caps`, `capture`, `stream_port`. */
    const val SERVICE_TYPE = "_whoisthis._tcp."
    const val DEFAULT_PORT = 80
    const val DEFAULT_STREAM_PORT = 81

    /** `capture` values: frames whenever asked, or only after a button press (`/capture?wait=`). */
    const val CAPTURE_CONTINUOUS = "continuous"
    const val CAPTURE_ON_TAP = "on_tap"

    fun presentBody(text: String, show: Boolean, speak: Boolean): String =
        JSONObject().put("text", text).put("show", show).put("speak", speak).toString()
}

/** What `GET /whoisthis` answers. Capabilities are the contract ids the firmware confirmed at boot. */
data class BoardStatus(
    val id: String,
    val model: String,
    val firmware: String,
    val protocol: Int,
    val capabilities: List<String>,
    /** Percent, or null when the board has no battery sense. */
    val battery: Int?,
    val streamPort: Int,
    /** [Protocol.CAPTURE_CONTINUOUS] or [Protocol.CAPTURE_ON_TAP]; firmware 1.0 sent nothing, which means continuous. */
    val capture: String = Protocol.CAPTURE_CONTINUOUS,
) {
    val onTap: Boolean get() = capture == Protocol.CAPTURE_ON_TAP

    companion object {
        fun parse(json: String): BoardStatus {
            val o = JSONObject(json)
            val caps = o.optJSONArray("capabilities")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            return BoardStatus(
                id = o.getString("id"),
                model = o.optString("model", "microcontroller"),
                firmware = o.optString("firmware", ""),
                protocol = o.optInt("protocol", 1),
                // Unknown ids are dropped: a newer firmware never makes the plugin claim what the contract lacks.
                capabilities = caps.filter { it in Capability.ALL },
                battery = if (o.has("battery") && !o.isNull("battery")) o.getInt("battery") else null,
                streamPort = o.optInt("stream_port", Protocol.DEFAULT_STREAM_PORT),
                capture = o.optString("capture", Protocol.CAPTURE_CONTINUOUS),
            )
        }
    }
}

/** One queued board event: name is a DeviceEvent value, detail its payload. */
data class BoardEvent(val seq: Long, val name: String, val detail: String)

/** What `GET /events?after=N` answers: events with seq > N, and the cursor to ask for next. */
data class BoardEvents(val next: Long, val events: List<BoardEvent>) {
    companion object {
        fun parse(json: String): BoardEvents {
            val o = JSONObject(json)
            val a: JSONArray = o.optJSONArray("events") ?: JSONArray()
            val events = List(a.length()) { i ->
                val e = a.getJSONObject(i)
                BoardEvent(e.getLong("seq"), e.getString("name"), e.optString("detail", ""))
            }
            return BoardEvents(o.optLong("next", events.lastOrNull()?.seq ?: 0L), events)
        }
    }
}

/** Width and height of a baseline/progressive JPEG from its SOF marker, without decoding it. */
object Jpeg {
    fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue }
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (sof) {
                val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return w to h
            }
            if (marker == 0xDA) return null // scan data without a SOF before it
            i += 2 + len
        }
        return null
    }
}

/**
 * Reads `multipart/x-mixed-replace` parts. Parts carry `Content-Length` (the firmware always sends
 * it); without it the part ends at the next boundary line. Not thread-safe.
 */
class Mjpeg(private val input: InputStream, boundary: String, private val onClose: () -> Unit = {}) : java.io.Closeable {
    private val boundary = "--" + boundary.removePrefix("--")
    private val line = ByteArrayOutputStream()

    /** Closes the underlying connection; a blocked [next] then fails with an IOException. */
    override fun close() {
        runCatching { input.close() }
        runCatching { onClose() }
    }

    /** One part's headers and body, or null at end of stream. */
    data class Part(val headers: Map<String, String>, val body: ByteArray)

    fun next(): Part? {
        // Skip to the boundary line (tolerates leading CRLF and a `--` terminator).
        while (true) {
            val l = readLine() ?: return null
            if (l == boundary) break
            if (l == "$boundary--") return null
        }
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val l = readLine() ?: return null
            if (l.isEmpty()) break
            val colon = l.indexOf(':')
            if (colon > 0) headers[l.substring(0, colon).trim().lowercase()] = l.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()
        val body = if (length != null) readExactly(length) else readUntilBoundary()
        return Part(headers, body)
    }

    private fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw EOFException("stream ended inside a part ($off/$n bytes)")
            off += r
        }
        return out
    }

    private fun readUntilBoundary(): ByteArray {
        val out = ByteArrayOutputStream()
        val marker = "\r\n$boundary".toByteArray()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("stream ended inside a part")
            if (b == marker[matched].toInt()) {
                matched++
                if (matched == marker.size) {
                    // Consume the rest of the boundary line so the next call starts cleanly.
                    pushBack = marker
                    return out.toByteArray()
                }
            } else {
                if (matched > 0) { out.write(marker, 0, matched); matched = 0 }
                if (b == marker[0].toInt()) matched = 1 else out.write(b)
            }
        }
    }

    private var pushBack: ByteArray? = null
    private var pushBackPos = 0

    private fun readByte(): Int {
        pushBack?.let { pb ->
            if (pushBackPos < pb.size) return pb[pushBackPos++].toInt() and 0xFF
            pushBack = null
            pushBackPos = 0
        }
        return input.read()
    }

    /** A CRLF- (or LF-) terminated line without its terminator, or null at end of stream. */
    private fun readLine(): String? {
        line.reset()
        while (true) {
            val b = readByte()
            if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            line.write(b)
        }
        val s = line.toString(Charsets.ISO_8859_1.name())
        return s.removeSuffix("\r")
    }

    companion object {
        /** The boundary from a `multipart/x-mixed-replace; boundary=frame` content type. */
        fun boundary(contentType: String?): String? =
            contentType?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
                ?.substringAfter('=')?.trim('"')
    }
}
