package com.ledpiano.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ledpiano.app.databinding.ItemBleDeviceBinding
import com.ledpiano.app.led.BleScanner

/** List of scanned BLE devices with a connect button on each row. */
class BleDeviceAdapter(
    private val items: MutableList<BleScanner.Device>,
    private val onConnect: (BleScanner.Device) -> Unit,
) : RecyclerView.Adapter<BleDeviceAdapter.Holder>() {

    class Holder(val binding: ItemBleDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemBleDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val device = items[position]
        holder.binding.txtDeviceName.text = device.name
        holder.binding.txtDeviceAddress.text = device.address
        holder.binding.btnConnectDevice.setOnClickListener { onConnect(device) }
    }
}
