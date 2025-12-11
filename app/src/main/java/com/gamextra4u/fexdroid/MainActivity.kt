package com.gamextra4u.fexdroid

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.gamextra4u.fexdroid.databinding.ActivityMainBinding
import com.gamextra4u.fexdroid.input.*
import com.gamextra4u.fexdroid.steam.GameFilterType
import com.gamextra4u.fexdroid.steam.GameLibraryStats
import com.gamextra4u.fexdroid.steam.GameManager
import com.gamextra4u.fexdroid.steam.GameSortType
import com.gamextra4u.fexdroid.steam.InstallOperation
import com.gamextra4u.fexdroid.steam.OperationStatus
import com.gamextra4u.fexdroid.steam.SteamGame
import com.gamextra4u.fexdroid.steam.SteamLaunchState
import com.gamextra4u.fexdroid.steam.SteamLauncher
import com.gamextra4u.fexdroid.steam.SteamSessionStore
import com.gamextra4u.fexdroid.storage.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var steamLauncher: SteamLauncher
    private lateinit var controllerMonitor: ControllerMonitor
    private lateinit var sessionStore: SteamSessionStore
    private lateinit var storageManager: StorageManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var gameLibraryManager: GameLibraryManager
    private lateinit var gameManager: GameManager
    
    // Input management
    private lateinit var inputRouter: InputRouter
    private lateinit var inputState: InputState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        // Initialize storage and permission managers
        initializeManagers()

        // Check and request permissions
        checkAndRequestPermissions()

        sessionStore = SteamSessionStore(applicationContext)
        steamLauncher = SteamLauncher(applicationContext, lifecycleScope)
        controllerMonitor = ControllerMonitor(this) { devices ->
            updateControllerStatus(devices)
        }

        // Initialize input router
        initializeInputRouter()

        populateSavedCredentials()
        setupInteractions()
        observeSteamState()

        steamLauncher.start()
    }

    private fun initializeManagers() {
        storageManager = StorageManager(applicationContext)
        permissionManager = PermissionManager(applicationContext)
        gameLibraryManager = GameLibraryManager(applicationContext, storageManager)
        gameManager = GameManager(applicationContext, lifecycleScope)
    }
    
    private fun initializeInputRouter() {
        inputRouter = InputRouter(applicationContext, lifecycleScope)
        
        // Configure input mappings
        inputRouter.configureControllerMappings(
            ControllerMappingConfig(
                buttonMappings = mapOf(
                    KeyEvent.KEYCODE_BUTTON_A to "KEY_Z",
                    KeyEvent.KEYCODE_BUTTON_B to "KEY_X",
                    KeyEvent.KEYCODE_BUTTON_X to "KEY_A",
                    KeyEvent.KEYCODE_BUTTON_Y to "KEY_S",
                    KeyEvent.KEYCODE_BUTTON_L1 to "KEY_Q",
                    KeyEvent.KEYCODE_BUTTON_R1 to "KEY_E",
                    KeyEvent.KEYCODE_BUTTON_START to "KEY_ENTER",
                    KeyEvent.KEYCODE_BUTTON_SELECT to "KEY_ESC",
                    KeyEvent.KEYCODE_BUTTON_THUMBL to "KEY_SPACE",
                    KeyEvent.KEYCODE_BUTTON_THUMBR to "KEY_TAB"
                ),
                analogMappings = mapOf(
                    "LEFT_X" to "MOUSE_X",
                    "LEFT_Y" to "MOUSE_Y",
                    "RIGHT_X" to "KEY_ARROWS",
                    "RIGHT_Y" to "KEY_ARROWS"
                ),
                deadzone = 0.15f
            )
        )
        
        inputRouter.configureTouchMappings(
            TouchMappingConfig(
                mouseMode = true,
                gesturesEnabled = true,
                virtualKeyboardEnabled = false
            )
        )
        
        // Set up input event listeners
        inputRouter.setOnInputDevicesChangedListener { devices ->
            updateInputDevicesStatus(devices)
        }
        
        inputRouter.setOnControllerStateChangedListener { hasControllers ->
            updateControllerState(hasControllers)
        }
        
        inputRouter.setOnInputStateChangedListener { state ->
            inputState = state
            updateInputStateUI(state)
        }
        
        // Attach on-screen controls
        inputRouter.attachOnScreenControls(binding.onScreenControls)
        
        // Start the input router
        inputRouter.start()
    }

    private fun checkAndRequestPermissions() {
        if (permissionManager.hasAllRequiredPermissions()) {
            // All permissions are granted, proceed with storage initialization
            initializeStorage()
        } else {
            // Request permissions
            permissionManager.requestPermissions(this) { result ->
                when (result) {
                    PermissionRequestResult.AllGranted -> {
                        initializeStorage()
                    }
                    PermissionRequestResult.ManageExternalStorageRequired -> {
                        // Show dialog explaining why we need this permission
                        showManageStoragePermissionDialog()
                    }
                    is PermissionRequestResult.PartiallyGranted -> {
                        // Show warning about missing permissions
                        showPartialPermissionWarning()
                    }
                    is PermissionRequestResult.Error -> {
                        showPermissionError(result.message)
                    }
                }
            }
        }
    }

    private fun initializeStorage() {
        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.storage_initializing)
            
            when (val result = storageManager.initializeStorage()) {
                is StorageResult.Success -> {
                    binding.statusText.text = getString(R.string.storage_initialized)
                    initializeGameLibrary()
                }
                is StorageResult.PermissionRequired -> {
                    binding.statusText.text = getString(R.string.permission_required, result.message)
                }
                is StorageResult.Error -> {
                    binding.statusText.text = getString(R.string.storage_error, result.message)
                }
            }
        }
    }

    private fun initializeGameLibrary() {
        lifecycleScope.launch {
            gameManager.initialize()
            observeGameLibrary()
            gameManager.startPeriodicLibraryRefresh()
        }
    }
    
    private fun observeGameLibrary() {
        lifecycleScope.launch {
            gameManager.games.collectLatest { games ->
                updateGameLibraryUI(games)
            }
        }
        
        lifecycleScope.launch {
            gameManager.stats.collectLatest { stats ->
                updateGameLibraryStats(stats)
            }
        }
        
        lifecycleScope.launch {
            gameManager.operations.collectLatest { operations ->
                updateInstallOperationsUI(operations)
            }
        }
    }
    
    private fun updateGameLibraryUI(games: List<SteamGame>) {
        binding.statusText.text = getString(R.string.game_library_loaded, games.size)
    }
    
    private fun updateGameLibraryStats(stats: GameLibraryStats) {
        val statsText = getString(
            R.string.game_library_stats,
            stats.installedGames,
            stats.totalGames,
            formatBytes(stats.totalSize),
            stats.updatesAvailable
        )
        binding.gameLibraryStatsText.text = statsText
    }
    
    private fun updateInstallOperationsUI(operations: List<InstallOperation>) {
        if (operations.isEmpty()) {
            binding.operationsPanel.visibility = View.GONE
            return
        }
        
        binding.operationsPanel.visibility = View.VISIBLE
        val activeOps = operations.filter { 
            it.status == OperationStatus.InProgress || 
            it.status == OperationStatus.Paused 
        }
        
        if (activeOps.isNotEmpty()) {
            val activeOp = activeOps.first()
            binding.operationProgressText.text = getString(
                R.string.operation_progress,
                activeOp.gameName,
                activeOp.operationType.name,
                (activeOp.progress * 100).toInt()
            )
            binding.operationProgressBar.progress = (activeOp.progress * 100).toInt()
            binding.operationStatusText.text = activeOp.message
        }
    }

    private fun showManageStoragePermissionDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Storage Permission Required")
            .setMessage("FEXDroid needs permission to access all files on your device to manage Steam games. This is required for Android 11 and above.")
            .setPositiveButton("Grant Permission") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${packageName}")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        showPermissionError("Unable to open settings: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                showPermissionError("Storage permission is required for the app to function properly")
            }
            .show()
    }

    private fun showPartialPermissionWarning() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Some Permissions Denied")
            .setMessage("Some permissions were denied. The app may not function properly without all required permissions.")
            .setPositiveButton("Retry") { _, _ ->
                checkAndRequestPermissions()
            }
            .setNegativeButton("Continue") { _, _ ->
                initializeStorage()
            }
            .show()
    }

    private fun showPermissionError(message: String) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Permission Error")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val result = permissionManager.handlePermissionResult(permissions, grantResults)
            
            when (result) {
                PermissionRequestResult.AllGranted -> {
                    initializeStorage()
                }
                PermissionRequestResult.ManageExternalStorageRequired -> {
                    showManageStoragePermissionDialog()
                }
                is PermissionRequestResult.PartiallyGranted -> {
                    showPartialPermissionWarning()
                }
                is PermissionRequestResult.Error -> {
                    showPermissionError(result.message)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        controllerMonitor.start()
        inputRouter.start()
        
        // Check if permissions were granted after returning from settings
        if (this::permissionManager.isInitialized) {
            lifecycleScope.launch {
                val result = storageManager.requestPermission()
                if (result is PermissionResult.Granted) {
                    initializeStorage()
                }
            }
        }
    }

    override fun onPause() {
        controllerMonitor.stop()
        inputRouter.stop()
        super.onPause()
    }
    
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Route touch events through the input router
        val handled = inputRouter.processMotionEvent(event)
        return handled || super.dispatchTouchEvent(event)
    }
    
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Route controller/joystick events through the input router
        val handled = inputRouter.processMotionEvent(event)
        return handled || super.dispatchGenericMotionEvent(event)
    }
    
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Route key events through the input router
        val handled = inputRouter.processKeyEvent(event)
        return handled || super.dispatchKeyEvent(event)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun populateSavedCredentials() {
        sessionStore.restore()?.let { session ->
            binding.usernameInput.setText(session.accountName)
            binding.rememberSessionSwitch.isChecked = session.rememberSession
        }
    }

    private fun setupInteractions() {
        binding.saveCredentialsButton.setOnClickListener {
            val username = binding.usernameInput.text?.toString().orEmpty()
            val password = binding.passwordInput.text?.toString()
            val remember = binding.rememberSessionSwitch.isChecked
            steamLauncher.updateCredentials(username, password, remember)
            binding.passwordInput.text?.clear()
        }

        binding.relaunchButton.setOnClickListener {
            steamLauncher.relaunch()
        }

        binding.clearSessionButton.setOnClickListener {
            steamLauncher.clearSession()
            binding.usernameInput.text?.clear()
            binding.passwordInput.text?.clear()
            binding.rememberSessionSwitch.isChecked = false
        }
        
        binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
            inputRouter.setOnScreenControlsEnabled(isChecked)
            if (isChecked) {
                binding.onScreenControls.visibility = View.VISIBLE
            } else {
                binding.onScreenControls.visibility = View.GONE
            }
        }
        
        binding.browseGamesButton.setOnClickListener {
            showGameLibraryDialog()
        }
        
        binding.gameLibraryFilterButton.setOnClickListener {
            showGameFilterDialog()
        }
        
        binding.gameLibrarySortButton.setOnClickListener {
            showGameSortDialog()
        }
    }

    private fun observeSteamState() {
        lifecycleScope.launch {
            steamLauncher.state.collectLatest { state ->
                renderState(state)
            }
        }

        lifecycleScope.launch {
            steamLauncher.logs.collectLatest { logs ->
                updateLogs(logs)
            }
        }
    }

    private fun renderState(state: SteamLaunchState) {
        val statusText = when (state) {
            SteamLaunchState.Idle -> getString(R.string.status_idle)
            is SteamLaunchState.Preparing -> getString(R.string.status_preparing, state.message)
            is SteamLaunchState.Launching -> getString(R.string.status_launching)
            is SteamLaunchState.Running -> {
                val suffix = state.accountName?.takeIf { it.isNotBlank() }?.let { " – $it" } ?: ""
                getString(R.string.status_running, suffix)
            }
            is SteamLaunchState.Completed -> {
                val duration = formatDuration(state.durationMillis)
                getString(R.string.status_completed, duration)
            }
            is SteamLaunchState.Failed -> getString(R.string.status_failed, state.reason)
        }
        binding.statusText.text = statusText
    }

    private fun updateLogs(logs: List<String>) {
        if (logs.isEmpty()) {
            binding.logText.text = getString(R.string.log_empty_placeholder)
        } else {
            binding.logText.text = logs.joinToString(separator = "\n")
        }
        binding.logScroll.post {
            binding.logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateControllerStatus(devices: List<InputDevice>) {
        if (devices.isEmpty()) {
            binding.controllerStatus.setText(R.string.controller_status_none)
            return
        }
        val names = devices.joinToString(separator = ", ") { it.name }
        binding.controllerStatus.text = getString(R.string.controller_status, names)
    }
    
    private fun updateInputDevicesStatus(devices: List<InputDevice>) {
        if (devices.isEmpty()) {
            binding.controllerStatus.setText(R.string.no_controllers)
            return
        }
        val controllerDevices = devices.filter { 
            it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            it.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        }
        
        if (controllerDevices.isEmpty()) {
            binding.controllerStatus.setText(R.string.touch_input_enabled)
            return
        }
        
        val names = controllerDevices.joinToString(separator = ", ") { it.name }
        binding.controllerStatus.text = getString(R.string.controllers_detected, names)
    }
    
    private fun updateControllerState(hasControllers: Boolean) {
        // Update UI based on controller state
        binding.onScreenControlsSwitch.isEnabled = !hasControllers
        if (hasControllers) {
            binding.onScreenControlsSwitch.isChecked = false
            binding.onScreenControls.visibility = View.GONE
        }
    }
    
    private fun updateInputStateUI(state: InputState) {
        // Update UI to reflect current input state
        when (state.primarySource) {
            InputSource.CONTROLLER -> {
                binding.controllerStatus.text = getString(R.string.input_mapping_configured)
            }
            InputSource.TOUCH -> {
                binding.controllerStatus.text = getString(R.string.touch_input_enabled)
            }
            InputSource.KEYBOARD -> {
                binding.controllerStatus.text = getString(R.string.keyboard_input_enabled)
            }
            else -> {
                // Keep existing status
            }
        }
    }

    private fun formatDuration(durationMillis: Long): String {
        val safeDuration = max(0L, durationMillis)
        val totalSeconds = safeDuration / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
    
    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.2f %s", size, units[unitIndex])
    }
    
    private fun showGameLibraryDialog() {
        val games = gameManager.getFilteredGames()
        if (games.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Game Library")
                .setMessage("No games found in your Steam library")
                .setPositiveButton("OK") { _, _ -> }
                .show()
            return
        }
        
        val gameNames = games.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Game Library (${games.size} games)")
            .setItems(gameNames) { _, which ->
                val selectedGame = games[which]
                showGameManagementDialog(selectedGame)
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }
    
    private fun showGameManagementDialog(game: SteamGame) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        
        if (!game.isInstalled) {
            options.add("Install")
            actions.add {
                lifecycleScope.launch {
                    gameManager.installGame(game.appId, game.displayName)
                }
            }
        } else {
            options.add("Play")
            actions.add {
                // Launch the game - this would be integrated with GameLauncher
                AlertDialog.Builder(this)
                    .setTitle("Game Launch")
                    .setMessage("Game launch feature coming soon")
                    .setPositiveButton("OK") { _, _ -> }
                    .show()
            }
            
            options.add("Update")
            actions.add {
                lifecycleScope.launch {
                    gameManager.updateGame(game.appId, game.displayName)
                }
            }
            
            options.add("Uninstall")
            actions.add {
                AlertDialog.Builder(this)
                    .setTitle("Uninstall Game")
                    .setMessage("Are you sure you want to uninstall ${game.displayName}?")
                    .setPositiveButton("Yes") { _, _ ->
                        lifecycleScope.launch {
                            gameManager.uninstallGame(game.appId, game.displayName)
                        }
                    }
                    .setNegativeButton("No") { _, _ -> }
                    .show()
            }
        }
        
        options.add(if (game.isFavorite) "Remove from Favorites" else "Add to Favorites")
        actions.add {
            lifecycleScope.launch {
                gameManager.toggleGameFavorite(game.appId)
            }
        }
        
        options.add("View Details")
        actions.add {
            showGameDetailsDialog(game)
        }
        
        AlertDialog.Builder(this)
            .setTitle(game.displayName)
            .setItems(options.toTypedArray()) { _, which ->
                actions[which]()
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }
    
    private fun showGameDetailsDialog(game: SteamGame) {
        val details = StringBuilder()
        details.append("Name: ${game.displayName}\n")
        details.append("App ID: ${game.appId}\n")
        details.append("Status: ${if (game.isInstalled) "Installed" else "Not Installed"}\n")
        if (game.isInstalled) {
            details.append("Size: ${formatBytes(game.sizeOnDisk)}\n")
        }
        if (game.playtime > 0) {
            details.append("Play Time: ${game.playtime / 60} hours\n")
        }
        if (game.hasCloudSaves) {
            details.append("Cloud Saves: Yes (${formatBytes(game.cloudSaveSize)})\n")
        }
        details.append("Favorite: ${if (game.isFavorite) "Yes" else "No"}\n")
        
        AlertDialog.Builder(this)
            .setTitle("Game Details")
            .setMessage(details.toString())
            .setPositiveButton("OK") { _, _ -> }
            .show()
    }
    
    private fun showGameFilterDialog() {
        val filterOptions = listOf(
            "All Games",
            "Installed",
            "Not Installed",
            "Favorites",
            "Needs Update",
            "Cloud Saves"
        )
        
        val filterTypes = listOf(
            GameFilterType.All,
            GameFilterType.Installed,
            GameFilterType.NotInstalled,
            GameFilterType.Favorites,
            GameFilterType.NeedingUpdate,
            GameFilterType.WithCloudSaves
        )
        
        var selectedFilters = setOf(GameFilterType.All)
        val checkedItems = BooleanArray(filterOptions.size)
        checkedItems[0] = true
        
        AlertDialog.Builder(this)
            .setTitle("Filter Games")
            .setMultiChoiceItems(
                filterOptions.toTypedArray(),
                checkedItems
            ) { _, which, isChecked ->
                checkedItems[which] = isChecked
                selectedFilters = filterTypes.filterIndexed { index, _ -> checkedItems[index] }.toSet()
            }
            .setPositiveButton("Apply") { _, _ ->
                lifecycleScope.launch {
                    if (selectedFilters.isNotEmpty()) {
                        gameManager.setGameFilters(selectedFilters)
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }
    
    private fun showGameSortDialog() {
        val sortOptions = listOf(
            "Name",
            "Install Date",
            "Play Time",
            "Size",
            "Recently Played"
        )
        
        val sortTypes = listOf(
            GameSortType.Name,
            GameSortType.InstallDate,
            GameSortType.PlayTime,
            GameSortType.Size,
            GameSortType.RecentlyPlayed
        )
        
        AlertDialog.Builder(this)
            .setTitle("Sort Games")
            .setItems(sortOptions.toTypedArray()) { _, which ->
                lifecycleScope.launch {
                    gameManager.setGameSortType(sortTypes[which])
                }
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
