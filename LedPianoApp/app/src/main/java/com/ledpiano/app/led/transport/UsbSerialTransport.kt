package com.ledpiano.app.led.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Marker: the LED Piano Strip ESP32 itself (Bluetooth or USB) — it can store songs, pair a piano, flash keys. */
interface Esp32Transport : PixelTransport

/**
 * The ESP32 "LED Piano Strip" on a USB cable (USB-C / OTG adapter), like FriendBot's USB link
 * (robot/android …/UsbConnection.java), but with small built-in drivers instead of a downloaded library:
 *
 *  - CDC-ACM: ESP32-S3 / C3 native USB (Espressif 0x303A, the "USB" port of an ESP32-S3-DevKitC N16R8)
 *    and the WCH CH343 / CH9102 chip (the "COM" port of a DevKitC-1 v1.1)
 *  - Silicon Labs CP210x (CP2102 / CP2104): most ESP32-WROOM-32 dev boards
 *  - WCH CH340 / CH341: cheap ESP32 boards
 *
 * 115200 baud, 8N1. The same "A5 op len … chk" messages as Bluetooth; the ESP32 answers with piano
 * notes and status once the app has written to it.
 *
 * DTR and RTS stay OFF: on ESP32 dev boards these lines drive the reset (EN) and BOOT (GPIO0) pins
 * through the "auto-reset" transistors, and GPIO0 is also the firmware's practice button. Opening the
 * port can still reset some boards, so the link waits [BOOT_WAIT_MS] before it reports "connected".
 *
 * Address: "usb:VVVV:PPPP" (hex vendor:product), or "usb:" = the first supported device.
 */
class UsbSerialTransport : Esp32Transport {

    override var listener: LedTransport.Listener? = null
    @Volatile private var connected = false
    override val isConnected: Boolean get() = connected

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "USB-serial").apply { isDaemon = true } }
    private var appContext: Context? = null
    private var manager: UsbManager? = null
    private var connection: UsbDeviceConnection? = null
    private var claimed: List<UsbInterface> = emptyList()
    private var outEp: UsbEndpoint? = null
    private var inEp: UsbEndpoint? = null
    private var reader: Thread? = null
    @Volatile private var closing = false
    private var receiverRegistered = false
    private var wanted: String = PREFIX

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val dev = deviceExtra(intent) ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) io.execute { open(dev) }
                    else listener?.onError("Cần cho phép ứng dụng dùng cổng USB")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = deviceExtra(intent) ?: return
                    if (connected && matches(dev, wanted)) {
                        closeLink()
                        listener?.onDisconnected("Đã rút cáp USB")
                    }
                }
            }
        }
    }

    override fun connect(context: Context, address: String) {
        val ctx = context.applicationContext
        appContext = ctx
        wanted = address
        closing = false
        val m = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        manager = m
        registerReceiver(ctx)
        val dev = supportedDevices(m).firstOrNull { matches(it, address) }
        if (dev == null) {
            main.post { listener?.onError("Không thấy mạch ESP32 trên cáp USB — hãy cắm lại cáp (cáp phải truyền dữ liệu)") }
            return
        }
        if (m.hasPermission(dev)) {
            io.execute { open(dev) }
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
            m.requestPermission(dev, pi) // Android asks "Allow LED Piano to access …?"
        }
    }

    override fun disconnect() {
        val was = connected
        closing = true
        closeLink()
        appContext?.let { unregisterReceiver(it) }
        if (was) main.post { listener?.onDisconnected("Ngắt kết nối") }
    }

    override fun send(data: ByteArray) {
        io.execute {
            val c = connection ?: return@execute
            val ep = outEp ?: return@execute
            if (!connected) return@execute
            var off = 0
            while (off < data.size) {
                val n = minOf(ep.maxPacketSize.coerceAtLeast(16), data.size - off)
                val chunk = data.copyOfRange(off, off + n)
                if (c.bulkTransfer(ep, chunk, n, WRITE_TIMEOUT_MS) < 0) {
                    main.post { listener?.onError("Lỗi gửi dữ liệu qua USB") }
                    return@execute
                }
                off += n
            }
        }
    }

    // ---- open / drivers ----

    private fun open(dev: UsbDevice) {
        val m = manager ?: return
        val c = m.openDevice(dev)
        if (c == null) {
            main.post { listener?.onError("Không mở được cổng USB") }
            return
        }
        val kind = kindOf(dev)
        val ok = try {
            when (kind) {
                Kind.CDC -> setupCdc(dev, c)
                Kind.CP210X -> setupCp210x(dev, c)
                Kind.CH34X -> setupCh34x(dev, c)
                null -> false
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            for (i in claimed) c.releaseInterface(i)
            c.close()
            main.post { listener?.onError("Mạch USB này chưa được hỗ trợ (${hex(dev.vendorId)}:${hex(dev.productId)})") }
            return
        }
        connection = c
        // Opening the port may have restarted the ESP32: give the firmware time to boot.
        Thread.sleep(BOOT_WAIT_MS)
        if (closing) return
        connected = true
        startReader(c)
        main.post { listener?.onConnected() }
    }

    private fun claim(c: UsbDeviceConnection, i: UsbInterface): Boolean {
        if (!c.claimInterface(i, true)) return false
        claimed = claimed + i
        return true
    }

    /** Bulk IN + OUT endpoints of an interface. */
    private fun bulk(i: UsbInterface): Boolean {
        for (k in 0 until i.endpointCount) {
            val e = i.getEndpoint(k)
            if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
        }
        return inEp != null && outEp != null
    }

    private fun setupCdc(dev: UsbDevice, c: UsbDeviceConnection): Boolean {
        claimed = emptyList()
        var comm: UsbInterface? = null
        var data: UsbInterface? = null
        for (k in 0 until dev.interfaceCount) {
            val i = dev.getInterface(k)
            if (i.interfaceClass == UsbConstants.USB_CLASS_COMM && comm == null) comm = i
            if (i.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && data == null) data = i
        }
        val d = data ?: return false
        comm?.let { claim(c, it) }
        if (!claim(c, d) || !bulk(d)) return false
        val index = comm?.id ?: 0
        // SET_LINE_CODING: 115200 baud, 1 stop bit, no parity, 8 data bits.
        val lc = byteArrayOf((BAUD and 0xFF).toByte(), (BAUD shr 8 and 0xFF).toByte(), (BAUD shr 16 and 0xFF).toByte(), 0, 0, 0, 8)
        c.controlTransfer(0x21, 0x20, 0, index, lc, lc.size, CTRL_TIMEOUT_MS)
        // SET_CONTROL_LINE_STATE: Espressif native USB wants DTR on ("terminal open"); it does not
        // reset on DTR alone. Boards with a USB chip (CH343 / CH9102): both lines off (see above).
        val lines = if (dev.vendorId == VID_ESPRESSIF) 0x01 else 0x00
        c.controlTransfer(0x21, 0x22, lines, index, null, 0, CTRL_TIMEOUT_MS)
        return true
    }

    private fun setupCp210x(dev: UsbDevice, c: UsbDeviceConnection): Boolean {
        claimed = emptyList()
        val i = dev.getInterface(0)
        if (!claim(c, i) || !bulk(i)) return false
        val t = 0x41 // vendor, host → interface
        c.controlTransfer(t, 0x00, 0x0001, 0, null, 0, CTRL_TIMEOUT_MS)           // IFC_ENABLE
        c.controlTransfer(t, 0x03, 0x0800, 0, null, 0, CTRL_TIMEOUT_MS)           // SET_LINE_CTL: 8N1
        c.controlTransfer(t, 0x07, 0x0300, 0, null, 0, CTRL_TIMEOUT_MS)           // SET_MHS: DTR = RTS = off
        val b = byteArrayOf((BAUD and 0xFF).toByte(), (BAUD shr 8 and 0xFF).toByte(), (BAUD shr 16 and 0xFF).toByte(), 0)
        c.controlTransfer(t, 0x1E, 0, 0, b, b.size, CTRL_TIMEOUT_MS)              // SET_BAUDRATE
        return true
    }

    private fun setupCh34x(dev: UsbDevice, c: UsbDeviceConnection): Boolean {
        claimed = emptyList()
        val i = dev.getInterface(0)
        if (!claim(c, i) || !bulk(i)) return false
        val out = 0x40 // vendor, host → device
        val ver = ByteArray(2)
        c.controlTransfer(0xC0, 0x5F, 0, 0, ver, 2, CTRL_TIMEOUT_MS)              // read version
        c.controlTransfer(out, 0xA1, 0, 0, null, 0, CTRL_TIMEOUT_MS)              // serial init
        ch34xBaud(c)
        c.controlTransfer(out, 0x9A, 0x2518, 0x00C3, null, 0, CTRL_TIMEOUT_MS)    // line control: 8N1, rx + tx on
        c.controlTransfer(out, 0xA1, 0x501F, 0xD90A, null, 0, CTRL_TIMEOUT_MS)    // second init step
        ch34xBaud(c)
        c.controlTransfer(out, 0xA4, 0xFF, 0, null, 0, CTRL_TIMEOUT_MS)           // modem lines: DTR = RTS = off (active low)
        return true
    }

    /** 115200 baud on CH340/CH341: factor = 0x10000 − 1532620800 / 115200 = 0xCBFF, prescaler 3 (+ 0x80 flag). */
    private fun ch34xBaud(c: UsbDeviceConnection) {
        c.controlTransfer(0x40, 0x9A, 0x1312, 0xCB83, null, 0, CTRL_TIMEOUT_MS)
        c.controlTransfer(0x40, 0x9A, 0x0F2C, 0x00FF, null, 0, CTRL_TIMEOUT_MS)
    }

    private fun startReader(c: UsbDeviceConnection) {
        val ep = inEp ?: return
        reader = Thread({
            val buf = ByteArray(maxOf(64, ep.maxPacketSize))
            while (connected && !closing) {
                val n = c.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS)
                if (n > 0) {
                    val copy = buf.copyOf(n)
                    main.post { listener?.onDataReceived(copy) }
                }
            }
        }, "USB-serial-read").apply { isDaemon = true; start() }
    }

    private fun closeLink() {
        connected = false
        val c = connection
        connection = null
        reader?.interrupt()
        reader = null
        if (c != null) {
            for (i in claimed) try { c.releaseInterface(i) } catch (_: Exception) {}
            c.close()
        }
        claimed = emptyList()
        inEp = null
        outEp = null
    }

    private fun registerReceiver(ctx: Context) {
        if (receiverRegistered) return
        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // The permission result comes from our own PendingIntent, so the receiver need not be exported.
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(permissionReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(permissionReceiver, f)
        receiverRegistered = true
    }

    private fun unregisterReceiver(ctx: Context) {
        if (!receiverRegistered) return
        try { ctx.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        receiverRegistered = false
    }

    enum class Kind { CDC, CP210X, CH34X }

    companion object {
        const val PREFIX = "usb:"
        const val BAUD = 115200
        const val VID_ESPRESSIF = 0x303A
        private const val VID_SILABS = 0x10C4
        private const val VID_WCH = 0x1A86
        private const val ACTION_PERMISSION = "com.ledpiano.app.USB_PERMISSION"
        private const val BOOT_WAIT_MS = 1500L
        private const val CTRL_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 500
        private const val READ_TIMEOUT_MS = 200

        fun hex(v: Int) = "%04X".format(v)

        /** Which driver fits this USB device, or null when it is not a serial chip we know. */
        fun kindOf(dev: UsbDevice): Kind? = when {
            dev.vendorId == VID_SILABS -> Kind.CP210X
            dev.vendorId == VID_WCH && dev.productId in setOf(0x7523, 0x5523, 0x7522) -> Kind.CH34X
            (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA } -> Kind.CDC
            else -> null
        }

        /** USB serial devices plugged in now (ESP32 boards first). */
        fun supportedDevices(m: UsbManager): List<UsbDevice> =
            m.deviceList.values.filter { kindOf(it) != null }
                .sortedBy { if (it.vendorId == VID_ESPRESSIF || it.vendorId == VID_SILABS || it.vendorId == VID_WCH) 0 else 1 }

        fun addressOf(dev: UsbDevice) = PREFIX + hex(dev.vendorId) + ":" + hex(dev.productId)

        /** A readable name: "ESP32-S3 (USB)", "CP2102 (USB)" … */
        fun labelOf(dev: UsbDevice): String {
            val chip = when {
                // 303A:1001 = USB-Serial/JTAG: the "USB" port of an ESP32-S3-DevKitC (also C3 / C6)
                dev.vendorId == VID_ESPRESSIF -> "ESP32-S3 / C3 (cổng USB)"
                // CP2102N: the "COM" port of an ESP32-S3-DevKitC-1 v1.0, and most ESP32-WROOM-32 boards
                dev.vendorId == VID_SILABS -> "CP210x (ESP32-WROOM-32 / cổng COM của S3)"
                // CH343: the "COM" port of an ESP32-S3-DevKitC-1 v1.1; CH9102 on some WROOM-32 boards
                dev.vendorId == VID_WCH && dev.productId in setOf(0x55D3, 0x55D4) -> "CH343 / CH9102 (cổng COM của S3)"
                dev.vendorId == VID_WCH -> "CH340"
                else -> dev.productName ?: "USB serial"
            }
            return "USB · $chip"
        }

        fun matches(dev: UsbDevice, address: String): Boolean {
            val id = address.removePrefix(PREFIX)
            if (id.isBlank()) return true
            val parts = id.split(':')
            val vid = parts.getOrNull(0)?.toIntOrNull(16)
            val pid = parts.getOrNull(1)?.toIntOrNull(16)
            return (vid == null || vid == dev.vendorId) && (pid == null || pid == dev.productId)
        }

        @Suppress("DEPRECATION")
        private fun deviceExtra(intent: Intent): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
