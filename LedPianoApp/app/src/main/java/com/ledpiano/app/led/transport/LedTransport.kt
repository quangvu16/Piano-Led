package com.ledpiano.app.led.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.Executors

/** A raw byte pipe to an LED controller. */
interface LedTransport {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onDataReceived(data: ByteArray) {}
        fun onError(message: String) {}

        /** Connected, but the controller speaks another protocol; [available] lists its services. */
        fun onServiceMissing(available: Set<UUID>) {
            onError("Thiết bị không hỗ trợ giao thức đã chọn")
        }
    }

    var listener: Listener?
    val isConnected: Boolean

    fun connect(context: Context, address: String)
    fun disconnect()

    /** Queues one frame for transmission; safe to call from any thread. */
    fun send(data: ByteArray)
}

/**
 * Shared BLE GATT implementation for LED controllers.
 *
 * Discovers the service/characteristic pair given by the subclass and then
 * works as an ordered byte pipe. All writes go through a single thread so
 * frames cannot interleave.
 */
abstract class BleLedTransport(
    private val serviceUuid: UUID,
    private val writeUuid: UUID,
    private val notifyUuid: UUID?,
) : LedTransport {

    override var listener: LedTransport.Listener? = null

    @Volatile
    private var connected = false

    override val isConnected: Boolean get() = connected

    /** Write-without-response is faster; some UART bridges prefer responses. */
    protected open val preferNoResponse: Boolean = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LedTransport-IO")
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var useNoResponse = true

    /** Bigger Bluetooth packets to ask for (0 = keep the default 20-byte writes). */
    protected open val wantedMtu: Int = 0

    /** Bytes that fit in ONE write on this link (20 until the controller agrees to bigger packets). */
    @Volatile
    protected var maxWriteSize: Int = 20
        private set
    private var discoveryGatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    override fun connect(context: Context, address: String) {
        teardown()
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener?.onError("Bluetooth chưa được bật")
            return
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            listener?.onError("Địa chỉ Bluetooth không hợp lệ: $address")
            return
        }
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            listener?.onError("Không thể mở kết nối Bluetooth")
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        val g = gatt
        val wasConnected = connected
        teardown()
        try {
            g?.disconnect()
        } catch (_: Exception) {
        }
        g?.close()
        if (wasConnected) {
            mainHandler.post { listener?.onDisconnected("Ngắt kết nối") }
        }
    }

    override fun send(data: ByteArray) {
        ioExecutor.execute { writeInternal(data) }
    }

    @SuppressLint("MissingPermission")
    private fun writeInternal(data: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return
        if (!isConnected) return
        try {
            @Suppress("DEPRECATION")
            c.value = data
            c.writeType =
                if (useNoResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            // Android accepts one Bluetooth operation at a time: when it is still busy with the last
            // one, wait a moment and try again instead of dropping the piece (songs sent to the ESP32
            // are hundreds of pieces long).
            var tries = 0
            while (!g.writeCharacteristic(c) && tries++ < 40 && isConnected) Thread.sleep(5)
        } catch (e: Exception) {
            mainHandler.post { listener?.onError("Lỗi gửi dữ liệu LED: ${e.message}") }
        }
    }

    private fun teardown() {
        connected = false
        writeChar = null
        maxWriteSize = 20
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery(g: BluetoothGatt) {
        synchronized(this) {
            if (discoveryGatt === g) return // once per connection
            discoveryGatt = g
        }
        try {
            g.discoverServices()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = connected
                teardown()
                g.close()
                if (gatt === g) gatt = null
                if (wasConnected) {
                    mainHandler.post { listener?.onDisconnected("Mất kết nối với thiết bị") }
                } else {
                    mainHandler.post { listener?.onError("Kết nối Bluetooth thất bại") }
                }
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Ask for bigger packets first, so a whole picture (many lit keys) travels in one
                // write instead of several 20-byte pieces that can get lost on the way.
                if (wantedMtu > 23 && g.requestMtu(wantedMtu)) {
                    mainHandler.postDelayed({ startDiscovery(g) }, 1500) // no answer: carry on anyway
                } else {
                    startDiscovery(g)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) maxWriteSize = mtu - 3
            startDiscovery(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { listener?.onError("Không tìm thấy dịch vụ Bluetooth") }
                return
            }
            val service = g.getService(serviceUuid)
            // An LED Piano Strip (ESP32 + WS2812B) also speaks Led Organ; prefer its own pixel mode.
            val betterWs = serviceUuid != Ws2812Transport.SERVICE_UUID && g.getService(Ws2812Transport.SERVICE_UUID) != null
            if (service == null || betterWs) {
                val available = g.services.map { it.uuid }.toSet()
                mainHandler.post { listener?.onServiceMissing(available) }
                return
            }
            val write = service.getCharacteristic(writeUuid)
            if (write == null) {
                mainHandler.post { listener?.onError("Thiết bị không hỗ trợ đặc tính $writeUuid") }
                return
            }
            writeChar = write
            useNoResponse = preferNoResponse &&
                (write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

            // High throughput link for the 25 Hz spectrum feed.
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            if (notifyUuid != null) {
                val notify = service.getCharacteristic(notifyUuid)
                if (notify != null) {
                    g.setCharacteristicNotification(notify, true)
                    val cccd = notify.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
            }

            connected = true
            mainHandler.post { listener?.onConnected() }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            val copy = value.copyOf()
            mainHandler.post { listener?.onDataReceived(copy) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Notification enable result – nothing to do.
        }
    }

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
