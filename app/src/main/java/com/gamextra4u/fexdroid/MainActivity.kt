package com.gamextra4u.fexdroid

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.gamextra4u.fexdroid.databinding.ActivityMainBinding
import com.gamextra4u.fexdroid.input.ControllerMonitor
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

        populateSavedCredentials()
        setupInteractions()
        observeSteamState()

        steamLauncher.start()
    }

    private fun initializeManagers() {
        storageManager = StorageManager(applicationContext)
        permissionManager = PermissionManager(applicationContext)
        gameLibraryManager = GameLibraryManager(applicationContext, storageManager)
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
            when (val result = gameLibraryManager.initializeGameLibrary()) {
                is GameLibraryResult.Success -> {
                    binding.statusText.text = getString(R.string.game_library_loading, result.games.size)
                    // You can display game library info here
                }
                is GameLibraryResult.Error -> {
                    binding.statusText.text = getString(R.string.game_library_error, result.message)
                }
            }
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

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    override fun onPause() {
        controllerMonitor.stop()
        super.onPause()
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

    private fun formatDuration(durationMillis: Long): String {
        val safeDuration = max(0L, durationMillis)
        val totalSeconds = safeDuration / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
