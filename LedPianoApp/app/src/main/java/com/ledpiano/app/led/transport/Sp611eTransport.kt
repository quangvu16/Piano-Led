package com.ledpiano.app.led.transport

import java.util.UUID

/**
 * BLE transport for the SP611E (BanlanX v2) controller.
 *
 * Service FFE0, characteristic FFE1 accepts writes and reports status
 * notifications (ASCII "SC" + packet counters).
 */
class Sp611eTransport : BleLedTransport(SERVICE_UUID, WRITE_UUID, WRITE_UUID) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }
}
