package com.ledpiano.app.led.transport

import java.util.UUID

/**
 * LED Piano Strip firmware (ESP32 + WS2812B + FastLED): the app sets each LED itself
 * (see [com.ledpiano.app.led.Ws2812]). Writes are cut into pieces that fit one Bluetooth packet; the firmware
 * reassembles them. Notifications on [NOTIFY_UUID] carry the notes of a Bluetooth MIDI piano paired
 * with the ESP32 (firmware 2).
 */
class Ws2812Transport : BleLedTransport(SERVICE_UUID, WRITE_UUID, NOTIFY_UUID), Esp32Transport {

    override val wantedMtu: Int = 185 // what the firmware offers: a 50-LED picture fits in two writes

    override fun send(data: ByteArray) {
        val piece = maxWriteSize.coerceIn(MAX_WRITE_SIZE, MAX_PIECE)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + piece, data.size)
            super.send(data.copyOfRange(offset, end))
            offset = end
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("8f1b0001-6c3e-4c1a-9f2e-5a1e0d2c7701")
        val WRITE_UUID: UUID = UUID.fromString("8f1b0002-6c3e-4c1a-9f2e-5a1e0d2c7701")
        /** Firmware 2: piano notes and status from the ESP32 (missing on older firmware – that is fine). */
        val NOTIFY_UUID: UUID = UUID.fromString("8f1b0003-6c3e-4c1a-9f2e-5a1e0d2c7701")
        const val MAX_WRITE_SIZE = 20
        /** The firmware takes up to 200 bytes per write. */
        const val MAX_PIECE = 180
    }
}
