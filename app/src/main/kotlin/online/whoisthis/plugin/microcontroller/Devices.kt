package online.whoisthis.plugin.microcontroller

import online.whoisthis.capture.Capability
import online.whoisthis.capture.DeviceInfo
import online.whoisthis.capture.DeviceState

/** A board the plugin can reach: found by mDNS or typed in on the plugin screen. */
data class Board(
    /** Host the HTTP calls go to: IP, `name.local`, optionally `:port`. Doubles as the contract device id. */
    val host: String,
    /** mDNS instance name or the typed address until the board answers `/whoisthis`. */
    val name: String,
    val model: String = "",
    val capabilities: List<String> = emptyList(),
    val streamPort: Int = Protocol.DEFAULT_STREAM_PORT,
    val source: Source = Source.MANUAL,
    /** The board shoots only on a button press (fob firmware); the engine long-polls instead of scheduling stills. */
    val captureOnTap: Boolean = false,
) {
    enum class Source { MDNS, MANUAL }

    fun withStatus(s: BoardStatus) = copy(
        name = if (source == Source.MANUAL) s.model.ifBlank { name } else name,
        model = s.model,
        capabilities = s.capabilities,
        streamPort = s.streamPort,
        captureOnTap = s.onTap,
    )
}

/** Catalog of boards → contract descriptors. */
object Devices {
    /** Placeholder shown when no board is known: the app routes the user to the plugin screen. */
    const val SETUP = "setup"
    const val MODEL = "WhoIsThis firmware (XIAO ESP32-S3 Sense)"

    /** A board we have not talked to yet: what the firmware ships with. */
    val assumedCapabilities = listOf(Capability.CAMERA_STILL, Capability.CAMERA_STREAM)

    /** Parses mDNS TXT `caps=camera.still,camera.stream,battery` into contract ids. */
    fun capabilities(caps: String?): List<String> =
        caps.orEmpty().split(',').map(String::trim).filter { it in Capability.ALL }

    fun info(b: Board, state: String, detail: String, capabilities: List<String> = b.capabilities.ifEmpty { assumedCapabilities }) =
        DeviceInfo(b.host, b.name.ifBlank { b.host }, b.model.ifBlank { MODEL }, capabilities, state, detail)

    fun setup(): DeviceInfo = DeviceInfo(SETUP, "Add a board", MODEL, assumedCapabilities, DeviceState.SETUP_REQUIRED, "open_plugin")

    /** Discovered boards first (they carry name and capabilities); the typed one only when it is new. */
    fun merge(discovered: List<Board>, manual: String?): List<Board> {
        val typed = manual?.takeIf { it.isNotBlank() }?.let { Board(host = it, name = it, source = Board.Source.MANUAL) }
        val found = discovered.sortedBy { it.name }.distinctBy { it.host }
        return if (typed == null || found.any { it.host == typed.host }) found else found + typed
    }
}
