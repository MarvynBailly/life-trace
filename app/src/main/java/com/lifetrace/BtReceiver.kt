package com.lifetrace

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Fires the moment a bluetooth device connects/disconnects. The connect event for car audio
 * is the "I'm driving" signal; headphones/earbuds = listening. Registered dynamically by the
 * SamplerService so it works on modern Android (these are not manifest-deliverable).
 */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> "connected"
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> "disconnected"
            else -> return
        }
        val device: BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val addr = device?.address ?: return
        val name = try { device.name } catch (e: SecurityException) { null }
        Config.init(context)
        Thread {
            try { Net.postBluetoothEvent(event, addr, name) } catch (e: Exception) {}
        }.start()
    }
}
