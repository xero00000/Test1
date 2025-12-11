package com.gamextra4u.fexdroid.storage

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

class GameLibraryManager(
    private val context: Context,
    private val storageManager: StorageManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val gameLibraryConfigFile = File(context.filesDir, "game_library.json")
    private val installedGamesDir = File(storageManager.getGameLibraryPath(), "InstalledGames")

    suspend fun initializeGameLibrary(): GameLibraryResult = withContext(dispatcher) {
        try {
            installedGamesDir.mkdirs()
            
            // Load existing game library configuration
            val existingConfig = loadGameLibraryConfig()
            
            // Scan for installed games
            val discoveredGames = scanForInstalledGames()
            
            // Update configuration with discovered games
            val updatedConfig = updateGameLibraryConfig(existingConfig, discoveredGames)
            saveGameLibraryConfig(updatedConfig)

            GameLibraryResult.Success(
                games = updatedConfig.games.values.toList(),
                libraryPath = storageManager.getGameLibraryPath(),
                totalSize = calculateTotalSize(updatedConfig.games),
                discoveredGames = discoveredGames.size
            )
        } catch (e: Exception) {
            GameLibraryResult.Error("Failed to initialize game library: ${e.message}")
        }
    }

    private suspend fun scanForInstalledGames(): List<GameInstallation> {
        val discoveredGames = mutableListOf<GameInstallation>()
        
        // Scan the installed games directory
        installedGamesDir.listFiles()?.forEach { gameDir ->
            if (gameDir.isDirectory) {
                val gameInfo = scanGameDirectory(gameDir)
                if (gameInfo != null) {
                    discoveredGames.add(gameInfo)
                }
            }
        }

        // Also scan Steam common directories if symlinks exist
        val steamCommonPath = File(storageManager.getGameLibraryPath(), "steam-common")
        if (steamCommonPath.exists() && steamCommonPath.isSymlink()) {
            steamCommonPath.listFiles()?.forEach { gameDir ->
                if (gameDir.isDirectory) {
                    val gameInfo = scanGameDirectory(gameDir)
                    if (gameInfo != null) {
                        discoveredGames.add(gameInfo)
                    }
                }
            }
        }

        return discoveredGames
    }

    private fun scanGameDirectory(gameDir: File): GameInstallation? {
        return try {
            // Look for common game files
            val gameExe = findGameExecutable(gameDir)
            val gameIcon = findGameIcon(gameDir)
            val manifestFile = findManifestFile(gameDir)
            
            if (gameExe != null) {
                val gameName = gameDir.name
                val installSize = calculateDirectorySize(gameDir)
                val lastPlayed = getLastModifiedTime(gameDir)
                val appId = extractAppId(gameDir)
                
                GameInstallation(
                    appId = appId,
                    name = gameName,
                    installPath = gameDir.absolutePath,
                    executablePath = gameExe.absolutePath,
                    iconPath = gameIcon?.absolutePath,
                    manifestPath = manifestFile?.absolutePath,
                    sizeBytes = installSize,
                    lastPlayed = lastPlayed,
                    installationSource = detectInstallationSource(gameDir)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findGameExecutable(gameDir: File): File? {
        val commonExecutables = listOf(
            "game",
            "game.exe",
            "Game",
            "Game.exe",
            "${gameDir.name}",
            "${gameDir.name}.exe",
            "bin/${gameDir.name}",
            "bin/${gameDir.name}.exe"
        )
        
        return commonExecutables.firstNotNullOfOrNull { executableName ->
            val executableFile = File(gameDir, executableName)
            if (executableFile.exists() && executableFile.canExecute()) {
                executableFile
            } else null
        }
    }

    private fun findGameIcon(gameDir: File): File? {
        val iconFiles = listOf(
            "game.png",
            "icon.png",
            "library_600x900.jpg",
            "header.jpg"
        )
        
        return iconFiles.firstNotNullOfOrNull { iconName ->
            val iconFile = File(gameDir, iconName)
            if (iconFile.exists() && iconFile.canRead()) {
                iconFile
            } else null
        }
    }

    private fun findManifestFile(gameDir: File): File? {
        val manifestFiles = listOf(
            "appmanifest_${extractAppId(gameDir)}.acf",
            "manifest.json",
            "gameinfo.json"
        )
        
        return manifestFiles.firstNotNullOfOrNull { manifestName ->
            val manifestFile = File(gameDir, manifestName)
            if (manifestFile.exists() && manifestFile.canRead()) {
                manifestFile
            } else null
        }
    }

    private fun extractAppId(gameDir: File): String {
        // Try to extract AppID from manifest files
        val manifestFile = findManifestFile(gameDir)
        if (manifestFile != null) {
            try {
                val content = manifestFile.readText()
                val json = JSONObject(content)
                return json.optString("appid", "unknown")
            } catch (e: Exception) {
                // Try to parse as Steam manifest
                val content = manifestFile.readText()
                val appIdMatch = Regex("\"appid\"\\s*\"(\\d+)\"").find(content)
                if (appIdMatch != null) {
                    return appIdMatch.groupValues[1]
                }
            }
        }
        
        // Fallback: use directory name hash
        return gameDir.name.hashCode().toString()
    }

    private fun detectInstallationSource(gameDir: File): InstallationSource {
        return when {
            gameDir.absolutePath.contains("steam-common") -> InstallationSource.STEAM_LIBRARY
            gameDir.absolutePath.contains(storageManager.getGameLibraryPath()) -> InstallationSource.LOCAL_LIBRARY
            else -> InstallationSource.UNKNOWN
        }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        
        return dir.walk().sumOf { file ->
            if (file.isFile) file.length() else 0L
        }
    }

    private fun getLastModifiedTime(dir: File): Long {
        return dir.lastModified()
    }

    private fun calculateTotalSize(games: Map<String, GameInstallation>): Long {
        return games.values.sumOf { it.sizeBytes }
    }

    private suspend fun loadGameLibraryConfig(): GameLibraryConfig {
        return withContext(dispatcher) {
            if (!gameLibraryConfigFile.exists()) {
                return@withContext GameLibraryConfig()
            }
            
            try {
                val jsonString = gameLibraryConfigFile.readText()
                val json = JSONObject(jsonString)
                
                val games = mutableMapOf<String, GameInstallation>()
                val gamesJson = json.optJSONObject("games")
                if (gamesJson != null) {
                    gamesJson.keys().forEach { key ->
                        val gameJson = gamesJson.getJSONObject(key)
                        games[key] = parseGameInstallation(gameJson)
                    }
                }
                
                GameLibraryConfig(
                    games = games,
                    lastScanTime = json.optLong("lastScanTime", 0L),
                    totalSize = json.optLong("totalSize", 0L)
                )
            } catch (e: Exception) {
                GameLibraryConfig() // Return empty config on parse error
            }
        }
    }

    private suspend fun saveGameLibraryConfig(config: GameLibraryConfig) {
        withContext(dispatcher) {
            val json = JSONObject()
            val gamesJson = JSONObject()
            
            config.games.forEach { (key, game) ->
                gamesJson.put(key, game.toJson())
            }
            
            json.put("games", gamesJson)
            json.put("lastScanTime", System.currentTimeMillis())
            json.put("totalSize", config.totalSize)
            
            gameLibraryConfigFile.writeText(json.toString())
        }
    }

    private fun parseGameInstallation(gameJson: JSONObject): GameInstallation {
        return GameInstallation(
            appId = gameJson.optString("appId"),
            name = gameJson.optString("name"),
            installPath = gameJson.optString("installPath"),
            executablePath = gameJson.optString("executablePath"),
            iconPath = gameJson.optString("iconPath"),
            manifestPath = gameJson.optString("manifestPath"),
            sizeBytes = gameJson.optLong("sizeBytes"),
            lastPlayed = gameJson.optLong("lastPlayed"),
            installationSource = InstallationSource.valueOf(gameJson.optString("installationSource", "UNKNOWN"))
        )
    }

    private fun GameInstallation.toJson(): JSONObject {
        return JSONObject().apply {
            put("appId", appId)
            put("name", name)
            put("installPath", installPath)
            put("executablePath", executablePath)
            put("iconPath", iconPath)
            put("manifestPath", manifestPath)
            put("sizeBytes", sizeBytes)
            put("lastPlayed", lastPlayed)
            put("installationSource", installationSource.name)
        }
    }

    private fun updateGameLibraryConfig(
        existingConfig: GameLibraryConfig, 
        discoveredGames: List<GameInstallation>
    ): GameLibraryConfig {
        val updatedGames = existingConfig.games.toMutableMap()
        
        discoveredGames.forEach { discoveredGame ->
            val key = discoveredGame.appId
            updatedGames[key] = discoveredGame
        }
        
        return GameLibraryConfig(
            games = updatedGames,
            lastScanTime = System.currentTimeMillis(),
            totalSize = updatedGames.values.sumOf { it.sizeBytes }
        )
    }

    suspend fun getGameLibraryInfo(): GameLibraryInfo = withContext(dispatcher) {
        val config = loadGameLibraryConfig()
        val storageInfo = storageManager.getStorageInfo()
        
        GameLibraryInfo(
            totalGames = config.games.size,
            totalSizeBytes = config.totalSize,
            availableSpaceBytes = storageInfo.availableBytes,
            libraryPath = storageManager.getGameLibraryPath(),
            games = config.games.values.toList()
        )
    }

    suspend fun scanForUpdates(): ScanUpdatesResult = withContext(dispatcher) {
        try {
            val discoveredGames = scanForInstalledGames()
            val config = loadGameLibraryConfig()
            val updatedGames = updateGameLibraryConfig(config, discoveredGames)
            
            val newGames = discoveredGames.filter { discovered ->
                !config.games.containsKey(discovered.appId)
            }
            
            val modifiedGames = discoveredGames.filter { discovered ->
                config.games[discovered.appId]?.let { existing ->
                    existing.sizeBytes != discovered.sizeBytes || 
                    existing.lastPlayed != discovered.lastPlayed
                } ?: false
            }
            
            saveGameLibraryConfig(updatedGames)
            
            ScanUpdatesResult.Success(
                newGames = newGames,
                modifiedGames = modifiedGames,
                totalGames = updatedGames.games.size
            )
        } catch (e: Exception) {
            ScanUpdatesResult.Error("Failed to scan for updates: ${e.message}")
        }
    }

    suspend fun cleanupOldFiles(): CleanupResult = withContext(dispatcher) {
        try {
            var cleanedBytes = 0L
            
            // Clean up old log files using storage manager
            val cleanupResult = storageManager.cleanupStorage()
            if (cleanupResult is CleanupResult.Success) {
                cleanedBytes += cleanupResult.cleanedBytes
            }
            
            // Clean up temporary files in game directories
            installedGamesDir.walk().forEach { file ->
                if (file.name.endsWith(".tmp") || file.name.endsWith(".log") || 
                    file.name.contains("cache") && file.isFile) {
                    cleanedBytes += file.length()
                    file.delete()
                }
            }
            
            CleanupResult.Success(cleanedBytes)
        } catch (e: Exception) {
            CleanupResult.Error("Failed to cleanup: ${e.message}")
        }
    }
}

data class GameLibraryConfig(
    val games: Map<String, GameInstallation> = emptyMap(),
    val lastScanTime: Long = 0L,
    val totalSize: Long = 0L
)

data class GameInstallation(
    val appId: String,
    val name: String,
    val installPath: String,
    val executablePath: String,
    val iconPath: String?,
    val manifestPath: String?,
    val sizeBytes: Long,
    val lastPlayed: Long,
    val installationSource: InstallationSource
)

enum class InstallationSource {
    STEAM_LIBRARY, LOCAL_LIBRARY, UNKNOWN
}

data class GameLibraryInfo(
    val totalGames: Int,
    val totalSizeBytes: Long,
    val availableSpaceBytes: Long,
    val libraryPath: String,
    val games: List<GameInstallation>
)

sealed class GameLibraryResult {
    data class Success(
        val games: List<GameInstallation>,
        val libraryPath: String,
        val totalSize: Long,
        val discoveredGames: Int
    ) : GameLibraryResult()
    data class Error(val message: String) : GameLibraryResult()
}

sealed class ScanUpdatesResult {
    data class Success(
        val newGames: List<GameInstallation>,
        val modifiedGames: List<GameInstallation>,
        val totalGames: Int
    ) : ScanUpdatesResult()
    data class Error(val message: String) : ScanUpdatesResult()
}

private fun File.isSymlink(): Boolean {
    return try {
        Files.isSymbolicLink(this.toPath())
    } catch (e: Exception) {
        false
    }
}