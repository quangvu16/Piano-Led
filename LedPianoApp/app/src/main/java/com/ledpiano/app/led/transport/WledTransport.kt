package com.ledpiano.app.led.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ledpiano.app.led.Ws2812
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Marker: the controller takes [Ws2812] pixel messages (ESP32 strip over BLE, or WLED over Wi-Fi). */
interface PixelTransport : LedTransport

/**
 * Wi-Fi pixel output. Four standard protocols, chosen by the address prefix:
 *
 * - "e131:IP" or "e131:IP/universe" — E1.31 / sACN (UDP 5568, universe 1 = first 170 LEDs): WLED,
 *   ESPixelStick, Falcon and most Wi-Fi pixel controllers, and the LED Piano Strip firmware.
 * - "ddp:IP" — DDP (UDP 4048, 480 LEDs per packet): WLED, ESPixelStick, xLights controllers.
 *
 * - "wled:IP"   — WLED controllers (below).
 * - "artnet:IP" or "artnet:IP/universe" — Art-Net nodes such as the BanlanX **SP801E**
 *   (Art-Net mode): ArtDmx packets on UDP 6454, 170 RGB LEDs per universe, starting at
 *   the given universe (default 0). Art-Net is a public standard (Artistic Licence).
 *
 * The phone drives a WS2812B strip over Wi-Fi through a ready-made WLED controller
 * (e.g. Gledopto "WLED" boxes; nothing to program). WLED's realtime UDP mode
 * (kno.wled.ge/interfaces/udp-realtime): port 21324, DNRGB packets
 * `04, timeout, startHigh, startLow, r, g, b, …` (≤ 489 LEDs per packet).
 *
 * It accepts the same [Ws2812] messages as the ESP32 strip, keeps the whole strip
 * picture here and sends all of it, so LEDs not listed are dark. The picture is
 * re-sent every second; if the app goes away WLED returns to its own mode after
 * [TIMEOUT_S] seconds.
 *
 * Address: "wled:192.168.1.50" (optionally ":port").
 */
class WledTransport(private val numLeds: () -> Int) : PixelTransport {

    private var artNet = false
    private var kind = "wled" // wled | artnet | e131 | ddp
    private var universe = 0
    private var sequence = 0

    override var listener: LedTransport.Listener? = null
    @Volatile private var connected = false
    override val isConnected: Boolean get() = connected

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "WLED-UDP").apply { isDaemon = true } }
    private var socket: DatagramSocket? = null
    private var target: InetAddress? = null
    private var port = DEFAULT_PORT

    // Strip picture (r, g, b per LED) and brightness 0..255 applied before sending.
    private var frame = ByteArray(0)
    private var brightness = 255
    private val parser = MessageParser()

    override fun connect(context: Context, address: String) {
        artNet = address.startsWith(ARTNET_PREFIX)
        kind = when {
            artNet -> "artnet"
            address.startsWith(E131_PREFIX) -> "e131"
            address.startsWith(DDP_PREFIX) -> "ddp"
            else -> "wled"
        }
        var hostPort = address.removePrefix(PREFIX).removePrefix(ARTNET_PREFIX).removePrefix(E131_PREFIX)
            .removePrefix(DDP_PREFIX).trim()
        universe = if (kind == "e131") 1 else 0
        if ((artNet || kind == "e131") && hostPort.contains('/')) {
            val def = universe
            universe = hostPort.substringAfter('/').toIntOrNull()?.coerceIn(def, 63999) ?: def
            hostPort = hostPort.substringBefore('/')
        }
        io.execute {
            try {
                val i = hostPort.lastIndexOf(':')
                val host = if (i > 0 && hostPort.count { it == ':' } == 1) hostPort.substring(0, i) else hostPort
                val def = when (kind) { "artnet" -> ARTNET_PORT; "e131" -> E131_PORT; "ddp" -> DDP_PORT; else -> DEFAULT_PORT }
                port = if (i > 0 && hostPort.count { it == ':' } == 1) hostPort.substring(i + 1).toIntOrNull() ?: def else def
                target = InetAddress.getByName(host)
                socket = DatagramSocket()
                frame = ByteArray(numLeds().coerceIn(1, MAX_LEDS) * 3)
                connected = true
                io.scheduleWithFixedDelay({ if (connected) flushNow() }, 1, 1, TimeUnit.SECONDS)
                main.post { listener?.onConnected() }
            } catch (e: Exception) {
                main.post { listener?.onError("Không tìm thấy bộ điều khiển ở $hostPort: ${e.message}") }
            }
        }
    }

    override fun disconnect() {
        if (!connected) { io.shutdown(); return }
        io.execute {
            // Dark, with a 1 s timeout so WLED goes back to its own mode right away.
            frame.fill(0)
            flushNow(timeout = 1)
            connected = false
            socket?.close()
            socket = null
        }
        io.shutdown()
        main.post { listener?.onDisconnected("Ngắt kết nối") }
    }

    override fun send(data: ByteArray) {
        if (!connected) return
        val copy = data.copyOf()
        if (io.isShutdown) return
        io.execute {
            var changed = false
            for (b in copy) if (parser.feed(b.toInt() and 0xFF)) { apply(parser); changed = true }
            if (changed) flushNow()
        }
    }

    private fun apply(p: MessageParser) {
        val n = numLeds().coerceIn(1, MAX_LEDS)
        if (frame.size != n * 3) frame = ByteArray(n * 3)
        when (p.op) {
            Ws2812.OP_PIXELS -> {
                frame.fill(0)
                var i = 0
                while (i + 4 < p.len) {
                    val idx = (p.data[i].toInt() and 0xFF) or ((p.data[i + 1].toInt() and 0xFF) shl 8)
                    if (idx < n) {
                        frame[idx * 3] = p.data[i + 2]; frame[idx * 3 + 1] = p.data[i + 3]; frame[idx * 3 + 2] = p.data[i + 4]
                    }
                    i += 5
                }
            }
            Ws2812.OP_FILL -> for (k in 0 until n) { frame[k * 3] = p.data[0]; frame[k * 3 + 1] = p.data[1]; frame[k * 3 + 2] = p.data[2] }
            Ws2812.OP_BRIGHTNESS -> brightness = p.data[0].toInt() and 0xFF
        }
    }

    /** Sends the whole picture (IO thread only). */
    private fun flushNow(timeout: Int = TIMEOUT_S) {
        val s = socket ?: return
        val addr = target ?: return
        if (kind != "wled") {
            try {
                val pkts = when (kind) {
                    "artnet" -> artDmxPackets(frame, brightness, universe, nextSequence())
                    "e131" -> e131Packets(frame, brightness, universe, nextSequence())
                    else -> ddpPackets(frame, brightness, nextSequence())
                }
                for (pkt in pkts) s.send(DatagramPacket(pkt, pkt.size, addr, port))
            } catch (e: Exception) {
                if (connected) main.post { listener?.onError("Gửi gói ${kind.uppercase()} lỗi: ${e.message}") }
            }
            return
        }
        val leds = frame.size / 3
        var start = 0
        try {
            do {
                val count = minOf(PER_PACKET, leds - start)
                val pkt = ByteArray(4 + count * 3)
                pkt[0] = 4 // DNRGB
                pkt[1] = timeout.toByte()
                pkt[2] = (start shr 8).toByte()
                pkt[3] = start.toByte()
                for (k in 0 until count * 3) {
                    pkt[4 + k] = ((frame[start * 3 + k].toInt() and 0xFF) * brightness / 255).toByte()
                }
                s.send(DatagramPacket(pkt, pkt.size, addr, port))
                start += count
            } while (start < leds)
        } catch (e: Exception) {
            if (connected) main.post { listener?.onError("Gửi tới WLED lỗi: ${e.message}") }
        }
    }

    /** Reassembles `A5 op len payload chk` messages (same checks as the ESP32 firmware). */
    class MessageParser {
        private var st = 0
        var op = 0; var len = 0
        val data = ByteArray(255)
        private var got = 0
        private var chk = 0

        fun feed(b: Int): Boolean {
            when (st) {
                0 -> if (b == 0xA5) st = 1
                1 -> { op = b; chk = b; st = 2 }
                2 -> { len = b; chk = chk xor b; got = 0; st = if (len == 0) 4 else 3 }
                3 -> { data[got++] = b.toByte(); chk = chk xor b; if (got == len) st = 4 }
                4 -> { st = 0; return b == chk }
            }
            return false
        }
    }

    private fun nextSequence(): Int {
        sequence = if (sequence >= 255) 1 else sequence + 1
        return sequence
    }

    companion object {
        const val E131_PREFIX = "e131:"
        const val DDP_PREFIX = "ddp:"
        const val E131_PORT = 5568
        const val DDP_PORT = 4048
        const val DDP_PER_PACKET = 480
        /** Fixed 16-byte E1.31 sender id (same as the Python app). */
        private val E131_CID = byteArrayOf(0x4c, 0x45, 0x44, 0x50, 0x69, 0x61, 0xe0.toByte(), 0x0a, 0x8c.toByte(), 0x5e, 0x5a,
            0x1e, 0x0d, 0x2c, 0x77, 0x01)

        /** E1.31 (sACN) data packets: root, framing and DMP layers; 170 RGB LEDs (510 slots) per universe. */
        fun e131Packets(frame: ByteArray, brightness: Int, startUniverse: Int, sequence: Int): List<ByteArray> {
            val leds = frame.size / 3
            val out = ArrayList<ByteArray>()
            var start = 0
            var u = maxOf(1, startUniverse)
            val name = "LED Piano".toByteArray(Charsets.UTF_8)
            do {
                val count = minOf(ARTNET_PER_UNIVERSE, leds - start)
                val slots = maxOf(count, 0) * 3
                val n = 126 + slots
                val p = ByteArray(n)
                fun put16(i: Int, v: Int) { p[i] = (v shr 8).toByte(); p[i + 1] = v.toByte() }
                fun put32(i: Int, v: Int) { put16(i, v shr 16); put16(i + 2, v and 0xFFFF) }
                put16(0, 0x0010)
                "ASC-E1.17".toByteArray(Charsets.US_ASCII).copyInto(p, 4)
                put16(16, 0x7000 or (n - 16)); put32(18, 4); E131_CID.copyInto(p, 22)
                put16(38, 0x7000 or (n - 38)); put32(40, 2); name.copyInto(p, 44)
                p[108] = 100; p[111] = sequence.toByte(); put16(113, u)
                put16(115, 0x7000 or (n - 115)); p[117] = 0x02; p[118] = 0xA1.toByte(); put16(121, 1)
                put16(123, 1 + slots)
                for (k in 0 until slots) p[126 + k] = ((frame[start * 3 + k].toInt() and 0xFF) * brightness / 255).toByte()
                out.add(p)
                start += maxOf(count, 1)
                u++
            } while (start < leds)
            return out
        }

        /** DDP packets: flags (V1, PUSH on the last), sequence 1..15, type RGB24, id 1, byte offset, length. */
        fun ddpPackets(frame: ByteArray, brightness: Int, sequence: Int): List<ByteArray> {
            val chunk = DDP_PER_PACKET * 3
            val out = ArrayList<ByteArray>()
            var off = 0
            do {
                val len = minOf(chunk, frame.size - off)
                val last = off + chunk >= frame.size
                val p = ByteArray(10 + len)
                p[0] = if (last) 0x41 else 0x40
                p[1] = ((sequence and 0x0F).takeIf { it != 0 } ?: 1).toByte()
                p[2] = 0x0B; p[3] = 0x01
                p[4] = (off shr 24).toByte(); p[5] = (off shr 16).toByte(); p[6] = (off shr 8).toByte(); p[7] = off.toByte()
                p[8] = (len shr 8).toByte(); p[9] = len.toByte()
                for (k in 0 until len) p[10 + k] = ((frame[off + k].toInt() and 0xFF) * brightness / 255).toByte()
                out.add(p)
                off += chunk
            } while (off < frame.size)
            return out
        }

        /** Adalight (Arduino Uno/Nano sketches, Hyperion, Prismatik): "Ada", count−1, checksum, r g b … */
        fun adalightPacket(frame: ByteArray, brightness: Int): ByteArray {
            val leds = maxOf(1, frame.size / 3)
            val n = leds - 1
            val hi = (n shr 8) and 0xFF
            val lo = n and 0xFF
            val p = ByteArray(6 + leds * 3)
            p[0] = 'A'.code.toByte(); p[1] = 'd'.code.toByte(); p[2] = 'a'.code.toByte()
            p[3] = hi.toByte(); p[4] = lo.toByte(); p[5] = (hi xor lo xor 0x55).toByte()
            for (k in 0 until minOf(frame.size, leds * 3)) p[6 + k] = ((frame[k].toInt() and 0xFF) * brightness / 255).toByte()
            return p
        }

        const val ARTNET_PREFIX = "artnet:"
        const val ARTNET_PORT = 6454
        const val ARTNET_PER_UNIVERSE = 170

        /**
         * ArtDmx packets for the whole strip: "Art-Net\0", OpCode 0x5000 (little-endian),
         * protocol 14, sequence, physical 0, universe (low byte, then net), length (big-endian, even), data.
         */
        fun artDmxPackets(frame: ByteArray, brightness: Int, startUniverse: Int, sequence: Int): List<ByteArray> {
            val leds = frame.size / 3
            val out = ArrayList<ByteArray>()
            var start = 0
            var u = startUniverse
            while (start < leds) {
                val count = minOf(ARTNET_PER_UNIVERSE, leds - start)
                var len = count * 3
                if (len % 2 == 1) len++
                val pkt = ByteArray(18 + len)
                "Art-Net".toByteArray(Charsets.US_ASCII).copyInto(pkt)
                pkt[8] = 0x00; pkt[9] = 0x50          // OpDmx 0x5000, little-endian
                pkt[10] = 0; pkt[11] = 14              // protocol version 14
                pkt[12] = sequence.toByte(); pkt[13] = 0
                pkt[14] = (u and 0xFF).toByte()        // SubUni
                pkt[15] = (u shr 8 and 0x7F).toByte()  // Net
                pkt[16] = (len shr 8).toByte(); pkt[17] = len.toByte()
                for (k in 0 until count * 3) pkt[18 + k] = ((frame[start * 3 + k].toInt() and 0xFF) * brightness / 255).toByte()
                out.add(pkt)
                start += count
                u++
            }
            return out
        }

        const val PREFIX = "wled:"
        const val DEFAULT_PORT = 21324
        const val TIMEOUT_S = 3
        const val PER_PACKET = 489
        const val MAX_LEDS = 1500
    }
}
