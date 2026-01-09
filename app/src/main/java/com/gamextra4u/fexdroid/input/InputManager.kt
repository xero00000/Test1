package com.gamextra4u.fexdroid.input

import android.content.Context
import android.hardware.input.InputManager as AndroidInputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central input management coordinator for FEXDroid
 * Handles controller detection, touch events, and input routing to emulated games
 */
class InputManager(
    private val context: Context,
    private val onInputDevicesChanged: (List<InputDevice>) -> Unit = {},
    private val onControllerStateChanged: (Boolean) -> Unit = {}
) : AndroidInputManager.InputDeviceListener {

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as AndroidInputManager
    private val handler = Handler(Looper.getMainLooper())
    
    private val controllerMonitor = ControllerMonitor(context) { devices ->
        onInputDevicesChanged(devices)
        onControllerStateChanged(devices.isNotEmpty())
    }
    
    private val controllerMapper = ControllerInputMapper()
    private val touchMapper = TouchInputMapper(context)
    
    private val _connectedDevices = MutableStateFlow<List<InputDevice>>(emptyList())
    val connectedDevices: StateFlow<List<InputDevice>> = _connectedDevices.asStateFlow()
    
    private val _hasControllers = MutableStateFlow(false)
    val hasControllers: StateFlow<Boolean> = _hasControllers.asStateFlow()
    
    private val _enableOnScreenControls = MutableStateFlow(false)
    val enableOnScreenControls: StateFlow<Boolean> = _enableOnScreenControls.asStateFlow()
    
    private var isStarted = false
    
    // Store callbacks for external access
    private var deviceCallback: ((List<InputDevice>) -> Unit)? = onInputDevicesChanged
    private var controllerCallback: ((Boolean) -> Unit)? = onControllerStateChanged

    fun start() {
        if (isStarted) return
        isStarted = true
        
        controllerMonitor.start()
        inputManager.registerInputDeviceListener(this, handler)
        
        // Initial device scan
        notifyDevicesChanged()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        
        controllerMonitor.stop()
        inputManager.unregisterInputDeviceListener(this)
    }

    /**
     * Process gamepad and joystick input events
     */
    fun processMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        
        return when {
            isGamepadDevice(device) -> controllerMapper.processGamepadEvent(event, device)
            isJoystickDevice(device) -> controllerMapper.processJoystickEvent(event, device)
            else -> false
        }
    }

    /**
     * Process controller button events
     */
    fun processKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        
        return when {
            isGamepadDevice(device) -> controllerMapper.processGamepadKeyEvent(event, device)
            else -> false
        }
    }

    /**
     * Process touch screen events for touch-to-mouse/keyboard mapping
     */
    fun processTouchEvent(event: MotionEvent): Boolean {
        return touchMapper.processTouchEvent(event)
    }

    /**
     * Toggle on-screen virtual controls
     */
    fun setOnScreenControlsEnabled(enabled: Boolean) {
        _enableOnScreenControls.value = enabled
    }

    /**
     * Get all connected input devices
     */
    fun getConnectedDevices(): List<InputDevice> = _connectedDevices.value

    /**
     * Check if any controllers are connected
     */
    fun hasConnectedControllers(): Boolean = _hasControllers.value

    /**
     * Configure controller input mappings
     */
    fun configureControllerMappings(config: ControllerMappingConfig) {
        controllerMapper.configure(config)
    }

    /**
     * Configure touch input mappings
     */
    fun configureTouchMappings(config: TouchMappingConfig) {
        touchMapper.configure(config)
    }

    /**
     * Set callback for input device changes
     */
    fun setOnInputDevicesChangedListener(listener: (List<InputDevice>) -> Unit) {
        // Already handled by constructor callback
    }

    /**
     * Set callback for controller state changes
     */
    fun setOnControllerStateChangedListener(listener: (Boolean) -> Unit) {
        // Already handled by constructor callback
    }
    
    /**
     * Set input event listener
     */
    fun setInputEventListener(listener: (ControllerInputEvent) -> Unit) {
        controllerMapper.setInputEventListener(listener)
    }

    private fun notifyDevicesChanged() {
        val devices = getAllInputDevices()
        _connectedDevices.value = devices
        _hasControllers.value = devices.any { 
            isGamepadDevice(it) || isJoystickDevice(it) 
        }
    }

    private fun getAllInputDevices(): List<InputDevice> {
        return InputDevice.getDeviceIds()
            .toList()
            .mapNotNull { deviceId -> InputDevice.getDevice(deviceId) }
            .filter { device ->
                device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                device.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
            }
    }

    private fun isGamepadDevice(device: InputDevice): Boolean {
        return device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
    }

    private fun isJoystickDevice(device: InputDevice): Boolean {
        return device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    override fun onInputDeviceAdded(deviceId: Int) = notifyDevicesChanged()

    override fun onInputDeviceRemoved(deviceId: Int) = notifyDevicesChanged()

    override fun onInputDeviceChanged(deviceId: Int) = notifyDevicesChanged()
}

/**
 * Configuration for controller input mappings
 */
data class ControllerMappingConfig(
    val buttonMappings: Map<Int, String> = defaultButtonMappings,
    val analogMappings: Map<String, String> = defaultAnalogMappings,
    val deadzone: Float = 0.15f
)

/**
 * Configuration for touch input mappings
 */
data class TouchMappingConfig(
    val mouseMode: Boolean = true,
    val gesturesEnabled: Boolean = true,
    val virtualKeyboardEnabled: Boolean = false
)

// Default controller mappings
private val defaultButtonMappings = mapOf(
    KeyEvent.KEYCODE_BUTTON_A to "KEY_Z",        // A button -> Z key
    KeyEvent.KEYCODE_BUTTON_B to "KEY_X",        // B button -> X key  
    KeyEvent.KEYCODE_BUTTON_X to "KEY_A",        // X button -> A key
    KeyEvent.KEYCODE_BUTTON_Y to "KEY_S",        // Y button -> S key
    
    KeyEvent.KEYCODE_BUTTON_L1 to "KEY_Q",       // Left shoulder -> Q key
    KeyEvent.KEYCODE_BUTTON_R1 to "KEY_E",       // Right shoulder -> E key
    
    KeyEvent.KEYCODE_BUTTON_START to "KEY_ENTER", // Start -> Enter
    KeyEvent.KEYCODE_BUTTON_SELECT to "KEY_ESC",  // Select -> Escape
    
    KeyEvent.KEYCODE_BUTTON_THUMBL to "KEY_SPACE",  // Left stick click -> Space
    KeyEvent.KEYCODE_BUTTON_THUMBR to "KEY_TAB",    // Right stick click -> Tab
)

private val defaultAnalogMappings = mapOf(
    "LEFT_X" to "MOUSE_X",     // Left stick X -> Mouse X
    "LEFT_Y" to "MOUSE_Y",     // Left stick Y -> Mouse Y
    "RIGHT_X" to "KEY_ARROWS", // Right stick X -> Arrow keys
    "RIGHT_Y" to "KEY_ARROWS", // Right stick Y -> Arrow keys
)