package online.whoisthis.plugin.microcontroller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Finds boards advertising `_whoisthis._tcp` on the local network through Android's NSD (mDNS). */
class Discovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = LinkedHashMap<String, Board>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null

    private val _boards = MutableStateFlow<List<Board>>(emptyList())
    val boards: StateFlow<List<Board>> get() = _boards

    fun start() {
        if (listener != null) return
        multicast = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("whoisthis-mdns")?.apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "discovery start failed: $code"); listener = null }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { found.remove(info.serviceName) }
                publish()
            }
        }
        listener = l
        runCatching { nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "discoverServices", it); listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        multicast?.release()
        multicast = null
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "resolve ${i.serviceName} failed: $code") }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val b = board(i) ?: return
                synchronized(found) { found[i.serviceName] = b }
                publish()
            }
        })
    }

    private fun publish() { _boards.value = synchronized(found) { found.values.toList() } }

    companion object {
        private const val TAG = "wit.mcu"

        /** A resolved service → board, or null without an address. */
        @Suppress("DEPRECATION")
        fun board(i: NsdServiceInfo): Board? {
            val addr = i.host?.hostAddress ?: return null
            val txt = i.attributes.orEmpty().mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
            val host = if (i.port != 0 && i.port != Protocol.DEFAULT_PORT) "$addr:${i.port}" else addr
            return Board(
                host = host,
                name = i.serviceName,
                model = txt["model"].orEmpty(),
                capabilities = Devices.capabilities(txt["caps"]),
                streamPort = txt["stream_port"]?.toIntOrNull() ?: Protocol.DEFAULT_STREAM_PORT,
                captureOnTap = txt["capture"] == Protocol.CAPTURE_ON_TAP,
                source = Board.Source.MDNS,
            )
        }
    }
}
