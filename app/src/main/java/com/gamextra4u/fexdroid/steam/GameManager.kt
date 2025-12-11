package com.gamextra4u.fexdroid.steam

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GameManager(
    context: Context,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext
    
    private val gameLibrary = SteamGameLibrary(appContext, dispatcher)
    private val installManager = GameInstallManager(appContext, dispatcher)
    
    private val operationMutex = Mutex()
    private val libraryMutex = Mutex()

    // Public state flows
    val games: StateFlow<List<SteamGame>> = gameLibrary.games
    val stats: StateFlow<GameLibraryStats> = gameLibrary.stats
    val operations: StateFlow<List<InstallOperation>> = installManager.operations
    val activeOperation: StateFlow<InstallOperation?> = installManager.activeOperation

    suspend fun initialize() {
        libraryMutex.withLock {
            gameLibrary.initialize()
        }
    }

    suspend fun refreshGameLibrary() {
        libraryMutex.withLock {
            gameLibrary.refreshLibrary()
        }
    }

    suspend fun installGame(
        appId: Int,
        gameName: String,
        onProgress: suspend (InstallResult) -> Unit = {}
    ) {
        operationMutex.withLock {
            installManager.installGame(appId, gameName, onProgress)
        }
    }

    suspend fun uninstallGame(appId: Int, gameName: String) {
        operationMutex.withLock {
            installManager.uninstallGame(appId, gameName)
        }
    }

    suspend fun updateGame(appId: Int, gameName: String) {
        operationMutex.withLock {
            installManager.updateGame(appId, gameName)
        }
    }

    suspend fun cancelGameOperation(appId: Int) {
        operationMutex.withLock {
            installManager.cancelOperation(appId)
        }
    }

    suspend fun pauseGameOperation(appId: Int) {
        operationMutex.withLock {
            installManager.pauseOperation(appId)
        }
    }

    suspend fun resumeGameOperation(appId: Int) {
        operationMutex.withLock {
            installManager.resumeOperation(appId)
        }
    }

    suspend fun toggleGameFavorite(appId: Int) {
        libraryMutex.withLock {
            gameLibrary.toggleFavorite(appId)
        }
    }

    suspend fun setGameFilters(filters: Set<GameFilterType>) {
        libraryMutex.withLock {
            gameLibrary.setFilters(filters)
        }
    }

    suspend fun setGameSortType(sortType: GameSortType) {
        libraryMutex.withLock {
            gameLibrary.setSortType(sortType)
        }
    }

    fun getFilteredGames(): List<SteamGame> = gameLibrary.getFilteredAndSortedGames()

    fun getGameById(appId: Int): SteamGame? = gameLibrary.getGameById(appId)

    fun getGameOperation(appId: Int): InstallOperation? = installManager.getOperation(appId)

    fun getAllOperations(): List<InstallOperation> = installManager.getAllOperations()

    fun getLibraryStats(): GameLibraryStats = gameLibrary.getStats()

    fun startPeriodicLibraryRefresh() {
        scope.launch(dispatcher) {
            while (true) {
                try {
                    kotlinx.coroutines.delay(30000) // Refresh every 30 seconds
                    refreshGameLibrary()
                } catch (e: Exception) {
                    // Ignore errors in periodic refresh
                }
            }
        }
    }

    suspend fun clearCompletedOperations() {
        operationMutex.withLock {
            installManager.clearCompletedOperations()
        }
    }
}
