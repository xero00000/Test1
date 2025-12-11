package com.gamextra4u.fexdroid.steam

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class InstallOperation(
    val appId: Int,
    val gameName: String,
    val operationType: OperationType,
    val progress: Float = 0f,
    val status: OperationStatus = OperationStatus.Pending,
    val message: String = "",
    val startTimeMs: Long = System.currentTimeMillis(),
    val estimatedTimeRemainingMs: Long = -1L
)

enum class OperationType {
    Install,
    Uninstall,
    Update,
    Repair,
    Move
}

enum class OperationStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
    Cancelled,
    Paused
}

sealed class InstallResult {
    object Success : InstallResult()
    data class Progress(val progress: Float) : InstallResult()
    data class Error(val message: String) : InstallResult()
}

class GameInstallManager(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext
    private val steamHome = File(appContext.filesDir, "fexdroid/steam-home")
    private val steamLibraryPath = File(steamHome, "steamapps")
    
    private val _operations = MutableStateFlow<List<InstallOperation>>(emptyList())
    val operations: StateFlow<List<InstallOperation>> = _operations.asStateFlow()
    
    private val _activeOperation = MutableStateFlow<InstallOperation?>(null)
    val activeOperation: StateFlow<InstallOperation?> = _activeOperation.asStateFlow()
    
    private val cancelledOperations = mutableSetOf<Int>()
    private val pausedOperations = mutableSetOf<Int>()

    suspend fun installGame(
        appId: Int,
        gameName: String,
        onProgress: suspend (InstallResult) -> Unit = {}
    ): InstallResult = withContext(dispatcher) {
        var operation: InstallOperation? = null
        try {
            operation = InstallOperation(
                appId = appId,
                gameName = gameName,
                operationType = OperationType.Install,
                status = OperationStatus.InProgress,
                message = "Preparing installation"
            )
            addOperation(operation)
            setActiveOperation(operation)

            // Simulate download/install progress
            for (i in 0..100 step 5) {
                if (cancelledOperations.contains(appId)) {
                    cancelledOperations.remove(appId)
                    updateOperation(
                        appId,
                        operation.copy(status = OperationStatus.Cancelled, message = "Installation cancelled")
                    )
                    return@withContext InstallResult.Error("Installation cancelled")
                }

                if (pausedOperations.contains(appId)) {
                    updateOperation(
                        appId,
                        operation.copy(status = OperationStatus.Paused, message = "Installation paused")
                    )
                    // Wait until resumed
                    while (pausedOperations.contains(appId)) {
                        Thread.sleep(100)
                    }
                    updateOperation(
                        appId,
                        operation.copy(status = OperationStatus.InProgress, message = "Installation resumed")
                    )
                }

                val progress = i / 100f
                val updated = operation.copy(
                    progress = progress,
                    message = "Installing: $i%",
                    estimatedTimeRemainingMs = estimateTimeRemaining(progress)
                )
                updateOperation(appId, updated)
                onProgress(InstallResult.Progress(progress))
                Thread.sleep(100)
            }

            // Verify installation
            val installDir = File(steamLibraryPath, "common/$gameName")
            if (!installDir.exists()) {
                installDir.mkdirs()
            }

            val completed = operation.copy(
                progress = 1f,
                status = OperationStatus.Completed,
                message = "Installation completed successfully"
            )
            updateOperation(appId, completed)
            setActiveOperation(null)
            
            InstallResult.Success
        } catch (e: Exception) {
            if (operation != null) {
                val error = operation.copy(
                    status = OperationStatus.Failed,
                    message = e.message ?: "Installation failed"
                )
                updateOperation(appId, error)
            }
            setActiveOperation(null)
            
            InstallResult.Error(e.message ?: "Installation failed")
        }
    }

    suspend fun uninstallGame(appId: Int, gameName: String): InstallResult = withContext(dispatcher) {
        var operation: InstallOperation? = null
        try {
            operation = InstallOperation(
                appId = appId,
                gameName = gameName,
                operationType = OperationType.Uninstall,
                status = OperationStatus.InProgress,
                message = "Preparing uninstallation"
            )
            addOperation(operation)
            setActiveOperation(operation)

            val installDir = File(steamLibraryPath, "common/$gameName")
            if (installDir.exists()) {
                installDir.deleteRecursively()
            }

            val completed = operation.copy(
                progress = 1f,
                status = OperationStatus.Completed,
                message = "Uninstallation completed"
            )
            updateOperation(appId, completed)
            setActiveOperation(null)
            
            InstallResult.Success
        } catch (e: Exception) {
            if (operation != null) {
                val error = operation.copy(
                    status = OperationStatus.Failed,
                    message = e.message ?: "Uninstallation failed"
                )
                updateOperation(appId, error)
            }
            setActiveOperation(null)
            
            InstallResult.Error(e.message ?: "Uninstallation failed")
        }
    }

    suspend fun updateGame(appId: Int, gameName: String): InstallResult = withContext(dispatcher) {
        var operation: InstallOperation? = null
        try {
            operation = InstallOperation(
                appId = appId,
                gameName = gameName,
                operationType = OperationType.Update,
                status = OperationStatus.InProgress,
                message = "Checking for updates"
            )
            addOperation(operation)
            setActiveOperation(operation)

            // Simulate update check and download
            for (i in 0..100 step 10) {
                if (cancelledOperations.contains(appId)) {
                    cancelledOperations.remove(appId)
                    updateOperation(
                        appId,
                        operation.copy(status = OperationStatus.Cancelled)
                    )
                    return@withContext InstallResult.Error("Update cancelled")
                }

                val progress = i / 100f
                val message = when {
                    i < 20 -> "Checking for updates"
                    i < 50 -> "Downloading update"
                    i < 80 -> "Verifying files"
                    else -> "Finalizing update"
                }
                val updated = operation.copy(
                    progress = progress,
                    message = message
                )
                updateOperation(appId, updated)
                Thread.sleep(200)
            }

            val completed = operation.copy(
                progress = 1f,
                status = OperationStatus.Completed,
                message = "Game updated successfully"
            )
            updateOperation(appId, completed)
            setActiveOperation(null)
            
            InstallResult.Success
        } catch (e: Exception) {
            if (operation != null) {
                val error = operation.copy(
                    status = OperationStatus.Failed,
                    message = e.message ?: "Update failed"
                )
                updateOperation(appId, error)
            }
            setActiveOperation(null)
            
            InstallResult.Error(e.message ?: "Update failed")
        }
    }

    suspend fun cancelOperation(appId: Int) = withContext(dispatcher) {
        cancelledOperations.add(appId)
    }

    suspend fun pauseOperation(appId: Int) = withContext(dispatcher) {
        pausedOperations.add(appId)
        updateOperation(appId, _operations.value.find { it.appId == appId }?.copy(
            status = OperationStatus.Paused
        ) ?: return@withContext)
    }

    suspend fun resumeOperation(appId: Int) = withContext(dispatcher) {
        pausedOperations.remove(appId)
        updateOperation(appId, _operations.value.find { it.appId == appId }?.copy(
            status = OperationStatus.InProgress
        ) ?: return@withContext)
    }

    fun getOperation(appId: Int): InstallOperation? = 
        _operations.value.find { it.appId == appId }

    fun getAllOperations(): List<InstallOperation> = _operations.value

    private fun addOperation(operation: InstallOperation) {
        _operations.update { operations ->
            (operations + operation).distinctBy { it.appId }
        }
    }

    private fun updateOperation(appId: Int, operation: InstallOperation) {
        _operations.update { operations ->
            operations.map { if (it.appId == appId) operation else it }
        }
    }

    private fun setActiveOperation(operation: InstallOperation?) {
        _activeOperation.value = operation
    }

    private fun estimateTimeRemaining(progress: Float): Long {
        if (progress <= 0f) return -1L
        val elapsedTime = System.currentTimeMillis() - (_activeOperation.value?.startTimeMs ?: System.currentTimeMillis())
        val totalTime = (elapsedTime / progress).toLong()
        return totalTime - elapsedTime
    }

    suspend fun clearCompletedOperations() = withContext(dispatcher) {
        _operations.update { operations ->
            operations.filter { 
                it.status != OperationStatus.Completed && 
                it.status != OperationStatus.Failed &&
                it.status != OperationStatus.Cancelled
            }
        }
    }

    suspend fun clearOperation(appId: Int) = withContext(dispatcher) {
        _operations.update { operations ->
            operations.filter { it.appId != appId }
        }
    }
}
