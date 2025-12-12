package com.gamextra4u.fexdroid.runtime

import android.content.Context
import android.util.Log
import com.gamextra4u.fexdroid.storage.GameInstallation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GameExecutor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val fexRuntime: FexRuntime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val _state = MutableStateFlow<GameLaunchState>(GameLaunchState.Idle)
    val state: StateFlow<GameLaunchState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var currentProcess: GameProcess? = null
    private var monitorJob: Job? = null

    suspend fun launchGame(game: GameInstallation): GameExecutionResult = withContext(dispatcher) {
        try {
            _state.value = GameLaunchState.Preparing(game, "Preparing game environment")
            appendLog("Preparing to launch: ${game.name}")

            if (currentProcess != null) {
                appendLog("Warning: A game is already running")
                return@withContext GameExecutionResult.Error("Another game is currently running")
            }

            val runtimeEnv = fexRuntime.prepareRuntime()
            appendLog("FEX runtime initialized")

            val gameExe = File(game.executablePath)
            if (!gameExe.exists()) {
                appendLog("Error: Game executable not found at ${game.executablePath}")
                _state.value = GameLaunchState.Failed(game, "Game executable not found")
                return@withContext GameExecutionResult.Error("Game executable not found: ${game.executablePath}")
            }

            val workingDir = File(game.installPath)
            if (!workingDir.exists()) {
                appendLog("Warning: Game install path does not exist, using default")
            }

            _state.value = GameLaunchState.Preparing(game, "Building launch configuration")
            val launchConfig = buildLaunchConfig(game, runtimeEnv)
            appendLog("Launch configuration prepared")

            _state.value = GameLaunchState.Launching(game)
            appendLog("Launching ${game.name} via FEX emulation")

            val process = startGameProcess(launchConfig, runtimeEnv)
            val pid = getPid(process)
            val startTime = System.currentTimeMillis()

            currentProcess = GameProcess(
                game = game,
                process = process,
                pid = pid,
                startedAt = startTime
            )

            _state.value = GameLaunchState.Running(game, pid, startTime)
            appendLog("Game launched successfully (PID: $pid)")

            monitorGameProcess(currentProcess!!)

            val exitCode = process.waitFor()
            val duration = System.currentTimeMillis() - startTime
            currentProcess = null
            monitorJob?.cancel()

            if (exitCode == 0) {
                _state.value = GameLaunchState.Completed(game, exitCode, duration)
                appendLog("Game completed successfully (exit code: $exitCode)")
                GameExecutionResult.Success(exitCode, duration)
            } else {
                _state.value = GameLaunchState.Failed(game, "Game exited with error", exitCode)
                appendLog("Game exited with error code: $exitCode")
                GameExecutionResult.Crashed(exitCode, "Game crashed or exited abnormally", duration)
            }

        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: IOException) {
            val message = error.message ?: "Failed to start game process"
            appendLog("Error: $message")
            _state.value = GameLaunchState.Failed(game, message)
            GameExecutionResult.Error(message)
        } catch (error: Throwable) {
            val message = error.message ?: "Unexpected game launcher failure"
            appendLog("Error: $message")
            Log.e(TAG, "Game launch failed", error)
            _state.value = GameLaunchState.Failed(game, message)
            GameExecutionResult.Error(message)
        }
    }

    fun terminateCurrentGame() {
        currentProcess?.let { gameProcess ->
            appendLog("Terminating ${gameProcess.game.name} (PID: ${gameProcess.pid})")
            try {
                gameProcess.process.destroy()
                gameProcess.state = ProcessState.TERMINATED
                appendLog("Game process terminated")
            } catch (e: Exception) {
                appendLog("Error terminating game: ${e.message}")
            }
            currentProcess = null
            monitorJob?.cancel()
        }
    }

    fun forceKillCurrentGame() {
        currentProcess?.let { gameProcess ->
            appendLog("Force killing ${gameProcess.game.name} (PID: ${gameProcess.pid})")
            try {
                gameProcess.process.destroyForcibly()
                gameProcess.state = ProcessState.TERMINATED
                appendLog("Game process force killed")
            } catch (e: Exception) {
                appendLog("Error force killing game: ${e.message}")
            }
            currentProcess = null
            monitorJob?.cancel()
        }
    }

    fun isGameRunning(): Boolean = currentProcess != null

    fun getCurrentGame(): GameInstallation? = currentProcess?.game

    private suspend fun startGameProcess(
        config: GameLaunchConfig,
        runtimeEnv: FexRuntimeEnvironment
    ): Process = withContext(dispatcher) {
        val commandList = mutableListOf<String>()
        commandList.add(runtimeEnv.qemuBinary)
        commandList.addAll(config.additionalArgs)
        commandList.add(config.game.executablePath)

        val builder = ProcessBuilder(commandList)
        
        val workingDir = config.workingDirectory?.let { File(it) }
            ?: File(config.game.installPath)
        
        if (workingDir.exists() && workingDir.isDirectory) {
            builder.directory(workingDir)
        }

        builder.redirectErrorStream(true)
        
        val envVars = builder.environment()
        config.environmentVars.forEach { (key, value) ->
            envVars[key] = value
        }

        val logFile = fexRuntime.getGameLogFile(config.game.name, System.currentTimeMillis())
        appendLog("Game output will be logged to: ${logFile.absolutePath}")

        val process = builder.start()

        scope.launch(dispatcher) {
            try {
                logFile.outputStream().bufferedWriter().use { fileWriter ->
                    process.inputStream.bufferedReader().use { reader ->
                        reader.forEachLine { line ->
                            fileWriter.write("${formatTimestamp()} | $line\n")
                            fileWriter.flush()
                            appendLog("[${config.game.name}] $line")
                        }
                    }
                }
            } catch (e: Exception) {
                appendLog("Error reading game output: ${e.message}")
            }
        }

        process
    }

    private fun buildLaunchConfig(
        game: GameInstallation,
        runtimeEnv: FexRuntimeEnvironment
    ): GameLaunchConfig {
        val workingDir = File(game.installPath).absolutePath
        
        val envVars = fexRuntime.createGameLaunchConfig(
            gameBinary = game.executablePath,
            gameName = game.name,
            workingDir = workingDir,
            additionalEnv = mapOf(
                "GAME_NAME" to game.name,
                "GAME_APP_ID" to game.appId
            )
        )

        return GameLaunchConfig(
            game = game,
            fexBinary = runtimeEnv.qemuBinary,
            environmentVars = envVars,
            workingDirectory = workingDir,
            additionalArgs = emptyList()
        )
    }

    private fun monitorGameProcess(gameProcess: GameProcess) {
        monitorJob = scope.launch(dispatcher) {
            try {
                while (gameProcess.process.isAlive) {
                    kotlinx.coroutines.delay(5000)
                    val runtime = System.currentTimeMillis() - gameProcess.startedAt
                    val runtimeSeconds = runtime / 1000
                    if (runtimeSeconds % 60 == 0L) {
                        appendLog("Game ${gameProcess.game.name} running for ${runtimeSeconds / 60} minutes")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error monitoring game process", e)
            }
        }
    }

    private fun getPid(process: Process): Int {
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (e: Exception) {
            -1
        }
    }

    private fun appendLog(message: String) {
        val timestamp = formatTimestamp()
        _logs.value = (_logs.value + "$timestamp • $message").takeLast(MAX_LOG_LINES)
    }

    private fun formatTimestamp(): String = synchronized(LOG_TIME_FORMAT) {
        LOG_TIME_FORMAT.format(Date())
    }

    companion object {
        private const val TAG = "GameExecutor"
        private const val MAX_LOG_LINES = 500
        private val LOG_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
