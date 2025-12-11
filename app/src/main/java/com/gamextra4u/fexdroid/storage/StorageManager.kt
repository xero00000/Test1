package com.gamextra4u.fexdroid.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class StorageManager(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val gameLibraryDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "GameLibrary")
    private val downloadsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Downloads")
    private val tempDir = File(context.cacheDir, "temp")

    suspend fun initializeStorage(): StorageResult = withContext(dispatcher) {
        try {
            // Create directory structure
            gameLibraryDir.mkdirs()
            downloadsDir.mkdirs()
            tempDir.mkdirs()

            // Set up symlinks for common Steam locations
            setupSteamSymlinks()

            // Verify storage accessibility
            verifyStorageAccess()

            StorageResult.Success(
                gameLibraryPath = gameLibraryDir.absolutePath,
                downloadsPath = downloadsDir.absolutePath,
                tempPath = tempDir.absolutePath,
                availableBytes = getAvailableBytes(),
                totalBytes = getTotalBytes()
            )
        } catch (e: SecurityException) {
            StorageResult.PermissionRequired("Storage access denied: ${e.message}")
        } catch (e: IOException) {
            StorageResult.Error("Failed to initialize storage: ${e.message}")
        } catch (e: Exception) {
            StorageResult.Error("Unexpected storage error: ${e.message}")
        }
    }

    private suspend fun setupSteamSymlinks() {
        // Create symlinks for Steam directories if accessible
        val steamCommonPaths = listOf(
            "/storage/emulated/0/steam",
            "/storage/emulated/0/Android/data/steam",
            "/sdcard/steam",
            "/sdcard/Android/data/steam"
        )

        steamCommonPaths.forEach { steamPath ->
            val steamDir = File(steamPath)
            if (steamDir.exists() && steamDir.canRead()) {
                setupGameLibrarySymlinks(steamDir)
            }
        }
    }

    private suspend fun setupGameLibrarySymlinks(steamDir: File) {
        val steamAppsDir = File(steamDir, "steamapps")
        if (steamAppsDir.exists()) {
            val commonDir = File(steamAppsDir, "common")
            if (commonDir.exists()) {
                val symlinkTarget = File(gameLibraryDir, "steam-common")
                try {
                    createSymlink(commonDir, symlinkTarget)
                } catch (e: Exception) {
                    // Symlink creation might fail on some devices, that's ok
                }
            }

            val workshopDir = File(steamAppsDir, "workshop")
            if (workshopDir.exists()) {
                val symlinkTarget = File(gameLibraryDir, "steam-workshop")
                try {
                    createSymlink(workshopDir, symlinkTarget)
                } catch (e: Exception) {
                    // Symlink creation might fail on some devices, that's ok
                }
            }
        }
    }

    private fun createSymlink(source: File, target: File) {
        if (target.exists()) {
            target.delete()
        }
        // Use command line to create symlink since Java doesn't support it directly
        val process = Runtime.getRuntime().exec(arrayOf("ln", "-s", source.absolutePath, target.absolutePath))
        process.waitFor()
    }

    private suspend fun verifyStorageAccess() {
        val testFile = File(tempDir, "storage_test_${System.currentTimeMillis()}")
        testFile.writeText("test")
        val readBack = testFile.readText()
        testFile.delete()

        if (readBack != "test") {
            throw IOException("Storage access verification failed")
        }
    }

    suspend fun getStorageInfo(): StorageInfo = withContext(dispatcher) {
        val availableBytes = getAvailableBytes()
        val totalBytes = getTotalBytes()
        val usedBytes = totalBytes - availableBytes

        StorageInfo(
            totalBytes = totalBytes,
            availableBytes = availableBytes,
            usedBytes = usedBytes,
            usagePercentage = if (totalBytes > 0) (usedBytes * 100 / totalBytes) else 0,
            gameLibraryPath = gameLibraryDir.absolutePath,
            downloadsPath = downloadsDir.absolutePath
        )
    }

    private fun getAvailableBytes(): Long {
        return try {
            val statFs = android.os.StatFs(gameLibraryDir.absolutePath)
            statFs.availableBytes
        } catch (e: Exception) {
            0L
        }
    }

    private fun getTotalBytes(): Long {
        return try {
            val statFs = android.os.StatFs(gameLibraryDir.absolutePath)
            statFs.totalBytes
        } catch (e: Exception) {
            0L
        }
    }

    suspend fun requestPermission(): PermissionResult = withContext(dispatcher) {
        when {
            hasAllPermissions() -> PermissionResult.Granted
            needsManageExternalStoragePermission() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    PermissionResult.ManageExternalStorageRequired(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                } else {
                    PermissionResult.NeedsLegacyPermissions
                }
            }
            else -> PermissionResult.NeedsLegacyPermissions
        }
    }

    private fun hasAllPermissions(): Boolean {
        return hasStoragePermission() && hasInternetPermission()
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses granular permissions
            true // We request READ_MEDIA_* permissions via manifest
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12 uses MANAGE_EXTERNAL_STORAGE
            Environment.isExternalStorageManager()
        } else {
            // Android 10 and below use legacy storage permissions
            android.os.Environment.checkPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE, 
                android.os.Process.myPid(), 
                android.os.Process.myUid()
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasInternetPermission(): Boolean {
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
                context.checkSelfPermission(android.Manifest.permission.INTERNET)
    }

    private fun needsManageExternalStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasStoragePermission()
    }

    suspend fun cleanupStorage(): CleanupResult = withContext(dispatcher) {
        var cleanedBytes = 0L

        // Clean temp directory
        cleanedBytes += cleanDirectory(tempDir)

        // Clean old downloads
        cleanedBytes += cleanOldFiles(downloadsDir, maxAgeDays = 7)

        // Clean cache files in game library
        cleanedBytes += cleanCacheFiles(gameLibraryDir)

        CleanupResult.Success(cleanedBytes)
    }

    private fun cleanDirectory(dir: File): Long {
        if (!dir.exists()) return 0L

        var cleanedBytes = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                cleanedBytes += cleanDirectory(file)
            } else {
                cleanedBytes += file.length()
                file.delete()
            }
        }
        return cleanedBytes
    }

    private fun cleanOldFiles(dir: File, maxAgeDays: Int): Long {
        if (!dir.exists()) return 0L

        val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        var cleanedBytes = 0L

        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                cleanedBytes += if (file.isDirectory) {
                    cleanDirectory(file)
                } else {
                    file.length()
                }
                file.delete()
            }
        }
        return cleanedBytes
    }

    private fun cleanCacheFiles(dir: File): Long {
        if (!dir.exists()) return 0L

        var cleanedBytes = 0L
        dir.walk().forEach { file ->
            if (file.isFile && (file.name.contains("cache") || 
                               file.name.endsWith(".tmp") || 
                               file.name.endsWith(".log"))) {
                cleanedBytes += file.length()
                file.delete()
            }
        }
        return cleanedBytes
    }

    fun getGameLibraryPath(): String = gameLibraryDir.absolutePath
    fun getDownloadsPath(): String = downloadsDir.absolutePath
    fun getTempPath(): String = tempDir.absolutePath
}

sealed class StorageResult {
    data class Success(
        val gameLibraryPath: String,
        val downloadsPath: String,
        val tempPath: String,
        val availableBytes: Long,
        val totalBytes: Long
    ) : StorageResult()
    data class PermissionRequired(val message: String) : StorageResult()
    data class Error(val message: String) : StorageResult()
}

data class StorageInfo(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val usagePercentage: Int,
    val gameLibraryPath: String,
    val downloadsPath: String
)

sealed class PermissionResult {
    object Granted : PermissionResult()
    data class ManageExternalStorageRequired(
        val action: String,
        val uri: Uri
    ) : PermissionResult()
    object NeedsLegacyPermissions : PermissionResult()
}

sealed class CleanupResult {
    data class Success(val cleanedBytes: Long) : CleanupResult()
    data class Error(val message: String) : CleanupResult()
}