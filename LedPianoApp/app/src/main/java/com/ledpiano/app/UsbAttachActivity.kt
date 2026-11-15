package com.ledpiano.app

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.ledpiano.app.led.LedController
import com.ledpiano.app.led.transport.UsbSerialTransport

/**
 * Opened by Android when an ESP32 board is plugged in by USB (see res/xml/usb_serial_filter.xml).
 * Plugging in this way also gives the app permission to use the port, so it connects straight away.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val led = (application as LedPianoApp).ledController
        if (dev != null && UsbSerialTransport.kindOf(dev) != null && led.state != LedController.State.CONNECTED) {
            val settings = com.ledpiano.app.model.AppSettings(this)
            if (dev.vendorId == 0x2341 || settings.ledTransport == "adalight") {
                // Arduino Uno / Nano running the Adalight sketch (LedPianoAdalight.ino)
                led.connect(this, "ada:" + UsbSerialTransport.addressOf(dev), "Arduino (Adalight) · " + UsbSerialTransport.labelOf(dev))
            } else {
                led.connectUsb(this, UsbSerialTransport.addressOf(dev), UsbSerialTransport.labelOf(dev))
            }
            Toast.makeText(this, getString(R.string.usb_connecting), Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
