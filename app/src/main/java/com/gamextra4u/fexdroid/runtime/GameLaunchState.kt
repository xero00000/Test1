package com.gamextra4u.fexdroid.runtime

import com.gamextra4u.fexdroid.storage.GameInstallation

sealed class GameLaunchState {
    object Idle : GameLaunchState()
    data class Preparing(val game: GameInstallation, val message: String) : GameLaunchState()
    data class Launching(val game: GameInstallation) : GameLaunchState()
    data class Running(val game: GameInstallation, val pid: Int, val startedAt: Long) : GameLaunchState()
    data class Completed(val game: GameInstallation, val exitCode: Int, val durationMillis: Long) : GameLaunchState()
    data class Failed(val game: GameInstallation, val reason: String, val exitCode: Int? = null) : GameLaunchState()
}

data class GameProcess(
    val game: GameInstallation,
    val process: Process,
    val pid: Int,
    val startedAt: Long,
    var state: ProcessState = ProcessState.RUNNING
)

enum class ProcessState {
    RUNNING,
    COMPLETED,
    CRASHED,
    TERMINATED
}

sealed class GameExecutionResult {
    data class Success(val exitCode: Int, val durationMillis: Long) : GameExecutionResult()
    data class Crashed(val exitCode: Int, val errorMessage: String, val durationMillis: Long) : GameExecutionResult()
    data class Error(val message: String) : GameExecutionResult()
}

data class GameLaunchConfig(
    val game: GameInstallation,
    val fexBinary: String,
    val environmentVars: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
    val additionalArgs: List<String> = emptyList()
)
