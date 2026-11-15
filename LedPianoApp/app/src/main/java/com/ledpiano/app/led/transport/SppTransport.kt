package com.ledpiano.app.led.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Bluetooth "Classic" serial port (SPP) – the HC-05 / HC-06 modules often wired to an Arduino Uno/Nano.
 * A plain byte pipe: [AdalightTransport] puts the Adalight protocol on top of it.
 * (iPhones cannot use SPP modules: iOS only talks to Bluetooth LE devices.)
 *
 * Address: "spp:00:21:13:01:23:45". The module must be paired first in Android's Bluetooth settings
 * (PIN 1234 or 0000).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is asked for with the other Bluetooth permissions
class SppTransport : LedTransport {

    override var listener: LedTransport.Listener? = null
    @Volatile private var connected = false
    override val isConnected: Boolean get() = connected

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "SPP").apply { isDaemon = true } }
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    @Volatile private var closing = false

    override fun connect(context: Context, address: String) {
        val mac = address.removePrefix(PREFIX).trim().uppercase()
        closing = false
        io.execute {
            try {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: throw IllegalStateException("không có Bluetooth")
                val dev: BluetoothDevice = adapter.getRemoteDevice(mac)
                adapter.cancelDiscovery()
                val s = dev.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                out = s.outputStream
                connected = true
                startReader(s)
                main.post { listener?.onConnected() }
            } catch (e: Exception) {
                main.post { listener?.onError("Không kết nối được module Bluetooth $mac (${e.message}) — đã ghép đôi trong Cài đặt Bluetooth chưa?") }
            }
        }
    }

    override fun disconnect() {
        val was = connected
        closing = true
        connected = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        if (was) main.post { listener?.onDisconnected("Ngắt kết nối") }
    }

    override fun send(data: ByteArray) {
        if (!connected) return
        val copy = data.copyOf()
        io.execute {
            try {
                out?.write(copy)
            } catch (_: Exception) {
                lost()
            }
        }
    }

    private fun startReader(s: BluetoothSocket) {
        Thread({
            val buf = ByteArray(128)
            try {
                while (connected) {
                    val n = s.inputStream.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        val copy = buf.copyOf(n)
                        main.post { listener?.onDataReceived(copy) }
                    }
                }
            } catch (_: Exception) {
            }
            lost()
        }, "SPP-read").apply { isDaemon = true; start() }
    }

    private fun lost() {
        if (!connected || closing) return
        connected = false
        main.post { listener?.onDisconnected("Mất kết nối Bluetooth với module") }
    }

    companion object {
        const val PREFIX = "spp:"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Paired Bluetooth Classic devices that look like serial modules (HC-05, HC-06, JDY-31 …) first. */
        fun bondedModules(context: Context): List<Pair<String, String>> = try {
            val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.bondedDevices.orEmpty()
                .filter { it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || it.type == BluetoothDevice.DEVICE_TYPE_DUAL }
                .map { (it.name ?: it.address) to PREFIX + it.address }
                .sortedBy { (name, _) -> if (Regex("HC-0|JDY|BT0|SPP", RegexOption.IGNORE_CASE).containsMatchIn(name)) 0 else 1 }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
