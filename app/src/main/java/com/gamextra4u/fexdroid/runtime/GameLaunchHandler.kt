package com.gamextra4u.fexdroid.runtime

import android.content.Context
import android.util.Log
import com.gamextra4u.fexdroid.storage.GameInstallation
import com.gamextra4u.fexdroid.storage.GameLibraryManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GameLaunchHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gameLibraryManager: GameLibraryManager,
    private val gameProcessManager: GameProcessManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val launchRequestFile = File(context.filesDir, "fexdroid/game_launch_request.txt")
    private val launchResponseFile = File(context.filesDir, "fexdroid/game_launch_response.txt")

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        launchRequestFile.parentFile?.mkdirs()
        monitorLaunchRequests()
    }

    suspend fun handleGameLaunch(gamePath: String): GameExecutionResult = withContext(dispatcher) {
        try {
            appendLog("Handling game launch request: $gamePath")

            val game = resolveGameFromPath(gamePath)
            if (game == null) {
                appendLog("Error: Could not resolve game from path: $gamePath")
                return@withContext GameExecutionResult.Error("Game not found in library: $gamePath")
            }

            appendLog("Resolved game: ${game.name} (AppID: ${game.appId})")

            val result = gameProcessManager.launchGame(game)

            when (result) {
                is GameExecutionResult.Success -> {
                    appendLog("Game ${game.name} completed successfully")
                    writeLaunchResponse("SUCCESS:${game.appId}:${result.exitCode}")
                }
                is GameExecutionResult.Crashed -> {
                    appendLog("Game ${game.name} crashed: ${result.errorMessage}")
                    writeLaunchResponse("CRASHED:${game.appId}:${result.exitCode}:${result.errorMessage}")
                }
                is GameExecutionResult.Error -> {
                    appendLog("Failed to launch ${game.name}: ${result.message}")
                    writeLaunchResponse("ERROR:${game.appId}:${result.message}")
                }
            }

            result

        } catch (e: Exception) {
            appendLog("Error handling game launch: ${e.message}")
            Log.e(TAG, "Game launch handling failed", e)
            writeLaunchResponse("ERROR:unknown:${e.message}")
            GameExecutionResult.Error("Failed to handle game launch: ${e.message}")
        }
    }

    suspend fun handleGameLaunchByAppId(appId: String): GameExecutionResult = withContext(dispatcher) {
        try {
            appendLog("Handling game launch by AppID: $appId")

            val libraryInfo = gameLibraryManager.getGameLibraryInfo()
            val game = libraryInfo.games.find { it.appId == appId }

            if (game == null) {
                appendLog("Error: Game with AppID $appId not found in library")
                return@withContext GameExecutionResult.Error("Game not found: $appId")
            }

            appendLog("Found game: ${game.name}")
            gameProcessManager.launchGame(game)

        } catch (e: Exception) {
            appendLog("Error handling game launch by AppID: ${e.message}")
            Log.e(TAG, "Game launch by AppID failed", e)
            GameExecutionResult.Error("Failed to launch game: ${e.message}")
        }
    }

    private suspend fun resolveGameFromPath(gamePath: String): GameInstallation? = withContext(dispatcher) {
        val libraryInfo = gameLibraryManager.getGameLibraryInfo()

        libraryInfo.games.find { game ->
            game.executablePath.equals(gamePath, ignoreCase = true) ||
            File(game.executablePath).canonicalPath == File(gamePath).canonicalPath
        } ?: run {
            val gameFile = File(gamePath)
            libraryInfo.games.find { game ->
                File(game.installPath).canonicalPath == gameFile.parentFile?.canonicalPath ||
                game.name.equals(gameFile.nameWithoutExtension, ignoreCase = true)
            }
        }
    }

    private fun monitorLaunchRequests() {
        scope.launch(dispatcher) {
            while (true) {
                try {
                    if (launchRequestFile.exists() && launchRequestFile.canRead()) {
                        val request = launchRequestFile.readText().trim()
                        if (request.isNotEmpty()) {
                            appendLog("Received launch request: $request")
                            launchRequestFile.delete()
                            
                            processLaunchRequest(request)
                        }
                    }
                    
                    kotlinx.coroutines.delay(1000)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring launch requests", e)
                    kotlinx.coroutines.delay(5000)
                }
            }
        }
    }

    private suspend fun processLaunchRequest(request: String) {
        val parts = request.split(":", limit = 2)
        val type = parts.getOrNull(0)
        val value = parts.getOrNull(1)

        when (type?.uppercase()) {
            "PATH" -> {
                if (value != null) {
                    handleGameLaunch(value)
                }
            }
            "APPID" -> {
                if (value != null) {
                    handleGameLaunchByAppId(value)
                }
            }
            else -> {
                appendLog("Unknown launch request type: $type")
                writeLaunchResponse("ERROR:unknown:Unknown request type")
            }
        }
    }

    private suspend fun writeLaunchResponse(response: String) = withContext(dispatcher) {
        try {
            launchResponseFile.writeText(response)
            appendLog("Wrote launch response: $response")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write launch response", e)
        }
    }

    fun createLaunchScript(): String {
        return """
            #!/system/bin/sh
            # FEXDroid Game Launch Interceptor
            # This script captures game launch requests from Steam and forwards them to the Android app
            
            GAME_PATH="$1"
            APP_ID="$2"
            REQUEST_FILE="${context.filesDir.absolutePath}/fexdroid/game_launch_request.txt"
            RESPONSE_FILE="${context.filesDir.absolutePath}/fexdroid/game_launch_response.txt"
            
            if [ -z "${'$'}GAME_PATH" ]; then
                echo "Usage: ${'$'}0 <game_path> [app_id]"
                exit 1
            fi
            
            echo "Requesting game launch: ${'$'}GAME_PATH"
            
            if [ -n "${'$'}APP_ID" ]; then
                echo "APPID:${'$'}APP_ID" > "${'$'}REQUEST_FILE"
            else
                echo "PATH:${'$'}GAME_PATH" > "${'$'}REQUEST_FILE"
            fi
            
            # Wait for response
            TIMEOUT=30
            ELAPSED=0
            while [ ${'$'}ELAPSED -lt ${'$'}TIMEOUT ]; do
                if [ -f "${'$'}RESPONSE_FILE" ]; then
                    RESPONSE=$(cat "${'$'}RESPONSE_FILE")
                    rm -f "${'$'}RESPONSE_FILE"
                    echo "Launch response: ${'$'}RESPONSE"
                    
                    case "${'$'}RESPONSE" in
                        SUCCESS:*)
                            exit 0
                            ;;
                        CRASHED:*)
                            exit 1
                            ;;
                        ERROR:*)
                            exit 2
                            ;;
                    esac
                fi
                
                sleep 1
                ELAPSED=${'$'}((ELAPSED + 1))
            done
            
            echo "Timeout waiting for game launch response"
            exit 3
        """.trimIndent()
    }

    private fun appendLog(message: String) {
        val timestamp = formatTimestamp()
        _logs.value = (_logs.value + "$timestamp • $message").takeLast(MAX_LOG_LINES)
    }

    private fun formatTimestamp(): String = synchronized(LOG_TIME_FORMAT) {
        LOG_TIME_FORMAT.format(Date())
    }

    companion object {
        private const val TAG = "GameLaunchHandler"
        private const val MAX_LOG_LINES = 200
        private val LOG_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
