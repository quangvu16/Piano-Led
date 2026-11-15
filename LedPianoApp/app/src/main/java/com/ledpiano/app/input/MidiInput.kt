package com.ledpiano.app.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * Reads notes from a digital piano over USB (OTG cable) or Bluetooth LE MIDI
 * using Android's built-in MIDI API (no extra library).
 *
 * USB keyboards are opened automatically as soon as they are plugged in.
 * Bluetooth MIDI keyboards must be picked once ([openBluetooth]); Android
 * only exposes them while we hold the returned [MidiDevice].
 */
class MidiInput(context: Context, private val hub: NoteInputHub) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager: MidiManager? =
        if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            appContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        } else {
            null
        }

    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null
    private var started = false

    val isSupported: Boolean get() = manager != null

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) {
            if (device == null && hub.source != InputSource.MIC) open(info)
        }

        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (device?.info?.id == info.id) {
                close()
                // Another keyboard may still be attached.
                autoOpenFirst()
            }
        }
    }

    /** Starts watching for keyboards and opens one if already attached. */
    fun start() {
        val m = manager ?: return
        if (started) return
        started = true
        @Suppress("DEPRECATION")
        m.registerDeviceCallback(deviceCallback, main)
        autoOpenFirst()
    }

    fun stop() {
        val m = manager ?: return
        if (!started) return
        started = false
        m.unregisterDeviceCallback(deviceCallback)
        close()
    }

    @Suppress("DEPRECATION")
    private fun autoOpenFirst() {
        val m = manager ?: return
        if (device != null) return
        val info = m.devices.firstOrNull { it.outputPortCount > 0 } ?: return
        open(info)
    }

    /** Opens a Bluetooth LE MIDI keyboard by its address (from a BLE scan). */
    @SuppressLint("MissingPermission")
    fun openBluetooth(address: String, onResult: (Boolean) -> Unit) {
        val m = manager
        if (m == null) {
            onResult(false)
            return
        }
        val bt = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val btDevice: BluetoothDevice = try {
            bt?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        } ?: run {
            onResult(false)
            return
        }
        close()
        m.openBluetoothDevice(btDevice, { d ->
            if (d == null) {
                onResult(false)
            } else {
                attach(d)
                onResult(port != null)
            }
        }, main)
    }

    private fun open(info: MidiDeviceInfo) {
        val m = manager ?: return
        if (info.outputPortCount == 0) return
        m.openDevice(info, { d -> if (d != null) attach(d) }, main)
    }

    private fun attach(d: MidiDevice) {
        val info = d.info
        val portIndex = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }?.portNumber ?: 0
        val p = d.openOutputPort(portIndex)
        if (p == null) {
            try {
                d.close()
            } catch (_: Exception) {
            }
            return
        }
        device = d
        port = p
        p.connect(parser)
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI"
        hub.setState(InputSource.MIDI, name)
    }

    fun close() {
        try {
            port?.disconnect(parser)
            port?.close()
        } catch (_: Exception) {
        }
        try {
            device?.close()
        } catch (_: Exception) {
        }
        val had = device != null
        port = null
        device = null
        if (had && hub.source == InputSource.MIDI) hub.setState(InputSource.NONE, null)
    }

    /** Minimal MIDI byte parser: note on/off with running status, ignores everything else. */
    private val parser = object : MidiReceiver() {
        private var status = 0
        private var data1 = -1

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (i in offset until offset + count) {
                val b = msg[i].toInt() and 0xFF
                when {
                    b >= 0xF8 -> Unit // real-time bytes may appear anywhere
                    b >= 0x80 -> {
                        status = if (b < 0xF0) b else 0
                        data1 = -1
                    }
                    status == 0 -> Unit
                    data1 < 0 -> data1 = b
                    else -> {
                        val type = status and 0xF0
                        if (type == 0x90 && b > 0) hub.emitNoteOn(data1, b)
                        else if (type == 0x80 || type == 0x90) hub.emitNoteOff(data1)
                        data1 = -1 // running status: next data byte starts a new message
                    }
                }
            }
        }
    }
}
