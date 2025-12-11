# FEXDroid Game Execution Runtime System

## Overview

The FEXDroid runtime system provides infrastructure for executing x86/x86_64 games via FEX emulation from within the Steam Big Picture environment on Android devices.

## Architecture

The runtime system consists of several key components:

### Core Components

1. **FexRuntime** (`runtime/FexRuntime.kt`)
   - Manages the FEX emulation environment
   - Handles qemu-x86_64 binary initialization
   - Configures environment variables for game execution
   - Manages game logs and cleanup

2. **GameExecutor** (`runtime/GameExecutor.kt`)
   - Executes individual game processes
   - Monitors game lifecycle (preparing, launching, running, completed, failed)
   - Captures and logs game output
   - Handles process termination and cleanup
   - Provides real-time state updates via Kotlin Flow

3. **GameProcessManager** (`runtime/GameProcessManager.kt`)
   - Manages multiple game processes
   - Supports both serial and concurrent game execution
   - Tracks active games and execution history
   - Provides centralized logging across all game processes
   - Enforces concurrent game limits

4. **GameLaunchHandler** (`runtime/GameLaunchHandler.kt`)
   - Captures game launch requests from Steam Big Picture
   - Resolves game paths to GameInstallation objects
   - Provides file-based IPC for game launch coordination
   - Generates launch interceptor scripts for Steam integration

### State Models

- **GameLaunchState**: Represents the current state of a game launch
  - Idle, Preparing, Launching, Running, Completed, Failed

- **GameProcess**: Tracks information about a running game process
  - Process handle, PID, start time, current state

- **GameExecutionResult**: Result of a game execution attempt
  - Success, Crashed, Error

- **GameExecutionRecord**: Historical record of game executions

## Features

### Process Lifecycle Management

The runtime system provides comprehensive lifecycle management for game processes:

1. **Preparation Phase**
   - Validates game executable exists
   - Prepares FEX runtime environment
   - Configures working directory and environment variables

2. **Launch Phase**
   - Spawns game process via qemu-x86_64
   - Redirects stdout/stderr to log files
   - Captures PID for tracking

3. **Running Phase**
   - Monitors process health
   - Streams output to logs
   - Reports runtime statistics

4. **Completion Phase**
   - Captures exit code
   - Records execution duration
   - Updates execution history

### Error Handling

Comprehensive error handling includes:

- Missing executable detection
- FEX runtime initialization failures
- Process spawn failures
- Abnormal termination detection
- Crash detection and logging

### Logging

Multi-level logging system:

1. **Game-specific logs**: Each game execution creates a timestamped log file
2. **Component logs**: Each runtime component maintains its own log stream
3. **Centralized logs**: GameProcessManager aggregates all logs for UI display

Log files are stored in: `{app_files}/fexdroid/logs/games/`

### Concurrent Execution

The system supports two modes:

1. **Serial Execution** (default)
   - Only one game can run at a time
   - Attempts to launch a second game are rejected with an error message

2. **Concurrent Execution**
   - Multiple games can run simultaneously
   - Configurable maximum concurrent game limit (default: 3)
   - Each game maintains its own executor and state

## Integration with Steam Big Picture

### Launch Interception

The GameLaunchHandler provides file-based IPC to intercept game launches from Steam:

1. Steam writes a launch request to `game_launch_request.txt`:
   - Format: `PATH:<game_executable_path>` or `APPID:<steam_app_id>`

2. GameLaunchHandler monitors this file and processes requests

3. After launch, the handler writes a response to `game_launch_response.txt`:
   - `SUCCESS:<app_id>:<exit_code>`
   - `CRASHED:<app_id>:<exit_code>:<error_message>`
   - `ERROR:<app_id>:<error_message>`

### Launch Script Generation

The handler can generate a launch interceptor script that Steam can use to route game launches through the Android app instead of executing directly.

## Usage Example

```kotlin
// Initialize the runtime system
val fexRuntime = FexRuntime(context)
val gameProcessManager = GameProcessManager(
    context = context,
    scope = lifecycleScope,
    allowConcurrentGames = false
)

// Launch a game
val game = gameLibrary.getGame("appId")
val result = gameProcessManager.launchGame(game)

when (result) {
    is GameExecutionResult.Success -> {
        println("Game completed successfully")
    }
    is GameExecutionResult.Crashed -> {
        println("Game crashed: ${result.errorMessage}")
    }
    is GameExecutionResult.Error -> {
        println("Failed to launch: ${result.message}")
    }
}

// Monitor active games
gameProcessManager.activeGames.collect { games ->
    println("Active games: ${games.size}")
}

// Terminate a game
gameProcessManager.terminateGame(appId)

// Force kill a game
gameProcessManager.forceKillGame(appId)
```

## Environment Variables

Games are launched with the following environment variables:

- `HOME`: Game working directory
- `LD_LIBRARY_PATH`: Library search path including FEX binaries
- `FEXDROID_ROOT`: FEXDroid installation root
- `STEAM_RUNTIME`: Steam runtime directory
- `DISPLAY`: X11 display (`:0`)
- `XDG_RUNTIME_DIR`: Runtime directory for user-specific files
- `PULSE_SERVER`: PulseAudio server address
- `PULSE_COOKIE`: PulseAudio authentication cookie
- `GAME_NAME`: Name of the game
- `GAME_APP_ID`: Steam App ID of the game

## Log Cleanup

The system provides automatic cleanup of old log files:

```kotlin
// Clean up logs older than 7 days
val deletedCount = gameProcessManager.cleanupOldLogs(maxAgeDays = 7)
```

## Future Enhancements

Potential improvements for the runtime system:

1. **Performance Monitoring**
   - CPU/memory usage tracking
   - Frame rate monitoring
   - Performance profiling

2. **Save State Management**
   - Automatic save state creation
   - Quick save/load functionality

3. **Advanced Process Control**
   - Process priority management
   - CPU affinity control
   - Resource limits

4. **Enhanced Steam Integration**
   - Steam overlay support
   - Achievement tracking
   - Cloud save synchronization

5. **Debugging Support**
   - Attach debugger to game processes
   - Crash dump collection
   - Stack trace capture

## Testing

Key test scenarios:

1. Launch a single game and verify it runs
2. Attempt to launch a second game in serial mode (should fail)
3. Enable concurrent mode and launch multiple games
4. Terminate a running game gracefully
5. Force kill a hung game
6. Verify logs are created correctly
7. Test log cleanup functionality
8. Simulate game crash and verify error handling
9. Test launch request file monitoring
10. Verify environment variable configuration
