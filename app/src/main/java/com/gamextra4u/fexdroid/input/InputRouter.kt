package com.gamextra4u.fexdroid.input

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Coordinates all input sources and routes them to the FEX emulation environment
 * Handles input device priority, fallback scenarios, and input event distribution
 */
class InputRouter(
    private val context: Context,
    private val scope: CoroutineScope
) {
    
    private val inputManager = InputManager(
        context = context,
        onInputDevicesChanged = { devices ->
            onDevicesChanged(devices)
        },
        onControllerStateChanged = { hasControllers ->
            onControllerStateChanged(hasControllers)
        }
    )
    
    private var onScreenControls: OnScreenControls? = null
    
    // Input routing state
    private val activeInputSources = mutableSetOf<InputSource>()
    private val inputPriority = mapOf(
        InputSource.CONTROLLER to 3,
        InputSource.TOUCH to 2,
        InputSource.KEYBOARD to 1
    )
    
    private var currentPrimarySource = InputSource.TOUCH
    
    // Input event queues for each source
    private val controllerEventQueue = mutableListOf<InputEvent>()
    private val touchEventQueue = mutableListOf<InputEvent>()
    private val keyboardEventQueue = mutableListOf<InputEvent>()
    
    // Input mapping configuration
    private var controllerConfig = ControllerMappingConfig()
    private var touchConfig = TouchMappingConfig()
    
    // Callbacks for state updates
    private var onInputStateChanged: ((InputState) -> Unit)? = null
    private var onInputDevicesChanged: ((List<InputDevice>) -> Unit)? = null
    private var onControllerStateChanged: ((Boolean) -> Unit)? = null
    
    // Input event listeners
    private var onInputEventListener: ((InputEvent) -> Unit)? = null
    
    init {
        setupInputEventHandlers()
    }
    
    /**
     * Start the input routing system
     */
    fun start() {
        Log.d(TAG, "Starting input router")
        inputManager.start()
        
        // Determine initial primary input source
        determinePrimaryInputSource()
        
        // Start input event processing
        startInputEventProcessing()
    }
    
    /**
     * Stop the input routing system
     */
    fun stop() {
        Log.d(TAG, "Stopping input router")
        inputManager.stop()
        activeInputSources.clear()
    }
    
    /**
     * Attach on-screen controls overlay
     */
    fun attachOnScreenControls(onScreenControls: OnScreenControls) {
        this.onScreenControls = onScreenControls
        
        // Set up callback handlers
        onScreenControls.setOnButtonPressedListener { buttonName ->
            handleOnScreenButtonPressed(buttonName)
        }
        
        onScreenControls.setOnButtonReleasedListener { buttonName ->
            handleOnScreenButtonReleased(buttonName)
        }
        
        onScreenControls.setOnStickMoveListener { stickName, x, y ->
            handleOnScreenStickMove(stickName, x, y)
        }
        
        Log.d(TAG, "On-screen controls attached")
    }
    
    /**
     * Process motion events (gamepad/joystick/touch)
     */
    fun processMotionEvent(event: MotionEvent): Boolean {
        val device = event.device
        
        return when {
            // Controller input
            device != null && (device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) -> {
                val handled = inputManager.processMotionEvent(event)
                if (handled) {
                    val inputEvent = createInputEventFromMotion(event, device)
                    queueInputEvent(inputEvent, InputSource.CONTROLLER)
                }
                handled
            }
            
            // Touch input
            device == null || device.sources and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN -> {
                val handled = inputManager.processTouchEvent(event)
                if (handled) {
                    val inputEvent = createInputEventFromMotion(event, device)
                    queueInputEvent(inputEvent, InputSource.TOUCH)
                }
                handled
            }
            
            else -> false
        }
    }
    
    /**
     * Process key events (controller buttons/keyboard)
     */
    fun processKeyEvent(event: KeyEvent): Boolean {
        val device = event.device
        
        return when {
            // Controller input
            device != null && device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD -> {
                val handled = inputManager.processKeyEvent(event)
                if (handled) {
                    val inputEvent = createInputEventFromKey(event, device)
                    queueInputEvent(inputEvent, InputSource.CONTROLLER)
                }
                handled
            }
            
            // Keyboard input
            device != null && device.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD -> {
                val inputEvent = createInputEventFromKey(event, device)
                queueInputEvent(inputEvent, InputSource.KEYBOARD)
                false // Don't consume keyboard events
            }
            
            else -> false
        }
    }
    
    /**
     * Enable or disable on-screen controls
     */
    fun setOnScreenControlsEnabled(enabled: Boolean) {
        inputManager.setOnScreenControlsEnabled(enabled)
        onScreenControls?.visibility = if (enabled) View.VISIBLE else View.GONE
        
        Log.d(TAG, "On-screen controls ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Configure controller input mappings
     */
    fun configureControllerMappings(config: ControllerMappingConfig) {
        controllerConfig = config
        inputManager.configureControllerMappings(config)
        Log.d(TAG, "Controller mappings configured: $config")
    }
    
    /**
     * Configure touch input mappings
     */
    fun configureTouchMappings(config: TouchMappingConfig) {
        touchConfig = config
        inputManager.configureTouchMappings(config)
        Log.d(TAG, "Touch mappings configured: $config")
    }
    
    /**
     * Get current input state
     */
    fun getInputState(): InputState {
        return InputState(
            activeInputSources = activeInputSources.toList(),
            primarySource = currentPrimarySource,
            connectedDevices = inputManager.getConnectedDevices(),
            hasControllers = inputManager.hasConnectedControllers(),
            onScreenControlsVisible = onScreenControls?.visibility == View.VISIBLE
        )
    }
    
    /**
     * Get all connected input devices
     */
    fun getConnectedDevices(): List<InputDevice> = inputManager.getConnectedDevices()
    
    /**
     * Check if any controllers are connected
     */
    fun hasConnectedControllers(): Boolean = inputManager.hasConnectedControllers()
    
    // State change callbacks
    fun setOnInputStateChangedListener(listener: (InputState) -> Unit) {
        onInputStateChanged = listener
    }
    
    fun setOnInputDevicesChangedListener(listener: (List<InputDevice>) -> Unit) {
        onInputDevicesChanged = listener
    }
    
    fun setOnControllerStateChangedListener(listener: (Boolean) -> Unit) {
        onControllerStateChanged = listener
    }
    
    fun setOnInputEventListener(listener: (InputEvent) -> Unit) {
        onInputEventListener = listener
    }
    
    private fun setupInputEventHandlers() {
        inputManager.setOnInputDevicesChangedListener { devices ->
            handleDevicesChanged(devices)
        }
        
        inputManager.setOnControllerStateChangedListener { hasControllers ->
            handleControllerStateChanged(hasControllers)
        }
    }
    
    private fun onDevicesChanged(devices: List<InputDevice>) {
        activeInputSources.clear()
        devices.forEach { device ->
            when {
                device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD -> {
                    activeInputSources.add(InputSource.CONTROLLER)
                }
                device.sources and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN -> {
                    activeInputSources.add(InputSource.TOUCH)
                }
                device.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD -> {
                    activeInputSources.add(InputSource.KEYBOARD)
                }
            }
        }
        
        determinePrimaryInputSource()
        notifyStateChanged()
    }
    
    private fun onControllerStateChanged(hasControllers: Boolean) {
        if (hasControllers) {
            activeInputSources.add(InputSource.CONTROLLER)
            if (currentPrimarySource != InputSource.CONTROLLER) {
                currentPrimarySource = InputSource.CONTROLLER
            }
        } else {
            activeInputSources.remove(InputSource.CONTROLLER)
            if (currentPrimarySource == InputSource.CONTROLLER) {
                determinePrimaryInputSource()
            }
        }
        
        notifyStateChanged()
    }
    
    private fun determinePrimaryInputSource() {
        currentPrimarySource = when {
            activeInputSources.contains(InputSource.CONTROLLER) -> InputSource.CONTROLLER
            activeInputSources.contains(InputSource.TOUCH) -> InputSource.TOUCH
            activeInputSources.contains(InputSource.KEYBOARD) -> InputSource.KEYBOARD
            else -> InputSource.TOUCH // Default fallback
        }
        
        Log.d(TAG, "Primary input source: $currentPrimarySource")
    }
    
    private fun handleDevicesChanged(devices: List<InputDevice>) {
        Log.d(TAG, "Input devices changed: ${devices.map { it.name }}")
        onInputDevicesChanged?.invoke(devices)
    }
    
    private fun handleControllerStateChanged(hasControllers: Boolean) {
        Log.d(TAG, "Controller state changed: $hasControllers")
        onControllerStateChanged?.invoke(hasControllers)
    }
    
    private fun startInputEventProcessing() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                processInputEvents()
                kotlinx.coroutines.delay(16) // ~60 FPS
            }
        }
    }
    
    private fun processInputEvents() {
        // Process events from all sources
        processControllerEvents()
        processTouchEvents()
        processKeyboardEvents()
    }
    
    private fun processControllerEvents() {
        synchronized(controllerEventQueue) {
            controllerEventQueue.forEach { event ->
                routeInputEvent(event, InputSource.CONTROLLER)
            }
            controllerEventQueue.clear()
        }
    }
    
    private fun processTouchEvents() {
        synchronized(touchEventQueue) {
            touchEventQueue.forEach { event ->
                routeInputEvent(event, InputSource.TOUCH)
            }
            touchEventQueue.clear()
        }
    }
    
    private fun processKeyboardEvents() {
        synchronized(keyboardEventQueue) {
            keyboardEventQueue.forEach { event ->
                routeInputEvent(event, InputSource.KEYBOARD)
            }
            keyboardEventQueue.clear()
        }
    }
    
    private fun queueInputEvent(event: InputEvent, source: InputSource) {
        when (source) {
            InputSource.CONTROLLER -> synchronized(controllerEventQueue) {
                controllerEventQueue.add(event)
            }
            InputSource.TOUCH -> synchronized(touchEventQueue) {
                touchEventQueue.add(event)
            }
            InputSource.KEYBOARD -> synchronized(keyboardEventQueue) {
                keyboardEventQueue.add(event)
            }
        }
    }
    
    private fun routeInputEvent(event: InputEvent, source: InputSource) {
        // Log input event for debugging
        Log.d(TAG, "Routing input event: $event from $source")
        
        // Apply input mappings based on source
        val mappedEvent = applyInputMappings(event, source)
        
        // Send to on-screen controls if active
        if (source == InputSource.TOUCH && onScreenControls?.visibility == View.VISIBLE) {
            // Touch events might be consumed by on-screen controls
            return
        }
        
        // Send to FEX emulation environment
        sendToFexEnvironment(mappedEvent)
        
        // Notify listeners
        onInputEventListener?.invoke(mappedEvent)
    }
    
    private fun applyInputMappings(event: InputEvent, source: InputSource): InputEvent {
        return when (source) {
            InputSource.CONTROLLER -> when (event) {
                is InputEvent.ButtonEvent -> {
                    event.copy(
                        mappedKey = controllerConfig.buttonMappings[event.keyCode] ?: event.mappedKey
                    )
                }
                is InputEvent.AnalogEvent -> {
                    event.copy(
                        deadzone = controllerConfig.deadzone
                    )
                }
                else -> event
            }
            
            InputSource.TOUCH -> when (event) {
                is InputEvent.TouchEvent -> {
                    event.copy(
                        mouseMode = touchConfig.mouseMode
                    )
                }
                else -> event
            }
            
            InputSource.KEYBOARD -> event // No mapping needed for keyboard
        }
    }
    
    private fun sendToFexEnvironment(event: InputEvent) {
        scope.launch {
            try {
                // Create input event file for FEX to read
                val inputDir = File(context.filesDir, "input")
                if (!inputDir.exists()) {
                    inputDir.mkdirs()
                }
                
                val eventFile = File(inputDir, "input_event.tmp")
                ObjectOutputStream(FileOutputStream(eventFile)).use { oos ->
                    oos.writeObject(event)
                    oos.flush()
                }
                
                // Rename to final location (atomic operation)
                val finalFile = File(inputDir, "input_event")
                eventFile.renameTo(finalFile)
                
                Log.d(TAG, "Sent input event to FEX: $event")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send input event to FEX", e)
            }
        }
    }
    
    private fun notifyStateChanged() {
        val state = getInputState()
        onInputStateChanged?.invoke(state)
    }
    
    // On-screen control handlers
    private fun handleOnScreenButtonPressed(buttonName: String) {
        val event = InputEvent.ButtonEvent(
            action = "PRESS",
            buttonName = buttonName,
            keyCode = -1,
            mappedKey = getMappedKeyForButton(buttonName),
            source = InputSource.ON_SCREEN,
            timestamp = System.currentTimeMillis()
        )
        
        queueInputEvent(event, InputSource.ON_SCREEN)
        Log.d(TAG, "On-screen button pressed: $buttonName")
    }
    
    private fun handleOnScreenButtonReleased(buttonName: String) {
        val event = InputEvent.ButtonEvent(
            action = "RELEASE",
            buttonName = buttonName,
            keyCode = -1,
            mappedKey = getMappedKeyForButton(buttonName),
            source = InputSource.ON_SCREEN,
            timestamp = System.currentTimeMillis()
        )
        
        queueInputEvent(event, InputSource.ON_SCREEN)
        Log.d(TAG, "On-screen button released: $buttonName")
    }
    
    private fun handleOnScreenStickMove(stickName: String, x: Float, y: Float) {
        val event = InputEvent.AnalogEvent(
            analogType = stickName,
            x = x,
            y = y,
            intensity = abs(x) + abs(y),
            deadzone = 0.1f,
            source = InputSource.ON_SCREEN,
            timestamp = System.currentTimeMillis()
        )
        
        queueInputEvent(event, InputSource.ON_SCREEN)
        Log.d(TAG, "On-screen stick move: $stickName ($x, $y)")
    }
    
    private fun getMappedKeyForButton(buttonName: String): String {
        return when (buttonName) {
            "A" -> "KEY_Z"
            "B" -> "KEY_X" 
            "X" -> "KEY_A"
            "Y" -> "KEY_S"
            "L1" -> "KEY_Q"
            "R1" -> "KEY_E"
            "START" -> "KEY_ENTER"
            "SELECT" -> "KEY_ESC"
            "DPAD_UP" -> "KEY_UP"
            "DPAD_DOWN" -> "KEY_DOWN"
            "DPAD_LEFT" -> "KEY_LEFT"
            "DPAD_RIGHT" -> "KEY_RIGHT"
            else -> "UNKNOWN"
        }
    }
    
    // Event creation helpers
    private fun createInputEventFromMotion(event: MotionEvent, device: InputDevice?): InputEvent {
        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> InputEvent.AnalogEvent(
                analogType = "MOUSE",
                x = event.x,
                y = event.y,
                intensity = 1.0f,
                deadzone = 0.1f,
                source = if (device != null) InputSource.CONTROLLER else InputSource.TOUCH,
                timestamp = System.currentTimeMillis()
            )
            
            MotionEvent.ACTION_DOWN -> InputEvent.TouchEvent(
                action = "DOWN",
                x = event.x,
                y = event.y,
                touchCount = event.pointerCount,
                mouseMode = true,
                source = InputSource.TOUCH,
                timestamp = System.currentTimeMillis()
            )
            
            MotionEvent.ACTION_UP -> InputEvent.TouchEvent(
                action = "UP",
                x = event.x,
                y = event.y,
                touchCount = event.pointerCount,
                mouseMode = true,
                source = InputSource.TOUCH,
                timestamp = System.currentTimeMillis()
            )
            
            else -> InputEvent.GenericEvent(
                eventType = "MOTION",
                source = if (device != null) InputSource.CONTROLLER else InputSource.TOUCH,
                timestamp = System.currentTimeMillis()
            )
        }
    }
    
    private fun createInputEventFromKey(event: KeyEvent, device: InputDevice?): InputEvent {
        return InputEvent.ButtonEvent(
            action = if (event.action == KeyEvent.ACTION_DOWN) "PRESS" else "RELEASE",
            buttonName = getKeyName(event.keyCode),
            keyCode = event.keyCode,
            mappedKey = controllerConfig.buttonMappings[event.keyCode] ?: "UNKNOWN",
            source = if (device != null) InputSource.CONTROLLER else InputSource.KEYBOARD,
            timestamp = System.currentTimeMillis()
        )
    }
    
    private fun getKeyName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "A"
            KeyEvent.KEYCODE_BUTTON_B -> "B"
            KeyEvent.KEYCODE_BUTTON_X -> "X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
            KeyEvent.KEYCODE_BUTTON_START -> "START"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "SELECT"
            else -> "UNKNOWN($keyCode)"
        }
    }
    
    companion object {
        private const val TAG = "InputRouter"
    }
}

/**
 * Input source types
 */
enum class InputSource {
    CONTROLLER,
    TOUCH,
    KEYBOARD,
    ON_SCREEN
}

/**
 * Input state information
 */
data class InputState(
    val activeInputSources: List<InputSource>,
    val primarySource: InputSource,
    val connectedDevices: List<InputDevice>,
    val hasControllers: Boolean,
    val onScreenControlsVisible: Boolean
)

/**
 * Unified input event types
 */
sealed class InputEvent(open val source: InputSource, open val timestamp: Long) {
    data class ButtonEvent(
        val action: String,
        val buttonName: String,
        val keyCode: Int,
        val mappedKey: String,
        override val source: InputSource,
        override val timestamp: Long
    ) : InputEvent(source, timestamp)
    
    data class AnalogEvent(
        val analogType: String,
        val x: Float,
        val y: Float,
        val intensity: Float,
        val deadzone: Float,
        override val source: InputSource,
        override val timestamp: Long
    ) : InputEvent(source, timestamp)
    
    data class TouchEvent(
        val action: String,
        val x: Float,
        val y: Float,
        val touchCount: Int,
        val mouseMode: Boolean,
        override val source: InputSource,
        override val timestamp: Long
    ) : InputEvent(source, timestamp)
    
    data class GenericEvent(
        val eventType: String,
        override val source: InputSource,
        override val timestamp: Long
    ) : InputEvent(source, timestamp)
}