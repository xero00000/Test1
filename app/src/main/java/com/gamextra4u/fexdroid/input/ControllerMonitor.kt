package com.gamextra4u.fexdroid.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

class ControllerMonitor(
    context: Context,
    private val onControllersChanged: (List<InputDevice>) -> Unit
) : InputManager.InputDeviceListener {

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    fun start() {
        if (started || inputManager == null) {
            if (inputManager == null) {
                onControllersChanged(emptyList())
            }
            return
        }
        started = true
        inputManager.registerInputDeviceListener(this, handler)
        notifyControllers()
    }

    fun stop() {
        if (!started || inputManager == null) {
            return
        }
        started = false
        inputManager.unregisterInputDeviceListener(this)
    }

    private fun notifyControllers() {
        val devices = inputManager?.let { manager ->
            InputDevice.getDeviceIds()
                .mapNotNull { InputDevice.getDevice(it) }
                .filter { device ->
                    device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                        device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                }
        } ?: emptyList()
        onControllersChanged(devices)
    }

    override fun onInputDeviceAdded(deviceId: Int) = notifyControllers()

    override fun onInputDeviceRemoved(deviceId: Int) = notifyControllers()

    override fun onInputDeviceChanged(deviceId: Int) = notifyControllers()
}
