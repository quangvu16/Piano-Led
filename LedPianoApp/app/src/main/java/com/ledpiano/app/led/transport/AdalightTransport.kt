package com.ledpiano.app.led.transport

import android.content.Context
import com.ledpiano.app.led.Ws2812
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * An Arduino Uno / Nano (or anything running an Adalight sketch, e.g. LedPianoAdalight.ino) on a
 * USB cable or an HC-05 Bluetooth module. Plain Arduinos have no Bluetooth LE or Wi-Fi, so this is
 * how the board from the HowToMechatronics FastLED tutorial joins in.
 *
 * Takes the same [Ws2812] messages as the ESP32, keeps the whole strip picture and sends it as one
 * Adalight packet ("Ada", count−1, checksum, r g b …) whenever it changes and once a second.
 *
 * Address: "ada:" + the link underneath — "ada:usb:2341:0043" (USB) or "ada:spp:00:21:13:01:23:45" (HC-05).
 */
class AdalightTransport(private val numLeds: () -> Int) : PixelTransport {

    private val link: LedTransport? get() = inner
    private var inner: LedTransport? = null

    override var listener: LedTransport.Listener? = null
    override val isConnected: Boolean get() = inner?.isConnected == true

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Adalight").apply { isDaemon = true } }
    private var frame = ByteArray(0)
    private var brightness = 255
    private val parser = WledTransport.MessageParser()

    override fun connect(context: Context, address: String) {
        val rest = address.removePrefix(PREFIX)
        val t: LedTransport = if (rest.startsWith(SppTransport.PREFIX)) SppTransport() else UsbSerialTransport()
        inner = t
        t.listener = object : LedTransport.Listener {
            override fun onConnected() {
                io.scheduleWithFixedDelay({ if (isConnected) flushNow() }, 1, 1, TimeUnit.SECONDS)
                listener?.onConnected()
            }
            override fun onDisconnected(reason: String?) { listener?.onDisconnected(reason) }
            override fun onError(message: String) { listener?.onError(message) }
            override fun onDataReceived(data: ByteArray) {} // the sketch only says "Ada"
        }
        t.connect(context, rest)
    }

    override fun disconnect() {
        if (isConnected) {
            io.execute {
                frame.fill(0)
                flushNow() // dark
            }
        }
        io.execute { inner?.disconnect() }
        io.shutdown()
    }

    override fun send(data: ByteArray) {
        if (!isConnected || io.isShutdown) return
        val copy = data.copyOf()
        io.execute {
            var changed = false
            for (b in copy) if (parser.feed(b.toInt() and 0xFF)) { apply(); changed = true }
            if (changed) flushNow()
        }
    }

    private fun apply() {
        val n = numLeds().coerceIn(1, MAX_LEDS)
        if (frame.size != n * 3) frame = ByteArray(n * 3)
        val p = parser
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

    private fun flushNow() {
        if (frame.isEmpty()) frame = ByteArray(numLeds().coerceIn(1, MAX_LEDS) * 3)
        link?.send(WledTransport.adalightPacket(frame, brightness))
    }

    companion object {
        const val PREFIX = "ada:"
        const val MAX_LEDS = 600
    }
}
