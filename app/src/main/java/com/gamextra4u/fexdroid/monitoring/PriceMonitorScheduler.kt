package com.gamextra4u.fexdroid.monitoring

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class PriceMonitorScheduler(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun schedulePeriodicMonitoring() {
        val constraints = buildConstraints()
        
        val periodicRequest = PeriodicWorkRequestBuilder<PriceMonitorWorker>(
            repeatInterval = MONITORING_INTERVAL_HOURS,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = FLEX_INTERVAL_MINUTES,
            flexTimeIntervalUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_MINUTES,
                TimeUnit.MINUTES
            )
            .addTag(WORKER_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }

    fun pauseMonitoring() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    fun resumeMonitoring() {
        schedulePeriodicMonitoring()
    }

    fun isMonitoringScheduled(): Boolean {
        val workInfos = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
        return workInfos.any { 
            it.state == WorkInfo.State.RUNNING || 
            it.state == WorkInfo.State.ENQUEUED 
        }
    }

    private fun buildConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .setRequiresStorageNotLow(false)
            .build()
    }

    companion object {
        const val WORKER_TAG = "PriceMonitorWorker"
        const val UNIQUE_WORK_NAME = "PeriodicPriceMonitoring"
        
        private const val MONITORING_INTERVAL_HOURS = 1L
        private const val FLEX_INTERVAL_MINUTES = 15L
        private const val BACKOFF_DELAY_MINUTES = 5L
    }
}
