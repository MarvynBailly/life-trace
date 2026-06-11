package com.lifetrace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the sampler after a reboot so tracking is continuous. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SamplerService.start(context)
        }
    }
}
