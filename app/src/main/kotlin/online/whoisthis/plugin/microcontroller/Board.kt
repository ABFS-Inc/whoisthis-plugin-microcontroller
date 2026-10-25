package online.whoisthis.plugin.microcontroller

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** What the engine needs from a board; [HttpBoard] is the real one, tests substitute a fake. */
interface BoardTransport {
    fun status(host: String): BoardStatus
    /**
     * One JPEG. With [waitMs] > 0 the board holds the request until its button is pressed and
     * returns null (HTTP 204) when nothing happened within the wait; the caller simply asks again.
     */
    fun capture(host: String, maxLongSide: Int, waitMs: Int = 0): ByteArray?
    /** An open MJPEG stream; the caller closes it. */
    fun stream(host: String, streamPort: Int, maxLongSide: Int): Mjpeg
    fun events(host: String, after: Long): BoardEvents
    fun present(host: String, text: String, show: Boolean, speak: Boolean)
}

/** Boards reached by plain HTTP on the local network. Hosts are `ip`, `name.local` or `host:port`. */
class HttpBoard : BoardTransport {
    override fun status(host: String): BoardStatus = BoardStatus.parse(get(url(host, null, "/whoisthis")).readText())

    override fun capture(host: String, maxLongSide: Int, waitMs: Int): ByteArray? {
        val path = if (waitMs > 0) "/capture?max=$maxLongSide&wait=$waitMs" else "/capture?max=$maxLongSide"
        val c = open(url(host, null, path), timeoutMs = CAPTURE_TIMEOUT_MS + waitMs)
        try {
            return when (c.responseCode) {
                200 -> c.inputStream.use { it.readBytes() }
                204 -> null
                else -> throw IOException("HTTP ${c.responseCode} from /capture")
            }
        } finally {
            c.disconnect()
        }
    }

    override fun stream(host: String, streamPort: Int, maxLongSide: Int): Mjpeg {
        val c = open(url(host, streamPort, "/stream?max=$maxLongSide"), timeoutMs = STREAM_READ_TIMEOUT_MS)
        if (c.responseCode != 200) { c.disconnect(); throw IOException("HTTP ${c.responseCode} from /stream") }
        val boundary = Mjpeg.boundary(c.contentType) ?: run { c.disconnect(); throw IOException("no multipart boundary in ${c.contentType}") }
        return Mjpeg(c.inputStream, boundary) { c.disconnect() }
    }

    override fun events(host: String, after: Long): BoardEvents =
        BoardEvents.parse(get(url(host, null, "/events?after=$after")).readText())

    override fun present(host: String, text: String, show: Boolean, speak: Boolean) {
        val c = open(url(host, null, "/present"))
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(Protocol.presentBody(text, show, speak).toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299) throw IOException("HTTP $code from /present")
    }

    private fun get(u: URL, timeoutMs: Int = TIMEOUT_MS): InputStream {
        val c = open(u, timeoutMs)
        if (c.responseCode != 200) { c.disconnect(); throw IOException("HTTP ${c.responseCode} from ${u.path}") }
        return c.inputStream
    }

    private fun open(u: URL, timeoutMs: Int = TIMEOUT_MS): HttpURLConnection =
        (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("Connection", "close")
        }

    private fun InputStream.readText() = use { it.readBytes().toString(Charsets.UTF_8) }

    companion object {
        const val TIMEOUT_MS = 5_000
        const val CAPTURE_TIMEOUT_MS = 10_000
        const val STREAM_READ_TIMEOUT_MS = 15_000

        /**
         * Control URLs ([port] null) use the port in `host` (`10.0.0.5:8080`) or 80; the stream URL
         * passes the board's advertised stream port and ignores any port in `host`.
         */
        fun url(host: String, port: Int?, path: String): URL {
            val h = host.trim().removePrefix("http://").removeSuffix("/")
            val split = h.lastIndexOf(':')
            val hasPort = split > 0 && !h.startsWith('[') && h.substring(split + 1).all(Char::isDigit)
            val name = if (hasPort) h.substring(0, split) else h
            val p = port ?: (if (hasPort) h.substring(split + 1).toInt() else Protocol.DEFAULT_PORT)
            return URL("http://$name:$p$path")
        }
    }
}
