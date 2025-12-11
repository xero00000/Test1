package com.gamextra4u.fexdroid.storage

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class StorageCleanupManager(
    private val context: Context,
    private val storageManager: StorageManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun performMaintenance(): CleanupResult = withContext(dispatcher) {
        try {
            var totalCleanedBytes = 0L
            
            // Clean temporary files
            val tempCleanup = storageManager.cleanupStorage()
            if (tempCleanup is CleanupResult.Success) {
                totalCleanedBytes += tempCleanup.cleanedBytes
            }
            
            // Clean old log files
            totalCleanedBytes += cleanOldLogs()
            
            // Clean cache directories
            totalCleanedBytes += cleanCacheDirectories()
            
            // Clean old download files
            totalCleanedBytes += cleanOldDownloads()
            
            // Clean Steam temporary files
            totalCleanedBytes += cleanSteamTempFiles()
            
            CleanupResult.Success(totalCleanedBytes)
        } catch (e: Exception) {
            CleanupResult.Error("Cleanup failed: ${e.message}")
        }
    }

    private suspend fun cleanOldLogs(): Long = withContext(dispatcher) {
        val logDirectories = listOf(
            File(storageManager.getGameLibraryPath(), "logs"),
            File(storageManager.getGameLibraryPath(), "steam-logs"),
            File(context.cacheDir, "logs")
        )
        
        var cleanedBytes = 0L
        val cutoffDate = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -30) // Keep logs for 30 days
        }.time

        logDirectories.forEach { logDir ->
            if (logDir.exists()) {
                logDir.walk().forEach { file ->
                    if (file.isFile && file.lastModified() < cutoffDate.time) {
                        cleanedBytes += file.length()
                        file.delete()
                    }
                }
            }
        }
        
        cleanedBytes
    }

    private suspend fun cleanCacheDirectories(): Long = withContext(dispatcher) {
        val cacheDirs = listOf(
            File(storageManager.getGameLibraryPath(), "cache"),
            File(storageManager.getGameLibraryPath(), "temp"),
            File(context.cacheDir, "fexdroid")
        )
        
        var cleanedBytes = 0L
        
        cacheDirs.forEach { cacheDir ->
            if (cacheDir.exists()) {
                cleanedBytes += cleanDirectory(cacheDir, maxSizeMB = 100) // Max 100MB per cache dir
            }
        }
        
        cleanedBytes
    }

    private suspend fun cleanOldDownloads(): Long = withContext(dispatcher) {
        val downloadsDir = File(storageManager.getDownloadsPath())
        if (!downloadsDir.exists()) return@withContext 0L
        
        var cleanedBytes = 0L
        val cutoffDate = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -7) // Keep downloads for 7 days
        }.time

        downloadsDir.walk().forEach { file ->
            if (file.isFile && file.lastModified() < cutoffDate.time) {
                cleanedBytes += file.length()
                file.delete()
            }
        }
        
        cleanedBytes
    }

    private suspend fun cleanSteamTempFiles(): Long = withContext(dispatcher) {
        val steamCommonPath = File(storageManager.getGameLibraryPath(), "steam-common")
        if (!steamCommonPath.exists()) return@withContext 0L
        
        var cleanedBytes = 0L
        
        steamCommonPath.walk().forEach { gameDir ->
            if (gameDir.isDirectory) {
                // Clean Steam crash dumps
                val crashDumpDir = File(gameDir, "CrashReportClient")
                if (crashDumpDir.exists()) {
                    cleanedBytes += cleanDirectory(crashDumpDir)
                }
                
                // Clean old update files
                val steamAppsDir = File(gameDir, "steamapps")
                if (steamAppsDir.exists()) {
                    steamAppsDir.walk().forEach { file ->
                        if (file.name.startsWith("appmanifest_") && file.name.endsWith(".tmp")) {
                            cleanedBytes += file.length()
                            file.delete()
                        }
                    }
                }
                
                // Clean old log files
                gameDir.walk().forEach { file ->
                    if (file.isFile && (file.name.contains("log") || file.name.contains("crash"))) {
                        cleanedBytes += file.length()
                        file.delete()
                    }
                }
            }
        }
        
        cleanedBytes
    }

    private fun cleanDirectory(dir: File, maxSizeMB: Int = Int.MAX_VALUE): Long {
        if (!dir.exists()) return 0L
        
        var totalSize = 0L
        val maxBytes = maxSizeMB * 1024 * 1024L
        
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return 0L
        
        files.forEach { file ->
            if (totalSize < maxBytes) {
                if (file.isDirectory) {
                    totalSize += cleanDirectory(file, maxSizeMB)
                } else {
                    totalSize += file.length()
                }
            } else {
                totalSize += if (file.isDirectory) {
                    cleanDirectory(file)
                } else {
                    file.length()
                }
                file.delete()
            }
        }
        
        return totalSize
    }

    suspend fun getCleanupRecommendations(): List<CleanupRecommendation> = withContext(dispatcher) {
        val recommendations = mutableListOf<CleanupRecommendation>()
        
        // Check temp directory size
        val tempDir = File(storageManager.getTempPath())
        if (tempDir.exists()) {
            val tempSize = calculateDirectorySize(tempDir)
            if (tempSize > 10 * 1024 * 1024) { // More than 10MB
                recommendations.add(
                    CleanupRecommendation(
                        type = CleanupType.TEMP_FILES,
                        description = "Temporary files are using ${formatBytes(tempSize)}",
                        potentialSavings = tempSize,
                        priority = CleanupPriority.MEDIUM
                    )
                )
            }
        }
        
        // Check old log files
        val logDirs = listOf(
            File(storageManager.getGameLibraryPath(), "logs"),
            File(context.cacheDir, "logs")
        )
        
        var totalLogSize = 0L
        logDirs.forEach { logDir ->
            if (logDir.exists()) {
                totalLogSize += calculateOldFileSize(logDir, 7) // Files older than 7 days
            }
        }
        
        if (totalLogSize > 5 * 1024 * 1024) { // More than 5MB
            recommendations.add(
                CleanupRecommendation(
                    type = CleanupType.LOG_FILES,
                    description = "Old log files are using ${formatBytes(totalLogSize)}",
                    potentialSavings = totalLogSize,
                    priority = CleanupPriority.LOW
                )
            )
        }
        
        // Check cache files
        val cacheDirs = listOf(
            File(storageManager.getGameLibraryPath(), "cache")
        )
        
        var totalCacheSize = 0L
        cacheDirs.forEach { cacheDir ->
            if (cacheDir.exists()) {
                totalCacheSize += calculateDirectorySize(cacheDir)
            }
        }
        
        if (totalCacheSize > 50 * 1024 * 1024) { // More than 50MB
            recommendations.add(
                CleanupRecommendation(
                    type = CleanupType.CACHE_FILES,
                    description = "Cache files are using ${formatBytes(totalCacheSize)}",
                    potentialSavings = totalCacheSize,
                    priority = CleanupPriority.MEDIUM
                )
            )
        }
        
        recommendations
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walk().sumOf { file ->
            if (file.isFile) file.length() else 0L
        }
    }

    private fun calculateOldFileSize(dir: File, maxAgeDays: Int): Long {
        if (!dir.exists()) return 0L
        
        val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        return dir.walk().sumOf { file ->
            if (file.isFile && file.lastModified() < cutoffTime) {
                file.length()
            } else {
                0L
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        
        return String.format("%.1f %s", size, units[unitIndex])
    }

    suspend fun scheduleAutomaticCleanup(): Boolean = withContext(dispatcher) {
        try {
            // This would ideally integrate with WorkManager for periodic cleanup
            // For now, we'll just ensure cleanup directories exist
            val cleanupLog = File(storageManager.getGameLibraryPath(), "logs")
            cleanupLog.mkdirs()
            
            val lastCleanupFile = File(context.filesDir, "last_cleanup.txt")
            val currentTime = System.currentTimeMillis()
            val lastCleanup = lastCleanupFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
            
            // If it's been more than 24 hours since last cleanup
            if (currentTime - lastCleanup > 24 * 60 * 60 * 1000L) {
                performMaintenance()
                lastCleanupFile.writeText(currentTime.toString())
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}

data class CleanupRecommendation(
    val type: CleanupType,
    val description: String,
    val potentialSavings: Long,
    val priority: CleanupPriority
)

enum class CleanupType {
    TEMP_FILES, LOG_FILES, CACHE_FILES, STEAM_FILES, OLD_DOWNLOADS
}

enum class CleanupPriority {
    LOW, MEDIUM, HIGH, CRITICAL
}