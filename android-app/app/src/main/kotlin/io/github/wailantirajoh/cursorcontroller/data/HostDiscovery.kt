package io.github.wailantirajoh.cursorcontroller.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import io.github.wailantirajoh.cursorcontroller.core.ProtocolConstants
import java.net.Inet4Address

/**
 * Mencari agent Mac lewat mDNS (`_cursorctl._tcp`) dan memetakan hostId (dari TXT record) ke "ip:port".
 * Hanya berjalan saat app di foreground. MulticastLock membuat mDNS andal di beberapa vendor.
 */
class HostDiscovery(context: Context, private val onChange: (Map<String, String>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    private val serviceToHost = HashMap<String, String>()
    private val addresses = HashMap<String, String>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        lock = wifi?.createMulticastLock("cursorctl")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) = lost(info.serviceName)
        }
        listener = discovery
        runCatching { nsd.discoverServices(ProtocolConstants.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    @Synchronized
    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.takeIf { it.isHeld }?.release()
        lock = null
        resolveQueue.clear()
        serviceToHost.clear()
        addresses.clear()
        onChange(emptyMap())
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun lost(serviceName: String) {
        val hostId = serviceToHost.remove(serviceName) ?: return
        addresses.remove(hostId)
        onChange(addresses.toMap())
    }

    // resolveService hanya boleh satu per satu di API < 34, jadi diantrekan.
    @Synchronized
    private fun resolveNext() {
        val next = resolveQueue.removeFirstOrNull()
        if (next == null) {
            resolving = false
            return
        }
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                resolved(info)
                resolveNext()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()
        })
    }

    @Synchronized
    private fun resolved(info: NsdServiceInfo) {
        val hostId = info.attributes["hostId"]?.toString(Charsets.UTF_8) ?: return
        @Suppress("DEPRECATION")
        val host = info.host as? Inet4Address ?: return
        serviceToHost[info.serviceName] = hostId
        addresses[hostId] = "${host.hostAddress}:${info.port}"
        onChange(addresses.toMap())
    }
}
