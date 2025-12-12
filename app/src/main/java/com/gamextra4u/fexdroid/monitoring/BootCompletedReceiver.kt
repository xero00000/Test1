package com.gamextra4u.fexdroid.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gamextra4u.fexdroid.PriceWatchApp

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device boot completed, rescheduling price monitoring")

            try {
                val scheduler = PriceMonitorScheduler(context.applicationContext)
                scheduler.schedulePeriodicMonitoring()
                
                Log.i(TAG, "Price monitoring rescheduled successfully after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule price monitoring after boot", e)
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
