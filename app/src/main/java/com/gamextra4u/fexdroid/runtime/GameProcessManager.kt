package com.gamextra4u.fexdroid.runtime

import android.content.Context
import android.util.Log
import com.gamextra4u.fexdroid.storage.GameInstallation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class GameProcessManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val allowConcurrentGames: Boolean = false,
    private val maxConcurrentGames: Int = 3
) {

    private val fexRuntime = FexRuntime(context, dispatcher)
    private val gameExecutors = ConcurrentHashMap<String, GameExecutor>()
    private val launchMutex = Mutex()

    private val _activeGames = MutableStateFlow<List<GameProcess>>(emptyList())
    val activeGames: StateFlow<List<GameProcess>> = _activeGames.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _executionHistory = MutableStateFlow<List<GameExecutionRecord>>(emptyList())
    val executionHistory: StateFlow<List<GameExecutionRecord>> = _executionHistory.asStateFlow()

    suspend fun launchGame(game: GameInstallation): GameExecutionResult {
        return launchMutex.withLock {
            try {
                if (!allowConcurrentGames && gameExecutors.isNotEmpty()) {
                    val runningGame = gameExecutors.values.firstOrNull()?.getCurrentGame()
                    appendLog("Cannot launch ${game.name}: ${runningGame?.name} is already running")
                    return GameExecutionResult.Error("Another game is already running. Please close it first.")
                }

                if (allowConcurrentGames && gameExecutors.size >= maxConcurrentGames) {
                    appendLog("Cannot launch ${game.name}: Maximum concurrent games reached ($maxConcurrentGames)")
                    return GameExecutionResult.Error("Maximum number of concurrent games reached ($maxConcurrentGames)")
                }

                if (gameExecutors.containsKey(game.appId)) {
                    appendLog("Game ${game.name} is already running")
                    return GameExecutionResult.Error("This game is already running")
                }

                appendLog("Launching game: ${game.name} (AppID: ${game.appId})")
                
                val executor = GameExecutor(
                    context = context,
                    scope = scope,
                    fexRuntime = fexRuntime,
                    dispatcher = dispatcher
                )

                gameExecutors[game.appId] = executor

                scope.launch(dispatcher) {
                    executor.state.collect { state ->
                        updateActiveGamesList()
                        when (state) {
                            is GameLaunchState.Running -> {
                                appendLog("Game ${state.game.name} is now running")
                            }
                            is GameLaunchState.Completed -> {
                                appendLog("Game ${state.game.name} completed successfully")
                                recordExecution(state.game, state.exitCode, state.durationMillis, null)
                                gameExecutors.remove(state.game.appId)
                                updateActiveGamesList()
                            }
                            is GameLaunchState.Failed -> {
                                appendLog("Game ${state.game.name} failed: ${state.reason}")
                                recordExecution(state.game, state.exitCode ?: -1, 0, state.reason)
                                gameExecutors.remove(state.game.appId)
                                updateActiveGamesList()
                            }
                            else -> {}
                        }
                    }
                }

                scope.launch(dispatcher) {
                    executor.logs.collect { gameLogs ->
                        gameLogs.takeLast(5).forEach { log ->
                            appendLog(log)
                        }
                    }
                }

                val result = executor.launchGame(game)

                result

            } catch (e: Exception) {
                appendLog("Error launching game ${game.name}: ${e.message}")
                Log.e(TAG, "Failed to launch game", e)
                GameExecutionResult.Error("Failed to launch game: ${e.message}")
            }
        }
    }

    suspend fun terminateGame(appId: String): Boolean {
        val executor = gameExecutors[appId]
        if (executor != null) {
            appendLog("Terminating game with AppID: $appId")
            executor.terminateCurrentGame()
            gameExecutors.remove(appId)
            updateActiveGamesList()
            return true
        }
        appendLog("No game found with AppID: $appId")
        return false
    }

    suspend fun forceKillGame(appId: String): Boolean {
        val executor = gameExecutors[appId]
        if (executor != null) {
            appendLog("Force killing game with AppID: $appId")
            executor.forceKillCurrentGame()
            gameExecutors.remove(appId)
            updateActiveGamesList()
            return true
        }
        appendLog("No game found with AppID: $appId")
        return false
    }

    suspend fun terminateAllGames() {
        appendLog("Terminating all running games")
        gameExecutors.values.forEach { executor ->
            executor.terminateCurrentGame()
        }
        gameExecutors.clear()
        updateActiveGamesList()
    }

    fun isGameRunning(appId: String): Boolean {
        return gameExecutors.containsKey(appId)
    }

    fun getRunningGames(): List<GameInstallation> {
        return gameExecutors.values.mapNotNull { it.getCurrentGame() }
    }

    fun getRunningGameCount(): Int {
        return gameExecutors.size
    }

    suspend fun cleanupOldLogs(maxAgeDays: Int = 7): Int {
        appendLog("Cleaning up logs older than $maxAgeDays days")
        return fexRuntime.cleanupOldLogs(maxAgeDays)
    }

    private fun updateActiveGamesList() {
        val activeProcesses = gameExecutors.values.mapNotNull { executor ->
            executor.getCurrentGame()?.let { game ->
                val state = executor.state.value
                if (state is GameLaunchState.Running) {
                    GameProcess(
                        game = game,
                        process = Process::class.java.newInstance(),
                        pid = state.pid,
                        startedAt = state.startedAt,
                        state = ProcessState.RUNNING
                    )
                } else null
            }
        }
        _activeGames.value = activeProcesses
    }

    private fun recordExecution(
        game: GameInstallation,
        exitCode: Int,
        durationMillis: Long,
        errorMessage: String?
    ) {
        val record = GameExecutionRecord(
            game = game,
            startTime = System.currentTimeMillis() - durationMillis,
            endTime = System.currentTimeMillis(),
            durationMillis = durationMillis,
            exitCode = exitCode,
            success = exitCode == 0 && errorMessage == null,
            errorMessage = errorMessage
        )
        
        _executionHistory.value = (_executionHistory.value + record).takeLast(MAX_HISTORY_ENTRIES)
    }

    private fun appendLog(message: String) {
        val timestamp = formatTimestamp()
        _logs.value = (_logs.value + "$timestamp • $message").takeLast(MAX_LOG_LINES)
    }

    private fun formatTimestamp(): String = synchronized(LOG_TIME_FORMAT) {
        LOG_TIME_FORMAT.format(Date())
    }

    companion object {
        private const val TAG = "GameProcessManager"
        private const val MAX_LOG_LINES = 300
        private const val MAX_HISTORY_ENTRIES = 100
        private val LOG_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

data class GameExecutionRecord(
    val game: GameInstallation,
    val startTime: Long,
    val endTime: Long,
    val durationMillis: Long,
    val exitCode: Int,
    val success: Boolean,
    val errorMessage: String?
)
