package com.ledpiano.app.led.transport

import com.ledpiano.app.midi.Hand
import java.util.UUID

/**
 * BLE transport speaking the original Led Organ LED protocol over a
 * Nordic UART link (service 6E400001, write characteristic 6E400002).
 *
 * Frame: `7F [key,state]… 6E` – the frame is the complete picture of lit
 * keys; anything absent is off. The device accepts up to 20 bytes per BLE
 * write, so longer frames are chunked transparently by [send].
 */
class NusLegacyTransport : BleLedTransport(SERVICE_UUID, WRITE_UUID, null) {

    override fun send(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + MAX_WRITE_SIZE, data.size)
            super.send(data.copyOfRange(offset, end))
            offset = end
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        const val MAX_WRITE_SIZE = 20

        // Key states.
        const val STATE_RIGHT_WHITE = 0x01
        const val STATE_RIGHT_BLACK = 0x02
        const val STATE_LEFT_WHITE = 0x03
        const val STATE_LEFT_BLACK = 0x04

        /** All-off frame sent on connect and when nothing is sounding. */
        val FRAME_RESET = byteArrayOf(0x7F, 0x01, 0x00, 0x6E)

        /**
         * Strip byte for a piano key.
         *
         * Keys are counted left to right from A0 (index 0 … 87) and the strip
         * is addressed right to left: byte = 88 - index. [organOffset] is the
         * MIDI note of the first key covered by the strip (21 = A0) and shifts
         * the mapping like the original app's 1-based organ setting.
         */
        fun keyByte(pitch: Int, organOffset: Int): Int =
            88 - (organOffset - 21) - (pitch - 21)

        fun stateByte(hand: Hand, isBlack: Boolean): Int = when {
            hand == Hand.LEFT && isBlack -> STATE_LEFT_BLACK
            hand == Hand.LEFT -> STATE_LEFT_WHITE
            isBlack -> STATE_RIGHT_BLACK
            else -> STATE_RIGHT_WHITE
        }

        /** Builds one full frame from (keyByte, stateByte) pairs. */
        fun buildFrame(entries: List<Pair<Int, Int>>): ByteArray {
            if (entries.isEmpty()) return FRAME_RESET.copyOf()
            val out = ByteArray(entries.size * 2 + 2)
            out[0] = 0x7F
            out[out.size - 1] = 0x6E
            var i = 1
            for ((key, state) in entries) {
                out[i++] = key.toByte()
                out[i++] = state.toByte()
            }
            return out
        }
    }
}
