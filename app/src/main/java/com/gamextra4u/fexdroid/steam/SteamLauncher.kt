package com.gamextra4u.fexdroid.steam

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

sealed class SteamLaunchState {
    object Idle : SteamLaunchState()
    data class Preparing(val message: String) : SteamLaunchState()
    data class Launching(val accountName: String?) : SteamLaunchState()
    data class Running(val accountName: String?, val startedAt: Long) : SteamLaunchState()
    data class Completed(val durationMillis: Long) : SteamLaunchState()
    data class Failed(val reason: String) : SteamLaunchState()
}

class SteamLauncher(
    context: Context,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val appContext = context.applicationContext
    private val installer = SteamEnvironmentInstaller(appContext, dispatcher)
    private val sessionStore = SteamSessionStore(appContext)

    private val _state = MutableStateFlow<SteamLaunchState>(SteamLaunchState.Idle)
    val state: StateFlow<SteamLaunchState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val launchMutex = Mutex()
    private var steamProcess: Process? = null
    private val started = AtomicBoolean(false)

    fun start() {
        if (started.compareAndSet(false, true)) {
            relaunch()
        }
    }

    fun relaunch() {
        scope.launch {
            launchMutex.withLock {
                stopProcessLocked()
                runLaunchLocked()
            }
        }
    }

    fun updateCredentials(accountName: String, password: String?, remember: Boolean) {
        if (accountName.isBlank()) {
            appendLog("Steam account name is required before saving credentials.")
            return
        }
        sessionStore.persist(accountName, remember, password)
        val rememberMessage = if (remember) "(session will be remembered)" else ""
        appendLog("Saved Steam credentials for ${accountName.trim()} $rememberMessage".trim())
    }

    fun clearSession() {
        scope.launch {
            launchMutex.withLock {
                sessionStore.clear()
                stopProcessLocked()
                _state.value = SteamLaunchState.Idle
                appendLog("Cleared stored Steam session data.")
            }
        }
    }

    private suspend fun runLaunchLocked() {
        try {
            _state.value = SteamLaunchState.Preparing("Preparing Steam environment")
            val environment = installer.prepareEnvironment { progress ->
                _state.value = SteamLaunchState.Preparing(progress)
                appendLog(progress)
            }
            val session = sessionStore.restore()
            _state.value = SteamLaunchState.Launching(session?.accountName)
            appendLog("Launching Steam Big Picture UI")

            val process = startProcess(environment, session)
            steamProcess = process
            val startTime = System.currentTimeMillis()
            _state.value = SteamLaunchState.Running(session?.accountName, startTime)
            appendLog("Steam Big Picture is running.")

            scope.launch(dispatcher) {
                val exitCode = process.waitFor()
                steamProcess = null
                if (exitCode == 0) {
                    sessionStore.markLaunched()
                    val duration = System.currentTimeMillis() - startTime
                    _state.value = SteamLaunchState.Completed(duration)
                    appendLog("Steam Big Picture session completed successfully.")
                } else {
                    val message = "Steam exited with code $exitCode"
                    _state.value = SteamLaunchState.Failed(message)
                    appendLog(message)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            val message = error.message ?: "Unable to start Steam"
            _state.value = SteamLaunchState.Failed(message)
            appendLog(message)
        } catch (error: Throwable) {
            val message = error.message ?: "Unexpected Steam launcher failure"
            _state.value = SteamLaunchState.Failed(message)
            appendLog(message)
        }
    }

    private suspend fun startProcess(environment: SteamEnvironment, session: SteamSession?): Process =
        withContext(dispatcher) {
            val launchScript = environment.launchScript
            if (!launchScript.exists()) {
                throw IOException("Steam launch script missing")
            }
            val builder = ProcessBuilder("/system/bin/sh", launchScript.absolutePath)
            builder.directory(environment.binariesDir)
            builder.redirectErrorStream(true)
            val envVars = builder.environment()
            envVars["STEAM_HOME"] = environment.steamHome.absolutePath
            envVars["STEAM_RUNTIME"] = environment.runtimeDir.absolutePath
            envVars["FEXDROID_HOME"] = environment.installRoot.absolutePath
            envVars["STEAM_BIGPICTURE"] = "1"
            envVars["STEAM_ENABLE_CONTROLLER_SUPPORT"] = "1"
            session?.let {
                envVars["STEAM_ACCOUNT_NAME"] = it.accountName
                envVars["STEAM_REMEMBER_SESSION"] = it.rememberSession.toString()
                it.encodedToken?.let { token ->
                    envVars["STEAM_ENCRYPTED_TOKEN"] = token
                }
            }
            val process = builder.start()
            scope.launch(dispatcher) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { appendLog("Steam> $it") }
                }
            }
            process
        }

    private fun stopProcessLocked() {
        steamProcess?.let {
            appendLog("Stopping previously running Steam session")
            it.destroy()
            steamProcess = null
        }
    }

    private fun appendLog(message: String) {
        val timestamp = formatTimestamp()
        _logs.update { logs ->
            (logs + "$timestamp • $message").takeLast(MAX_LOG_LINES)
        }
    }

    private fun formatTimestamp(): String = synchronized(LOG_TIME_FORMAT) {
        LOG_TIME_FORMAT.format(Date())
    }

    companion object {
        private const val MAX_LOG_LINES = 200
        private val LOG_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
