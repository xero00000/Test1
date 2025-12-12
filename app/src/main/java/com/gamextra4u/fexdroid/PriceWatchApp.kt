package com.gamextra4u.fexdroid

import android.app.Application
import androidx.work.*
import com.gamextra4u.fexdroid.monitoring.PriceMonitorScheduler
import com.gamextra4u.fexdroid.monitoring.PriceMonitorWorker
import java.util.concurrent.TimeUnit

class PriceWatchApp : Application() {

    lateinit var priceMonitorScheduler: PriceMonitorScheduler
        private set

    override fun onCreate() {
        super.onCreate()

        initializeWorkManager()

        priceMonitorScheduler = PriceMonitorScheduler(applicationContext)
        
        priceMonitorScheduler.schedulePeriodicMonitoring()
        
        enqueueExpeditedKickoff()
    }

    private fun initializeWorkManager() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
        
        WorkManager.initialize(this, config)
    }

    private fun enqueueExpeditedKickoff() {
        val expeditedRequest = OneTimeWorkRequestBuilder<PriceMonitorWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(PriceMonitorScheduler.WORKER_TAG)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                "PriceMonitorKickoff",
                ExistingWorkPolicy.KEEP,
                expeditedRequest
            )
    }

    companion object {
        private const val TAG = "PriceWatchApp"
    }
}
