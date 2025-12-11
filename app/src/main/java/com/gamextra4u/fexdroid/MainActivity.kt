package com.gamextra4u.fexdroid

import android.os.Bundle
import android.view.InputDevice
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.gamextra4u.fexdroid.databinding.ActivityMainBinding
import com.gamextra4u.fexdroid.input.ControllerMonitor
import com.gamextra4u.fexdroid.steam.SteamLaunchState
import com.gamextra4u.fexdroid.steam.SteamLauncher
import com.gamextra4u.fexdroid.steam.SteamSessionStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var steamLauncher: SteamLauncher
    private lateinit var controllerMonitor: ControllerMonitor
    private lateinit var sessionStore: SteamSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

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

    override fun onResume() {
        super.onResume()
        controllerMonitor.start()
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
