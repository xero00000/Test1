package com.gamextra4u.fexdroid.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class FexRuntime(
    val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val installRoot = File(context.filesDir, "fexdroid")
    private val binDir = File(installRoot, "bin")
    private val qemuBinary = File(binDir, "qemu-x86_64")
    private val logDir = File(installRoot, "logs")
    private val gameLogDir = File(logDir, "games")

    init {
        gameLogDir.mkdirs()
    }

    suspend fun prepareRuntime(): FexRuntimeEnvironment = withContext(dispatcher) {
        if (!qemuBinary.exists() || !qemuBinary.canExecute()) {
            throw IOException("FEX runtime not initialized: qemu-x86_64 missing or not executable")
        }

        FexRuntimeEnvironment(
            qemuBinary = qemuBinary.absolutePath,
            binDir = binDir.absolutePath,
            runtimeDir = File(installRoot, "runtime").absolutePath,
            logDir = gameLogDir.absolutePath,
            libraryPath = buildLibraryPath()
        )
    }

    fun createGameLaunchConfig(
        gameBinary: String,
        gameName: String,
        workingDir: String? = null,
        additionalEnv: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val environment = mutableMapOf<String, String>()

        val gameWorkingDir = workingDir ?: File(gameBinary).parent ?: installRoot.absolutePath
        environment["HOME"] = gameWorkingDir
        environment["LD_LIBRARY_PATH"] = buildLibraryPath()
        environment["FEXDROID_ROOT"] = installRoot.absolutePath
        environment["STEAM_RUNTIME"] = File(installRoot, "runtime").absolutePath
        
        environment["DISPLAY"] = ":0"
        environment["XDG_RUNTIME_DIR"] = context.cacheDir.absolutePath
        
        environment["PULSE_SERVER"] = "127.0.0.1"
        environment["PULSE_COOKIE"] = File(context.cacheDir, "pulse-cookie").absolutePath

        environment.putAll(additionalEnv)

        return environment
    }

    fun getGameLogFile(gameName: String, timestamp: Long): File {
        val sanitizedName = gameName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(gameLogDir, "${sanitizedName}_${timestamp}.log")
    }

    private fun buildLibraryPath(): String {
        val libPaths = mutableListOf<String>()
        
        libPaths.add(binDir.absolutePath)
        
        val runtimeLib = File(installRoot, "runtime/lib")
        if (runtimeLib.exists()) {
            libPaths.add(runtimeLib.absolutePath)
        }
        
        val systemLibPaths = listOf(
            "/system/lib64",
            "/vendor/lib64",
            "/system/lib",
            "/vendor/lib"
        )
        
        libPaths.addAll(systemLibPaths.filter { File(it).exists() })
        
        return libPaths.joinToString(":")
    }

    suspend fun cleanupOldLogs(maxAgeDays: Int = 7): Int = withContext(dispatcher) {
        val maxAgeMillis = maxAgeDays * 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        var deletedCount = 0

        gameLogDir.listFiles()?.forEach { logFile ->
            if (logFile.isFile && (now - logFile.lastModified()) > maxAgeMillis) {
                if (logFile.delete()) {
                    deletedCount++
                }
            }
        }

        deletedCount
    }
}

data class FexRuntimeEnvironment(
    val qemuBinary: String,
    val binDir: String,
    val runtimeDir: String,
    val logDir: String,
    val libraryPath: String
)
