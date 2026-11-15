package com.ledpiano.app.led.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Finds LED controllers on the same Wi-Fi by themselves, so nobody has to type an IP address:
 *  - LED Piano Strip boards (ESP32 / S3 / C3 firmware): the "LEDPIANO?" broadcast ([StripWifiTransport.discover])
 *    and the Bonjour / mDNS service "_ledpiano._tcp" (firmware 2.1+);
 *  - WLED controllers: their Bonjour / mDNS service "_wled._tcp" (every WLED version advertises it).
 *
 * Same as LedFinder.swift (iPhone, iPad, Mac) and wled.find_controllers() (Python).
 * Blocking: call off the main thread. Returns (name shown in the list, address for LedController.connect).
 */
object WifiFinder {
    const val WLED_SERVICE = "_wled._tcp."
    const val STRIP_SERVICE = "_ledpiano._tcp."

    fun find(context: Context, timeoutMs: Long = 2500): List<Pair<String, String>> {
        val found = ConcurrentHashMap<String, String>() // address → name
        val broadcast = Thread {
            for ((name, addr) in StripWifiTransport.discover(timeoutMs.coerceAtMost(1500))) found[addr] = name
        }.apply { isDaemon = true; start() }
        for ((name, addr) in browse(context, timeoutMs)) found.putIfAbsent(addr, name)
        broadcast.join(timeoutMs)
        return found.entries.map { it.value to it.key }
            .sortedWith(compareBy({ !it.second.startsWith(StripWifiTransport.PREFIX) }, { it.first }))
    }

    /** mDNS browse for both services; resolves each one to an IPv4 address. */
    private fun browse(context: Context, timeoutMs: Long): List<Pair<String, String>> {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val toResolve = LinkedBlockingQueue<NsdServiceInfo>()
        val out = ConcurrentHashMap<String, String>()
        val listeners = listOf(WLED_SERVICE, STRIP_SERVICE).map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) { toResolve.offer(info) }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }.also { l ->
                try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
            }
        }
        val end = System.currentTimeMillis() + timeoutMs
        // Older Android resolves one service at a time, so resolve them one after the other.
        while (System.currentTimeMillis() < end) {
            val info = toResolve.poll(end - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: break
            val done = LinkedBlockingQueue<NsdServiceInfo?>()
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) { done.offer(r) }
                    override fun onResolveFailed(r: NsdServiceInfo, errorCode: Int) { done.offer(NsdServiceInfo()) }
                })
            } catch (_: Exception) {
                continue
            }
            val r = done.poll(1500, TimeUnit.MILLISECONDS) ?: continue
            @Suppress("DEPRECATION")
            val ip = r.host?.hostAddress?.takeIf { '.' in it } ?: continue // IPv4 only
            val wled = info.serviceType.contains("_wled")
            val label = info.serviceName.ifBlank { if (wled) "WLED" else "LED Piano Strip" }
            if (wled) out[WledTransport.PREFIX + ip] = "WLED · $label (Wi-Fi)"
            else out[StripWifiTransport.PREFIX + ip] = "$label (Wi-Fi)"
        }
        for (l in listeners) try { nsd.stopServiceDiscovery(l) } catch (_: Exception) {}
        return out.entries.map { it.value to it.key }
    }
}
