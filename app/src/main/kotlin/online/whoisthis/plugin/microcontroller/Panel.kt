package online.whoisthis.plugin.microcontroller

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the plugin screen knows about the running capture. Updated by [Engine], rendered by [Panel]. */
data class CaptureFacts(
    val device: String = "",
    val state: String = "",
    val detail: String = "",
    val photos: Int = 0,
    val frames: Int = 0,
    val dropped: Long = 0,
    val lastAt: Long = 0,
    val lastWidth: Int = 0,
    val lastHeight: Int = 0,
    val lastBytes: Int = 0,
    val lastError: String = "",
    /** Latest JPEG handed to the app, kept in memory for the screen only; never written or uploaded. */
    val thumbnail: ByteArray? = null,
)

/** Text for the plugin screen's panels. */
object Panel {
    const val NO_CAPTURE = "No capture yet. Start a board from WhoIsThis → Plugins → Microcontroller, or connect one here."
    const val NO_BOARDS = "No boards found. Power the board on the same Wi-Fi, or type its address above."

    fun boards(list: List<Board>): String {
        if (list.isEmpty()) return NO_BOARDS
        return list.joinToString("\n") { b ->
            val caps = b.capabilities.ifEmpty { Devices.assumedCapabilities }.joinToString(",")
            "${b.name} · ${b.host} · ${b.source.name.lowercase()}" + (if (b.captureOnTap) " · on tap" else "") + " · $caps"
        }
    }

    const val ON_TAP = "Press the board's button to take a picture; nothing is captured in between."

    /** Output panel after a board connects: how it captures, and what it can show or say, if anything. */
    fun connected(b: Board, caps: List<String>): String {
        val shows = "display.text" in caps
        val speaks = "audio.tts" in caps || "audio.bytes" in caps
        val mode = if (b.captureOnTap) ON_TAP + "\n" else ""
        if (shows) return mode.trimEnd()
        return mode + "${b.name} has no display. Results appear in the WhoIsThis app" +
            if (speaks) " and are spoken by the board." else " (the board blinks its LED on each result)."
    }

    fun presented(text: String) = "Sent to the board: “$text”"

    fun presentFailed(reason: String) = "Present failed: $reason"

    fun facts(f: CaptureFacts): String {
        if (f.device.isEmpty()) return NO_CAPTURE
        return buildString {
            append("${f.device}: ${f.state} ${f.detail}".trim())
            append('\n')
            append("Photos ${f.photos} · frames ${f.frames}")
            if (f.dropped > 0) append(" · dropped ${f.dropped}")
            if (f.lastAt > 0) append(" · last ${time(f.lastAt)} ${f.lastWidth}×${f.lastHeight} ${f.lastBytes / 1024} KB")
            if (f.lastError.isNotEmpty()) append("\nLast error: ${f.lastError}")
        }
    }

    private fun time(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
