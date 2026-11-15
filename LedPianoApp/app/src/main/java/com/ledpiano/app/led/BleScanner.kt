package com.ledpiano.app.led

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Thin wrapper around [android.bluetooth.le.BluetoothLeScanner] with the
 * permission handling for both the legacy (API ≤ 30) and modern (API 31+)
 * Bluetooth permission models.
 */
class BleScanner(private val context: Context) {

    data class Device(val name: String, val address: String, val rssi: Int)

    interface Callback {
        fun onDeviceFound(device: Device)
        fun onScanFinished()
        fun onScanFailed(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Device>()

    @Volatile private var scanning = false
    private var callback: Callback? = null

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
            )
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothEnabled(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun start(callback: Callback) {
        stop()
        this.callback = callback
        if (!hasPermissions()) {
            callback.onScanFailed("Chưa cấp quyền Bluetooth")
            return
        }
        val scanner = bluetoothLeScanner()
        if (scanner == null) {
            callback.onScanFailed("Bluetooth chưa được bật")
            return
        }
        synchronized(found) { found.clear() }
        scanning = true
        try {
            scanner.startScan(scanCallback)
        } catch (e: Exception) {
            scanning = false
            callback.onScanFailed("Không thể bắt đầu quét: ${e.message}")
            return
        }
        mainHandler.postDelayed({ stop() }, SCAN_PERIOD_MS)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        try {
            bluetoothLeScanner()?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        val cb = callback
        callback = null
        cb?.onScanFinished()
    }

    private fun bluetoothLeScanner() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            @SuppressLint("MissingPermission")
            val name = device.name?.takeIf { it.isNotBlank() } ?: return
            val entry = Device(name, address, result.rssi)
            synchronized(found) {
                if (found.containsKey(address)) return
                found[address] = entry
            }
            mainHandler.post { callback?.onDeviceFound(entry) }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post { callback?.onScanFailed("Lỗi quét Bluetooth ($errorCode)") }
        }
    }

    companion object {
        const val SCAN_PERIOD_MS = 12_000L
    }
}
