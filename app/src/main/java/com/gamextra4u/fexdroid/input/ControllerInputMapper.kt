package com.gamextra4u.fexdroid.input

import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Maps controller input (gamepads, joysticks) to x86 game input events
 * Handles button presses, analog stick movement, and trigger input
 */
class ControllerInputMapper {
    
    private var config = ControllerMappingConfig()
    private val activeButtons = mutableSetOf<Int>()
    private val analogValues = mutableMapOf<String, Float>()
    private var lastStickStates = mutableMapOf<String, Boolean>()
    
    private var inputEventListener: ((ControllerInputEvent) -> Unit)? = null
    
    /**
     * Configure the mapper with custom button and analog mappings
     */
    fun configure(config: ControllerMappingConfig) {
        this.config = config
        Log.d(TAG, "Controller mappings configured: $config")
    }
    
    /**
     * Set the input event listener
     */
    fun setInputEventListener(listener: (ControllerInputEvent) -> Unit) {
        this.inputEventListener = listener
    }

    /**
     * Process gamepad button and trigger events
     */
    fun processGamepadKeyEvent(event: KeyEvent, device: InputDevice): Boolean {
        if (event.repeatCount > 0) return true // Ignore key repeats
        
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                activeButtons.add(event.keyCode)
                handleButtonPress(event.keyCode, device)
                return true
            }
            KeyEvent.ACTION_UP -> {
                activeButtons.remove(event.keyCode)
                handleButtonRelease(event.keyCode, device)
                return true
            }
        }
        return false
    }

    /**
     * Process gamepad analog stick and trigger movement
     */
    fun processGamepadEvent(event: MotionEvent, device: InputDevice): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                processAnalogMovement(event, device)
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                handleButtonPress(event.actionButton, device)
                return true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                handleButtonRelease(event.actionButton, device)
                return true
            }
        }
        return false
    }

    /**
     * Process joystick input for analog stick movement
     */
    fun processJoystickEvent(event: MotionEvent, device: InputDevice): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                processJoystickMovement(event, device)
                return true
            }
        }
        return false
    }

    private fun handleButtonPress(keyCode: Int, device: InputDevice) {
        val mappedKey = config.buttonMappings[keyCode] ?: return
        
        Log.d(TAG, "Button pressed: ${getButtonName(keyCode)} -> $mappedKey (Device: ${device.name})")
        
        // Send mapped input event to the emulated environment
        sendInputEvent(ControllerInputEvent.ButtonPressed(
            deviceId = device.id,
            deviceName = device.name,
            originalKeyCode = keyCode,
            mappedKey = mappedKey,
            timestamp = System.currentTimeMillis()
        ))
    }

    private fun handleButtonRelease(keyCode: Int, device: InputDevice) {
        val mappedKey = config.buttonMappings[keyCode] ?: return
        
        Log.d(TAG, "Button released: ${getButtonName(keyCode)} -> $mappedKey (Device: ${device.name})")
        
        // Send mapped input event to the emulated environment
        sendInputEvent(ControllerInputEvent.ButtonReleased(
            deviceId = device.id,
            deviceName = device.name,
            originalKeyCode = keyCode,
            mappedKey = mappedKey,
            timestamp = System.currentTimeMillis()
        ))
    }

    private fun processAnalogMovement(event: MotionEvent, device: InputDevice) {
        val historySize = event.historySize
        for (i in 0..historySize) {
            processAnalogFrame(event.getHistoricalX(0, i), event.getHistoricalY(0, i), device)
        }
        processAnalogFrame(event.x, event.y, device)
    }

    private fun processJoystickMovement(event: MotionEvent, device: InputDevice) {
        val historySize = event.historySize
        for (i in 0..historySize) {
            processJoystickFrame(event, i, device)
        }
        processJoystickFrame(event, historySize, device)
    }

    private fun processAnalogFrame(x: Float, y: Float, device: InputDevice) {
        // Left stick (typically axis 0 and 1)
        val leftX = getAxisValue(event = null, device, MotionEvent.AXIS_X)
        val leftY = getAxisValue(event = null, device, MotionEvent.AXIS_Y)
        
        if (abs(leftX) > config.deadzone || abs(leftY) > config.deadzone) {
            handleLeftStickMovement(leftX, leftY, device)
        } else {
            handleLeftStickDeadzone(device)
        }

        // Right stick (typically axis 2 and 3)  
        val rightX = getAxisValue(event = null, device, MotionEvent.AXIS_Z)
        val rightY = getAxisValue(event = null, device, MotionEvent.AXIS_RZ)
        
        if (abs(rightX) > config.deadzone || abs(rightY) > config.deadzone) {
            handleRightStickMovement(rightX, rightY, device)
        } else {
            handleRightStickDeadzone(device)
        }

        // Triggers (typically axis 4 and 5)
        val leftTrigger = getAxisValue(event = null, device, MotionEvent.AXIS_LTRIGGER)
        val rightTrigger = getAxisValue(event = null, device, MotionEvent.AXIS_RTRIGGER)
        
        if (leftTrigger > config.deadzone) {
            handleLeftTrigger(leftTrigger, device)
        } else {
            handleLeftTriggerReleased(device)
        }
        
        if (rightTrigger > config.deadzone) {
            handleRightTrigger(rightTrigger, device)
        } else {
            handleRightTriggerReleased(device)
        }
    }

    private fun processJoystickFrame(event: MotionEvent, historyIndex: Int, device: InputDevice) {
        // Process individual joystick axes
        for (axis in 0 until event.axisCount) {
            val value = if (historyIndex < event.historySize) {
                event.getHistoricalAxisValue(axis, 0, historyIndex)
            } else {
                event.getAxisValue(axis)
            }
            
            if (abs(value) > config.deadzone) {
                handleJoystickAxis(axis, value, device)
            }
        }
    }

    private fun handleLeftStickMovement(x: Float, y: Float, device: InputDevice) {
        val normalizedX = normalizeAnalogValue(x)
        val normalizedY = normalizeAnalogValue(y)
        
        // Map to mouse movement for navigation/aiming
        sendInputEvent(ControllerInputEvent.AnalogMovement(
            deviceId = device.id,
            deviceName = device.name,
            analogType = "LEFT_STICK",
            x = normalizedX,
            y = normalizedY,
            intensity = abs(normalizedX) + abs(normalizedY),
            timestamp = System.currentTimeMillis()
        ))
        
        Log.d(TAG, "Left stick: X=$normalizedX, Y=$normalizedY")
    }

    private fun handleLeftStickDeadzone(device: InputDevice) {
        if (lastStickStates["LEFT_STICK"] == true) {
            sendInputEvent(ControllerInputEvent.AnalogReleased(
                deviceId = device.id,
                analogType = "LEFT_STICK",
                timestamp = System.currentTimeMillis()
            ))
        }
        lastStickStates["LEFT_STICK"] = false
    }

    private fun handleRightStickMovement(x: Float, y: Float, device: InputDevice) {
        val normalizedX = normalizeAnalogValue(x)
        val normalizedY = normalizeAnalogValue(y)
        
        // Map to arrow keys for navigation
        sendInputEvent(ControllerInputEvent.AnalogMovement(
            deviceId = device.id,
            deviceName = device.name,
            analogType = "RIGHT_STICK", 
            x = normalizedX,
            y = normalizedY,
            intensity = abs(normalizedX) + abs(normalizedY),
            timestamp = System.currentTimeMillis()
        ))
        
        Log.d(TAG, "Right stick: X=$normalizedX, Y=$normalizedY")
    }

    private fun handleRightStickDeadzone(device: InputDevice) {
        if (lastStickStates["RIGHT_STICK"] == true) {
            sendInputEvent(ControllerInputEvent.AnalogReleased(
                deviceId = device.id,
                analogType = "RIGHT_STICK",
                timestamp = System.currentTimeMillis()
            ))
        }
        lastStickStates["RIGHT_STICK"] = false
    }

    private fun handleLeftTrigger(value: Float, device: InputDevice) {
        val normalizedValue = normalizeTriggerValue(value)
        sendInputEvent(ControllerInputEvent.TriggerPressed(
            deviceId = device.id,
            deviceName = device.name,
            triggerType = "LEFT",
            intensity = normalizedValue,
            timestamp = System.currentTimeMillis()
        ))
    }

    private fun handleLeftTriggerReleased(device: InputDevice) {
        sendInputEvent(ControllerInputEvent.TriggerReleased(
            deviceId = device.id,
            triggerType = "LEFT",
            timestamp = System.currentTimeMillis()
        ))
    }

    private fun handleRightTrigger(value: Float, device: InputDevice) {
        val normalizedValue = normalizeTriggerValue(value)
        sendInputEvent(ControllerInputEvent.TriggerPressed(
            deviceId = device.id,
            deviceName = device.name,
            triggerType = "RIGHT",
            intensity = normalizedValue,
            timestamp = System.currentTimeMillis()
        ))
    }

    private fun handleRightTriggerReleased(device: InputDevice) {
        sendInputEvent(ControllerInputEvent.TriggerReleased(
            deviceId = device.id,
            triggerType = "RIGHT",
            timestamp = System.currentTimeMillis()
        ))
    }

    private fun handleJoystickAxis(axis: Int, value: Float, device: InputDevice) {
        val normalizedValue = normalizeAnalogValue(value)
        val axisName = "AXIS_$axis"
        
        sendInputEvent(ControllerInputEvent.JoystickAxis(
            deviceId = device.id,
            deviceName = device.name,
            axis = axis,
            value = normalizedValue,
            timestamp = System.currentTimeMillis()
        ))
    }

    private fun getAxisValue(event: MotionEvent?, device: InputDevice, axis: Int): Float {
        return try {
            // Try to get from MotionEvent first, then fallback to InputDevice
            event?.getAxisValue(axis) ?: device.getMotionRange(axis)?.value ?: 0f
        } catch (e: Exception) {
            0f
        }
    }

    private fun normalizeAnalogValue(value: Float): Float {
        return when {
            value > config.deadzone -> (value - config.deadzone) / (1f - config.deadzone)
            value < -config.deadzone -> (value + config.deadzone) / (1f - config.deadzone)
            else -> 0f
        }
    }

    private fun normalizeTriggerValue(value: Float): Float {
        // Triggers typically range from 0.0 (not pressed) to 1.0 (fully pressed)
        return max(0f, min(1f, value))
    }

    private fun getButtonName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "A"
            KeyEvent.KEYCODE_BUTTON_B -> "B" 
            KeyEvent.KEYCODE_BUTTON_X -> "X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
            KeyEvent.KEYCODE_BUTTON_START -> "START"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "SELECT"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
            else -> "UNKNOWN($keyCode)"
        }
    }

    private fun sendInputEvent(event: ControllerInputEvent) {
        inputEventListener?.invoke(event)
        
        // For now, log the event. In a real implementation, this would
        // send the event to the FEX emulation environment
        Log.d(TAG, "Input event: $event")
    }

    companion object {
        private const val TAG = "ControllerInputMapper"
    }
}

/**
 * Sealed hierarchy for controller input events
 */
sealed class ControllerInputEvent(val timestamp: Long) {
    data class ButtonPressed(
        val deviceId: Int,
        val deviceName: String,
        val originalKeyCode: Int,
        val mappedKey: String,
        override val timestamp: Long
    ) : ControllerInputEvent(timestamp)
    
    data class ButtonReleased(
        val deviceId: Int,
        val deviceName: String,
        val originalKeyCode: Int,
        val mappedKey: String,
        override val timestamp: Long
    ) : ControllerInputEvent(timestamp)
    
    data class AnalogMovement(
        val deviceId: Int,
        val deviceName: String,
        val analogType: String,
        val x: Float,
        val y: Float,
        val intensity: Float,
        override val timestamp: Long
    ) : ControllerInputEvent(timestamp)
    
    data class AnalogReleased(
        val deviceId: Int,
        val analogType: String,
        override val timestamp: Long
    ) : ControllerInputEvent(timestamp)
    
    data class TriggerPressed(
        val deviceId: Int,
        val deviceName: String,
        val triggerType: String,
        val intensity: Float,
        override val timestamp: Long
    ) : ControllerInputEvent(timestamp)
    
    data class TriggerReleased(
        val deviceId: Int,
        val triggerType: String,
        override val timestamp: Long
    ) : ControllerInputEvent(timestamp)
    
    data class JoystickAxis(
        val deviceId: Int,
        val deviceName: String,
        val axis: Int,
        val value: Float,
        override val timestamp: Long
    ) : ControllerInputEvent(timestamp)
}