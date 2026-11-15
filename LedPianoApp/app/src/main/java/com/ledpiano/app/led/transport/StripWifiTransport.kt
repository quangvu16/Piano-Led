package com.ledpiano.app.led.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors

/**
 * The ESP32 "LED Piano Strip" over Wi-Fi: a TCP connection to port 7777 carrying the same
 * "A5 op len … chk" messages as Bluetooth and USB, in both directions. So everything works over
 * Wi-Fi too: one LED per key, songs stored on the ESP32, notes from its Bluetooth piano, instant
 * green/red. Useful when the phone's Bluetooth is busy (headphones, a MIDI keyboard) or the strip is
 * far away.
 *
 * Address: "strip:192.168.1.60" (optionally ":port"). [discover] finds boards: it broadcasts
 * "LEDPIANO?" on UDP 7778 and every LED Piano Strip answers "LEDPIANO <name>".
 */
class StripWifiTransport : Esp32Transport {

    override var listener: LedTransport.Listener? = null
    @Volatile private var connected = false
    override val isConnected: Boolean get() = connected

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "Strip-WiFi").apply { isDaemon = true } }
    private var socket: Socket? = null
    private var out: OutputStream? = null
    @Volatile private var closing = false

    override fun connect(context: Context, address: String) {
        val hp = address.removePrefix(PREFIX).trim()
        val host = hp.substringBefore(':')
        val port = hp.substringAfter(':', "").toIntOrNull() ?: PORT
        closing = false
        io.execute {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 4000)
                socket = s
                out = s.getOutputStream()
                connected = true
                startReader(s.getInputStream())
                main.post { listener?.onConnected() }
            } catch (e: Exception) {
                main.post { listener?.onError("Không kết nối được mạch ESP32 ở $hp qua Wi-Fi (${e.message}) — cùng mạng Wi-Fi chưa?") }
            }
        }
    }

    override fun disconnect() {
        val was = connected
        closing = true
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
        if (was) main.post { listener?.onDisconnected("Ngắt kết nối") }
    }

    override fun send(data: ByteArray) {
        if (!connected) return
        val copy = data.copyOf()
        io.execute {
            try {
                out?.write(copy)
                out?.flush()
            } catch (_: Exception) {
                lost()
            }
        }
    }

    private fun startReader(input: InputStream) {
        Thread({
            val buf = ByteArray(256)
            try {
                while (connected) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        val copy = buf.copyOf(n)
                        main.post { listener?.onDataReceived(copy) }
                    }
                }
            } catch (_: Exception) {
            }
            lost()
        }, "Strip-WiFi-read").apply { isDaemon = true; start() }
    }

    private fun lost() {
        if (!connected || closing) return
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        main.post { listener?.onDisconnected("Mất kết nối Wi-Fi với mạch ESP32") }
    }

    companion object {
        const val PREFIX = "strip:"
        const val PORT = 7777
        const val DISCOVERY_PORT = 7778

        /** LED Piano Strip boards on this Wi-Fi: [(name, "strip:IP")]. Blocks for [timeoutMs]; call off the main thread. */
        fun discover(timeoutMs: Long = 1500): List<Pair<String, String>> {
            val found = LinkedHashMap<String, String>()
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 300
                    val probe = "LEDPIANO?".toByteArray()
                    val end = System.currentTimeMillis() + timeoutMs
                    var nextProbe = 0L
                    val buf = ByteArray(256)
                    while (System.currentTimeMillis() < end) {
                        if (System.currentTimeMillis() >= nextProbe) {
                            try {
                                s.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                            } catch (_: Exception) {
                            }
                            nextProbe = System.currentTimeMillis() + 500
                        }
                        val pkt = DatagramPacket(buf, buf.size)
                        try {
                            s.receive(pkt)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                        if (text.startsWith("LEDPIANO ")) {
                            val ip = pkt.address.hostAddress ?: continue
                            found[ip] = text.removePrefix("LEDPIANO ").trim().ifEmpty { "LED Piano Strip" }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return found.map { (ip, name) -> "$name (Wi-Fi)" to PREFIX + ip }
        }
    }
}
