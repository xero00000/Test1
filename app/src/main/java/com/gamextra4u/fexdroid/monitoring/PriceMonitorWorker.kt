package com.gamextra4u.fexdroid.monitoring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.gamextra4u.fexdroid.R
import com.gamextra4u.fexdroid.retailer.service.PriceMonitorService
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

class PriceMonitorWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private var wakeLock: PowerManager.WakeLock? = null
    private val priceMonitorService by lazy { PriceMonitorService(context) }
    
    private var priceScraper: PriceScraperInterface? = null
    private var priceRepository: PriceRepositoryInterface? = null

    fun injectDependencies(
        scraper: PriceScraperInterface?,
        repository: PriceRepositoryInterface?
    ) {
        this.priceScraper = scraper
        this.priceRepository = repository
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "PriceMonitorWorker started")

        return try {
            acquireWakeLock()
            setForegroundAsync(createForegroundInfo())

            performMonitoring()

            Log.i(TAG, "PriceMonitorWorker completed successfully")
            Result.success()
        } catch (e: CancellationException) {
            Log.w(TAG, "PriceMonitorWorker cancelled", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "PriceMonitorWorker failed", e)
            Result.retry()
        } finally {
            releaseWakeLock()
        }
    }

    private suspend fun performMonitoring() {
        Log.d(TAG, "Starting price monitoring loop")

        if (priceScraper != null && priceRepository != null) {
            performActualMonitoring()
        } else {
            performStubMonitoring()
        }

        Log.d(TAG, "Price monitoring loop completed")
    }

    private suspend fun performActualMonitoring() {
        Log.d(TAG, "Running actual price monitoring with retailer service")
        
        try {
            val watchedGames = priceRepository?.getWatchedGames() ?: emptyList()
            Log.d(TAG, "Monitoring ${watchedGames.size} games")
            
            if (priceScraper != null && priceRepository != null) {
                Log.d(TAG, "Using injected scraper and repository")
                val priceUpdates = priceScraper?.scrapePrices() ?: emptyList()
                Log.d(TAG, "Scraped ${priceUpdates.size} price updates")
                priceRepository?.savePrices(priceUpdates)
                Log.d(TAG, "Saved price updates to repository")
            } else {
                Log.d(TAG, "Using PriceMonitorService for fetching prices")
                val priceSnapshots = priceMonitorService.fetchPricesForProducts(
                    watchedGames.map { gameId ->
                        com.gamextra4u.fexdroid.retailer.domain.ProductDescriptor(
                            id = gameId,
                            title = "",
                            url = ""
                        )
                    }
                )
                Log.d(TAG, "Fetched ${priceSnapshots.size} price snapshots from retailers")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during actual monitoring", e)
        }
    }

    private suspend fun performStubMonitoring() {
        Log.d(TAG, "Running stub monitoring loop (scraper/repository not injected)")

        val iterationCount = 5
        for (i in 1..iterationCount) {
            if (isStopped) {
                Log.i(TAG, "Worker stopped, exiting monitoring loop")
                break
            }

            Log.d(TAG, "Monitoring iteration $i/$iterationCount")
            
            setProgressAsync(
                workDataOf(
                    PROGRESS_KEY to (i * 100 / iterationCount),
                    STATUS_KEY to "Monitoring iteration $i/$iterationCount"
                )
            )

            delay(2000)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG::WakeLock"
            ).apply {
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Wake lock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock", e)
        }
        wakeLock = null
    }

    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Price Monitoring Active")
            .setContentText("Checking game prices in the background...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Price Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for background price monitoring"
                setShowBadge(false)
            }

            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "PriceMonitorWorker"
        private const val CHANNEL_ID = "price_monitoring_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        
        const val PROGRESS_KEY = "progress"
        const val STATUS_KEY = "status"
    }
}
