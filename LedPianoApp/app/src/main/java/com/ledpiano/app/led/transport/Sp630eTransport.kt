package com.ledpiano.app.led.transport

import java.util.UUID

/**
 * BanlanX SP630E (and, untested, SP802E) over Bluetooth.
 *
 * These controllers have no "one LED per key" command: the whole strip shows one colour. So the
 * app lights the strip in the colour of the hand that plays now (right / left), and turns it off
 * between notes. For one LED per key, use an ESP32 strip, WLED or the SP801E (Art-Net).
 *
 * Protocol from UniLED (github.com/monty68/uniled, lib/ble/banlanx_6xx.py, MIT licence):
 * service FFE0 (some units E0FF), write characteristic FFE1, every message
 *     53  cmd  00(key)  01  00  len  payload…
 *  - 0x50 power: 01 on / 00 off
 *  - 0x51 brightness: 00 (colour channel), level 0..255
 *  - 0x52 static colour: r, g, b, level
 *  - 0x53 mode + effect: 01 (static colour), 01 (solid)
 * SP802E: BanlanX publishes no protocol; it is assumed to share this one (same app, same family).
 */
class Sp630eTransport(serviceUuid: UUID = SERVICE_UUID) : BleLedTransport(serviceUuid, WRITE_UUID, null) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        /** Some SP630E units list this service instead. */
        val SERVICE_UUID_ALT: UUID = UUID.fromString("0000e0ff-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }
}

object Sp630eProtocol {
    private fun message(cmd: Int, vararg payload: Int): ByteArray {
        val out = ByteArray(6 + payload.size)
        out[0] = 0x53
        out[1] = cmd.toByte()
        out[2] = 0x00 // key: plain (not encrypted)
        out[3] = 0x01
        out[4] = 0x00
        out[5] = payload.size.toByte()
        payload.forEachIndexed { i, b -> out[6 + i] = (b and 0xFF).toByte() }
        return out
    }

    fun power(on: Boolean) = message(0x50, if (on) 1 else 0)

    fun brightness(level: Int) = message(0x51, 0x00, level.coerceIn(0, 255))

    /** Static colour mode with the "solid" effect (needed once after connecting). */
    fun staticMode() = message(0x53, 0x01, 0x01)

    /** The whole strip in one colour (0xRRGGBB) at [level]. */
    fun color(rgb: Int, level: Int) =
        message(0x52, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, level.coerceIn(0, 255))
}
