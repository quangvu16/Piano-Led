package com.ledpiano.app.ui

import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.ledpiano.app.led.transport.UsbSerialTransport
import com.ledpiano.app.led.transport.AdalightTransport
import com.ledpiano.app.led.transport.SppTransport
import com.ledpiano.app.led.transport.StripWifiTransport
import com.ledpiano.app.led.transport.WifiFinder
import com.ledpiano.app.led.transport.WledTransport
import com.ledpiano.app.model.AppSettings
import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.ledpiano.app.R
import com.ledpiano.app.databinding.DialogBleDevicesBinding
import com.ledpiano.app.led.BleScanner
import com.ledpiano.app.led.LedController

/**
 * Scanning dialog that lists nearby BLE devices, with an ESP32 on a USB cable at the top (like
 * FriendBot's USB + Bluetooth links). By default it connects the
 * LED controller when the user picks one; pass [onPick] to use the chosen
 * device for something else (e.g. a Bluetooth MIDI keyboard). The caller must
 * already hold the Bluetooth permissions (see [BleScanner.requiredPermissions]).
 */
class BleConnectDialog(
    private val context: Context,
    private val scanner: BleScanner,
    private val ledController: LedController,
    private val onPick: ((BleScanner.Device) -> Unit)? = null,
) {
    private var dialog: AlertDialog? = null
    private val devices = mutableListOf<BleScanner.Device>()

    fun show() {
        dismiss()
        val binding = DialogBleDevicesBinding.inflate(LayoutInflater.from(context))
        val adapter = BleDeviceAdapter(devices) { device -> confirmConnect(device) }
        binding.recyclerDevices.layoutManager = LinearLayoutManager(context)
        binding.recyclerDevices.adapter = adapter

        val d = AlertDialog.Builder(context)
            .setView(binding.root)
            .setNegativeButton(R.string.lb_cancel, null)
            .setNeutralButton(R.string.lb_wifi) { _, _ -> askWifiKind() }
            .create()
        d.setOnDismissListener {
            scanner.stop()
            if (dialog === d) dialog = null
        }
        dialog = d
        d.show()

        // ESP32 boards plugged in by USB (only when choosing the LED strip, not a MIDI keyboard).
        if (onPick == null) {
            val usb = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            val adalight = AppSettings(context).ledTransport == "adalight"
            for (dev in UsbSerialTransport.supportedDevices(usb)) {
                // An Arduino (or any board when "Adalight" is chosen in Settings) gets the Adalight protocol.
                val arduino = adalight || dev.vendorId == VID_ARDUINO
                val row = if (arduino) {
                    BleScanner.Device("Arduino (Adalight) · " + UsbSerialTransport.labelOf(dev),
                        AdalightTransport.PREFIX + UsbSerialTransport.addressOf(dev), 0)
                } else {
                    BleScanner.Device(UsbSerialTransport.labelOf(dev), UsbSerialTransport.addressOf(dev), 0)
                }
                devices.add(0, row)
                adapter.notifyItemInserted(0)
            }
            // LED Piano Strip boards and WLED controllers on the same Wi-Fi (broadcast + Bonjour/mDNS).
            Thread({
                val found = WifiFinder.find(context)
                if (found.isNotEmpty()) binding.root.post {
                    for ((name, addr) in found) {
                        if (devices.any { it.address == addr }) continue
                        devices.add(0, BleScanner.Device(name, addr, 0))
                        adapter.notifyItemInserted(0)
                    }
                }
            }, "Strip-discover").apply { isDaemon = true; start() }
        }

        if (!scanner.isBluetoothEnabled()) {
            binding.txtScanStatus.text = context.getString(R.string.lb_turn_on_bluethooth)
            return
        }

        binding.txtScanStatus.text = context.getString(R.string.lb_scanning)
        scanner.start(object : BleScanner.Callback {
            override fun onDeviceFound(device: BleScanner.Device) {
                if (isEsp32Strip(device.name)) {
                    // ESP32-WROOM-32 / ESP32-S3-DevKitC / ESP32-C3 Super Mini running the LedPianoStrip firmware: on top, clearly named.
                    devices.add(0, device.copy(name = device.name + esp32Label(device.name)))
                    adapter.notifyItemInserted(0)
                } else {
                    devices.add(device)
                    adapter.notifyItemInserted(devices.size - 1)
                }
            }

            override fun onScanFinished() {
                binding.txtScanStatus.text =
                    if (devices.isEmpty()) context.getString(R.string.lb_no_device)
                    else context.getString(R.string.lb_found_devices, devices.size)
            }

            override fun onScanFailed(message: String) {
                binding.txtScanStatus.text = message
            }
        })
    }

    private fun confirmConnect(device: BleScanner.Device) {
        AlertDialog.Builder(context)
            .setTitle(R.string.lb_warning)
            .setMessage(
                context.getString(R.string.lb_want_connect_device) + ":\n" +
                    device.name + " (" + device.address + ")"
            )
            .setPositiveButton(R.string.lb_ok) { _, _ ->
                scanner.stop()
                dialog?.dismiss()
                val pick = onPick
                if (device.address.startsWith(UsbSerialTransport.PREFIX)) {
                    ledController.connectUsb(context, device.address, device.name)
                    return@setPositiveButton
                }
                if (device.address.startsWith(AdalightTransport.PREFIX) || device.address.startsWith(StripWifiTransport.PREFIX) ||
                    device.address.startsWith(WledTransport.PREFIX)) {
                    ledController.connect(context, device.address, device.name)
                    return@setPositiveButton
                }
                if (isEsp32Strip(device.name)) {
                    // Talk WS2812B pixels straight away (auto-detect would also find it).
                    AppSettings(context).ledTransport = "ws2812"
                }
                // BanlanX controllers share one Bluetooth service, so pick the protocol from the name.
                banlanxType(device.name)?.let { AppSettings(context).ledTransport = it }
                if (pick != null) pick(device) else ledController.connect(context, device.address, device.name)
            }
            .setNegativeButton(R.string.lb_cancel, null)
            .show()
    }

    /** "SP630E_…" → sp630e, "SP802E_…" → sp802e, else null (BanlanX names start with the model). */
    private fun banlanxType(name: String?): String? {
        val n = name?.uppercase() ?: return null
        return when {
            n.startsWith("SP630E") -> "sp630e"
            n.startsWith("SP802E") -> "sp802e"
            n.startsWith("SP611E") -> "sp611e"
            else -> null
        }
    }

    /** Other ways to reach the strip: Wi-Fi controllers (WLED, Art-Net, E1.31, DDP), the ESP32 over Wi-Fi, an HC-05. */
    private fun askWifiKind() {
        scanner.stop()
        val items = arrayOf(
            context.getString(R.string.lb_wled), context.getString(R.string.lb_artnet),
            context.getString(R.string.lb_e131), context.getString(R.string.lb_ddp),
            context.getString(R.string.lb_strip_wifi), context.getString(R.string.lb_adalight_bt),
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.lb_wifi)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askWledIp()
                    1 -> askArtNetIp()
                    2 -> askIp(R.string.lb_e131, R.string.lb_e131_hint, WledTransport.E131_PREFIX, "192.168.1.50", "E1.31 ")
                    3 -> askIp(R.string.lb_ddp, R.string.lb_ddp_hint, WledTransport.DDP_PREFIX, "192.168.1.50", "DDP ")
                    4 -> askIp(R.string.lb_strip_wifi, R.string.lb_strip_wifi_hint, StripWifiTransport.PREFIX, "192.168.1.60", "LED Piano Strip ")
                    else -> pickHc05()
                }
            }
            .setNegativeButton(R.string.lb_cancel, null)
            .show()
    }

    /** One IP address for [prefix] ("e131:", "ddp:", "strip:"), remembering the last one. */
    private fun askIp(title: Int, hint: Int, prefix: String, example: String, name: String) {
        val prefs = AppSettings(context)
        val input = EditText(context).apply {
            this.hint = example
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            prefs.lastLedAddress?.takeIf { it.startsWith(prefix) }?.let { setText(it.removePrefix(prefix)) }
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val box = FrameLayout(context).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(hint)
            .setView(box)
            .setPositiveButton(R.string.lb_ok) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) ledController.connect(context, prefix + ip, name + ip)
            }
            .setNegativeButton(R.string.lb_cancel, null)
            .show()
    }

    /** Arduino with an HC-05 / HC-06 module (Bluetooth Classic): pick one of the paired modules. */
    private fun pickHc05() {
        val mods = SppTransport.bondedModules(context)
        if (mods.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(R.string.lb_adalight_bt)
                .setMessage(R.string.lb_adalight_none)
                .setPositiveButton(R.string.lb_ok, null)
                .show()
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.lb_adalight_bt)
            .setItems(mods.map { it.first }.toTypedArray()) { _, which ->
                val (name, addr) = mods[which]
                ledController.connect(context, AdalightTransport.PREFIX + addr, "Arduino · $name")
            }
            .setNegativeButton(R.string.lb_cancel, null)
            .show()
    }

    private fun askArtNetIp() {
        val prefs = AppSettings(context)
        val input = EditText(context).apply {
            hint = "192.168.4.1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            prefs.lastLedAddress?.takeIf { it.startsWith(WledTransport.ARTNET_PREFIX) }
                ?.let { setText(it.removePrefix(WledTransport.ARTNET_PREFIX)) }
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val box = FrameLayout(context).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(context)
            .setTitle(R.string.lb_artnet)
            .setMessage(R.string.lb_artnet_hint)
            .setView(box)
            .setPositiveButton(R.string.lb_ok) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    prefs.ledTransport = "sp801e" // Art-Net = BanlanX SP801E (any Art-Net node works the same)
                    ledController.connectArtNet(context, ip)
                }
            }
            .setNegativeButton(R.string.lb_cancel, null)
            .show()
    }

    /** WLED controller on Wi-Fi: the phone sends every LED's colour itself (no Bluetooth). */
    private fun askWledIp() {
        val prefs = AppSettings(context)
        val input = EditText(context).apply {
            hint = "192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            prefs.lastLedAddress?.takeIf { it.startsWith(WledTransport.PREFIX) }
                ?.let { setText(it.removePrefix(WledTransport.PREFIX)) }
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val box = FrameLayout(context).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(context)
            .setTitle(R.string.lb_wled)
            .setMessage(R.string.lb_wled_hint)
            .setView(box)
            .setPositiveButton(R.string.lb_ok) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) ledController.connectWled(context, ip)
            }
            .setNegativeButton(R.string.lb_cancel, null)
            .show()
    }

    companion object {
        /** Arduino SA (genuine Uno / Nano Every / Mega): these run Adalight sketches, not the ESP32 firmware. */
        private const val VID_ARDUINO = 0x2341
        /** Board shown next to the name: the firmware calls itself "LED Piano Strip S3" on an ESP32-S3,
         *  "LED Piano Strip C3" on an ESP32-C3. */
        fun esp32Label(name: String?): String = " · ${boardName(name)} · WS2812B"

        fun boardName(name: String?): String = when {
            isEsp32S3(name) -> "ESP32-S3-DevKitC N16R8"
            isEsp32C3(name) -> "ESP32-C3 Super Mini"
            else -> "ESP32-WROOM-32"
        }

        fun isEsp32C3(name: String?): Boolean = name != null && Regex("\\bC3\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)

        fun isEsp32S3(name: String?): Boolean = name != null && Regex("\\bS3\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)

        /** Name advertised by the LedPianoStrip firmware ("LED Piano Strip", "LED Piano ESP32" …). */
        fun isEsp32Strip(name: String?): Boolean = name != null && name.startsWith("LED Piano", ignoreCase = true)
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
