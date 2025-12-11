package com.gamextra4u.fexdroid.steam

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed class GameLibraryResult {
    data class Success(val games: List<SteamGame>) : GameLibraryResult()
    data class Error(val message: String) : GameLibraryResult()
}

class SteamGameLibrary(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext
    private val steamHome = File(appContext.filesDir, "fexdroid/steam-home")
    private val steamLibraryPath = File(steamHome, "steamapps")
    
    private val _games = MutableStateFlow<List<SteamGame>>(emptyList())
    val games: StateFlow<List<SteamGame>> = _games.asStateFlow()
    
    private val _stats = MutableStateFlow<GameLibraryStats>(
        GameLibraryStats(0, 0, 0L, 0, 0)
    )
    val stats: StateFlow<GameLibraryStats> = _stats.asStateFlow()
    
    private val _filters = MutableStateFlow<Set<GameFilterType>>(setOf(GameFilterType.All))
    val filters: StateFlow<Set<GameFilterType>> = _filters.asStateFlow()
    
    private val _sortType = MutableStateFlow<GameSortType>(GameSortType.Name)
    val sortType: StateFlow<GameSortType> = _sortType.asStateFlow()
    
    private val initialized = AtomicBoolean(false)

    suspend fun initialize(): GameLibraryResult = withContext(dispatcher) {
        if (initialized.compareAndSet(false, true)) {
            refreshLibrary()
        }
        GameLibraryResult.Success(_games.value)
    }

    suspend fun refreshLibrary(): GameLibraryResult = withContext(dispatcher) {
        try {
            val gamesList = discoverInstalledGames()
            _games.value = gamesList
            updateStats()
            GameLibraryResult.Success(gamesList)
        } catch (e: Exception) {
            GameLibraryResult.Error(e.message ?: "Failed to refresh library")
        }
    }

    private suspend fun discoverInstalledGames(): List<SteamGame> = withContext(dispatcher) {
        if (!steamLibraryPath.exists()) {
            return@withContext emptyList()
        }

        val games = mutableListOf<SteamGame>()
        
        steamLibraryPath.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith("steamapps")) {
                val manifestFile = File(file, "manifest")
                if (manifestFile.exists()) {
                    parseManifest(manifestFile)?.let { games.add(it) }
                }
            }
        }
        
        // Also check for apps in direct directories
        steamLibraryPath.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.matches(Regex("\\d+"))) {
                val appId = file.name.toIntOrNull() ?: return@forEach
                val gameDir = File(steamLibraryPath, file.name)
                val game = createGameFromDirectory(appId, gameDir)
                if (game !in games) {
                    games.add(game)
                }
            }
        }
        
        games
    }

    private suspend fun parseManifest(manifestFile: File): SteamGame? = withContext(dispatcher) {
        try {
            val content = manifestFile.readText()
            val appIdRegex = "\"appid\"\\s+\"(\\d+)\"".toRegex()
            val nameRegex = "\"name\"\\s+\"([^\"]+)\"".toRegex()
            val stateSizeRegex = "\"StateFlags\"\\s+\"(\\d+)\"".toRegex()
            
            val appId = appIdRegex.find(content)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
            val name = nameRegex.find(content)?.groupValues?.get(1) ?: return@withContext null
            
            val installDir = "steamapps/common/$name"
            val gameDir = File(steamLibraryPath, installDir)
            val size = calculateDirectorySize(gameDir)
            
            SteamGame(
                appId = appId,
                name = name,
                displayName = name,
                contentDir = gameDir.absolutePath,
                installDir = installDir,
                isInstalled = gameDir.exists(),
                sizeOnDisk = size,
                hasCloudSaves = checkCloudSaves(appId)
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun createGameFromDirectory(appId: Int, gameDir: File): SteamGame = withContext(dispatcher) {
        val name = gameDir.name
        val size = calculateDirectorySize(gameDir)
        
        SteamGame(
            appId = appId,
            name = name,
            displayName = name,
            contentDir = gameDir.absolutePath,
            installDir = gameDir.name,
            isInstalled = gameDir.exists() && gameDir.listFiles()?.isNotEmpty() == true,
            sizeOnDisk = size,
            hasCloudSaves = checkCloudSaves(appId)
        )
    }

    private suspend fun calculateDirectorySize(dir: File): Long = withContext(dispatcher) {
        if (!dir.exists()) return@withContext 0L
        
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        size
    }

    private fun checkCloudSaves(appId: Int): Boolean {
        val cloudDir = File(steamHome, "userdata/$appId/remote")
        return cloudDir.exists() && (cloudDir.listFiles()?.isNotEmpty() == true)
    }

    suspend fun toggleFavorite(appId: Int) = withContext(dispatcher) {
        _games.update { games ->
            games.map { game ->
                if (game.appId == appId) {
                    game.copy(isFavorite = !game.isFavorite)
                } else {
                    game
                }
            }
        }
        updateStats()
    }

    suspend fun setFilters(filters: Set<GameFilterType>) = withContext(dispatcher) {
        _filters.value = filters
    }

    suspend fun setSortType(sortType: GameSortType) = withContext(dispatcher) {
        _sortType.value = sortType
    }

    fun getFilteredAndSortedGames(): List<SteamGame> {
        var filtered = _games.value
        
        val activeFilters = _filters.value
        if (!activeFilters.contains(GameFilterType.All)) {
            filtered = filtered.filter { game ->
                when {
                    activeFilters.contains(GameFilterType.Installed) -> game.isInstalled
                    activeFilters.contains(GameFilterType.NotInstalled) -> !game.isInstalled
                    activeFilters.contains(GameFilterType.Favorites) -> game.isFavorite
                    activeFilters.contains(GameFilterType.NeedingUpdate) -> 
                        game.updateState == GameUpdateState.UpdateAvailable
                    activeFilters.contains(GameFilterType.WithCloudSaves) -> game.hasCloudSaves
                    else -> true
                }
            }
        }
        
        return when (_sortType.value) {
            GameSortType.Name -> filtered.sortedBy { it.displayName }
            GameSortType.InstallDate -> filtered.sortedByDescending { it.lastPlayedAt }
            GameSortType.PlayTime -> filtered.sortedByDescending { it.playtime }
            GameSortType.Size -> filtered.sortedByDescending { it.sizeOnDisk }
            GameSortType.RecentlyPlayed -> filtered.sortedByDescending { it.lastPlayedAt }
        }
    }

    fun getGameById(appId: Int): SteamGame? = _games.value.find { it.appId == appId }

    fun getStats(): GameLibraryStats = _stats.value

    private suspend fun updateStats() = withContext(dispatcher) {
        val allGames = _games.value
        val installed = allGames.count { it.isInstalled }
        val totalSize = allGames.sumOf { it.sizeOnDisk }
        val updatesAvailable = allGames.count { it.updateState == GameUpdateState.UpdateAvailable }
        val withCloudSaves = allGames.count { it.hasCloudSaves }
        
        _stats.value = GameLibraryStats(
            totalGames = allGames.size,
            installedGames = installed,
            totalSize = totalSize,
            updatesAvailable = updatesAvailable,
            gamesWithCloudSaves = withCloudSaves
        )
    }
}
