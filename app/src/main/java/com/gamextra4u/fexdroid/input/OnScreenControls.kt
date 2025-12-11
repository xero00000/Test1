package com.gamextra4u.fexdroid.input

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import kotlin.math.abs

/**
 * Virtual on-screen gamepad controls overlay for devices without physical controllers
 * Provides a full gamepad interface with d-pad, buttons, and analog sticks
 */
class OnScreenControls @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Control regions and dimensions
    private val dpadRect = RectF()
    private val leftStickRect = RectF()
    private val rightStickRect = RectF()
    private val faceButtonsRect = RectF()
    
    private var controlSize = 80f
    private var padding = 20f
    private var strokeWidth = 4f
    
    // Visual state
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 50, 50, 50) // Semi-transparent dark gray
        style = Paint.Style.FILL
    }
    
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 100, 100, 100)
        style = Paint.Style.STROKE
        strokeWidth = this@OnScreenControls.strokeWidth
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }
    
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
    }
    
    // Control states
    private val buttonStates = mutableMapOf<String, Boolean>()
    private val stickPositions = mutableMapOf<String, PointF>()
    private val activeTouches = mutableMapOf<Int, TouchRegion>()
    
    // Callbacks
    private var onButtonPressed: ((String) -> Unit)? = null
    private var onButtonReleased: ((String) -> Unit)? = null
    private var onStickMove: ((String, Float, Float) -> Unit)? = null
    
    init {
        setLayerType(LAYER_TYPE_HARDWARE, null) // Enable hardware acceleration
        
        // Initialize button states
        buttonStates["A"] = false
        buttonStates["B"] = false
        buttonStates["X"] = false
        buttonStates["Y"] = false
        buttonStates["START"] = false
        buttonStates["SELECT"] = false
        buttonStates["L1"] = false
        buttonStates["R1"] = false
        
        // Initialize stick positions
        stickPositions["LEFT"] = PointF(0f, 0f)
        stickPositions["RIGHT"] = PointF(0f, 0f)
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        // Calculate control sizes based on screen size
        controlSize = min(w, h) * 0.12f
        padding = controlSize * 0.25f
        strokeWidth = controlSize * 0.05f
        
        updateStrokeWidth()
        layoutControls()
    }
    
    private fun updateStrokeWidth() {
        strokePaint.strokeWidth = strokeWidth
        textPaint.textSize = controlSize * 0.3f
        buttonTextPaint.textSize = controlSize * 0.25f
    }
    
    private fun layoutControls() {
        val width = width.toFloat()
        val height = height.toFloat()
        
        // D-Pad (bottom left)
        val dpadSize = controlSize * 1.2f
        dpadRect.set(
            padding,
            height - dpadSize - padding,
            padding + dpadSize,
            height - padding
        )
        
        // Left analog stick (bottom left, above D-Pad)
        val leftStickSize = controlSize * 1.1f
        leftStickRect.set(
            padding,
            height - dpadSize - leftStickSize - padding * 1.5f,
            padding + leftStickSize,
            height - dpadSize - padding * 0.5f
        )
        
        // Right analog stick (bottom right)
        val rightStickSize = controlSize * 1.1f
        rightStickRect.set(
            width - padding - rightStickSize,
            height - rightStickSize - padding,
            width - padding,
            height - padding
        )
        
        // Face buttons (bottom right, left of right stick)
        val faceButtonsSize = controlSize * 1.3f
        faceButtonsRect.set(
            width - padding - rightStickSize - faceButtonsSize - padding * 0.5f,
            height - faceButtonsSize - padding,
            width - padding - rightStickSize - padding * 0.5f,
            height - padding
        )
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw all controls
        drawDPad(canvas)
        drawAnalogSticks(canvas)
        drawFaceButtons(canvas)
        drawShoulderButtons(canvas)
        drawStartSelectButtons(canvas)
    }
    
    private fun drawDPad(canvas: Canvas) {
        val centerX = dpadRect.centerX()
        val centerY = dpadRect.centerY()
        val size = dpadRect.width() / 2f
        
        // Draw D-pad base
        val dpadPaint = if (isAnyDPadPressed()) {
            Paint(paint).apply { color = Color.argb(220, 70, 70, 70) }
        } else {
            paint
        }
        
        canvas.drawRoundRect(dpadRect, size * 0.1f, size * 0.1f, dpadPaint)
        canvas.drawRoundRect(dpadRect, size * 0.1f, size * 0.1f, strokePaint)
        
        // Draw directional arrows
        val arrowSize = size * 0.4f
        val arrowPaint = Paint(buttonTextPaint).apply {
            color = if (isDPadPressed("UP")) Color.YELLOW else Color.WHITE
        }
        canvas.drawText("↑", centerX, centerY - size * 0.4f, arrowPaint)
        
        arrowPaint.color = if (isDPadPressed("DOWN")) Color.YELLOW else Color.WHITE
        canvas.drawText("↓", centerX, centerY + size * 0.4f, arrowPaint)
        
        arrowPaint.color = if (isDPadPressed("LEFT")) Color.YELLOW else Color.WHITE
        canvas.drawText("←", centerX - size * 0.4f, centerY, arrowPaint)
        
        arrowPaint.color = if (isDPadPressed("RIGHT")) Color.YELLOW else Color.WHITE
        canvas.drawText("→", centerX + size * 0.4f, centerY, arrowPaint)
    }
    
    private fun drawAnalogSticks(canvas: Canvas) {
        drawStick(canvas, leftStickRect, "LEFT")
        drawStick(canvas, rightStickRect, "RIGHT")
    }
    
    private fun drawStick(canvas: Canvas, rect: RectF, stickName: String) {
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val radius = rect.width() / 2f
        
        // Draw stick base
        canvas.drawCircle(centerX, centerY, radius, paint)
        canvas.drawCircle(centerX, centerY, radius, strokePaint)
        
        // Draw stick position
        val position = stickPositions[stickName] ?: PointF(0f, 0f)
        val stickX = centerX + position.x * radius * 0.8f
        val stickY = centerY + position.y * radius * 0.8f
        
        val stickPaint = Paint(paint).apply {
            color = Color.argb(200, 80, 80, 80)
        }
        
        val stickStrokePaint = Paint(strokePaint).apply {
            color = Color.argb(255, 120, 120, 120)
        }
        
        canvas.drawCircle(stickX, stickY, radius * 0.4f, stickPaint)
        canvas.drawCircle(stickX, stickY, radius * 0.4f, stickStrokePaint)
    }
    
    private fun drawFaceButtons(canvas: Canvas) {
        val centerX = faceButtonsRect.centerX()
        val centerY = faceButtonsRect.centerY()
        val buttonRadius = controlSize * 0.25f
        
        // Button layout: Y (top), B (right), A (bottom), X (left)
        val buttons = listOf(
            "Y" to PointF(centerX, centerY - buttonRadius * 2f),
            "B" to PointF(centerX + buttonRadius * 2f, centerY),
            "A" to PointF(centerX, centerY + buttonRadius * 2f),
            "X" to PointF(centerX - buttonRadius * 2f, centerY)
        )
        
        buttons.forEach { (name, position) ->
            val isPressed = buttonStates[name] ?: false
            val buttonPaint = Paint(paint).apply {
                color = if (isPressed) Color.argb(220, 90, 90, 90) else paint.color
            }
            
            canvas.drawCircle(position.x, position.y, buttonRadius, buttonPaint)
            canvas.drawCircle(position.x, position.y, buttonRadius, strokePaint)
            canvas.drawText(name, position.x, position.y + buttonRadius * 0.3f, buttonTextPaint)
        }
    }
    
    private fun drawShoulderButtons(canvas: Canvas) {
        val width = width.toFloat()
        val buttonWidth = controlSize * 0.8f
        val buttonHeight = controlSize * 0.3f
        
        // L1 button (top left)
        val l1Rect = RectF(
            padding,
            padding,
            padding + buttonWidth,
            padding + buttonHeight
        )
        
        // R1 button (top right) 
        val r1Rect = RectF(
            width - padding - buttonWidth,
            padding,
            width - padding,
            padding + buttonHeight
        )
        
        drawShoulderButton(canvas, l1Rect, "L1")
        drawShoulderButton(canvas, r1Rect, "R1")
    }
    
    private fun drawShoulderButton(canvas: Canvas, rect: RectF, buttonName: String) {
        val isPressed = buttonStates[buttonName] ?: false
        val buttonPaint = Paint(paint).apply {
            color = if (isPressed) Color.argb(220, 90, 90, 90) else paint.color
        }
        
        canvas.drawRoundRect(rect, controlSize * 0.1f, controlSize * 0.1f, buttonPaint)
        canvas.drawRoundRect(rect, controlSize * 0.1f, controlSize * 0.1f, strokePaint)
        
        val textY = rect.centerY() + controlSize * 0.1f
        canvas.drawText(buttonName, rect.centerX(), textY, buttonTextPaint)
    }
    
    private fun drawStartSelectButtons(canvas: Canvas) {
        val width = width.toFloat()
        val buttonWidth = controlSize * 0.6f
        val buttonHeight = controlSize * 0.25f
        val centerY = height.toFloat() - controlSize * 0.8f
        
        // START button (center-right)
        val startRect = RectF(
            width / 2f + padding,
            centerY - buttonHeight / 2f,
            width / 2f + padding + buttonWidth,
            centerY + buttonHeight / 2f
        )
        
        // SELECT button (center-left)
        val selectRect = RectF(
            width / 2f - padding - buttonWidth,
            centerY - buttonHeight / 2f,
            width / 2f - padding,
            centerY + buttonHeight / 2f
        )
        
        drawStartSelectButton(canvas, startRect, "START")
        drawStartSelectButton(canvas, selectRect, "SELECT")
    }
    
    private fun drawStartSelectButton(canvas: Canvas, rect: RectF, buttonName: String) {
        val isPressed = buttonStates[buttonName] ?: false
        val buttonPaint = Paint(paint).apply {
            color = if (isPressed) Color.argb(220, 90, 90, 90) else paint.color
        }
        
        canvas.drawRoundRect(rect, controlSize * 0.1f, controlSize * 0.1f, buttonPaint)
        canvas.drawRoundRect(rect, controlSize * 0.1f, controlSize * 0.1f, strokePaint)
        
        val textY = rect.centerY() + controlSize * 0.1f
        canvas.drawText(buttonName, rect.centerX(), textY, buttonTextPaint)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = false
        
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                handled = handleTouchStart(event)
            }
            
            MotionEvent.ACTION_MOVE -> {
                handled = handleTouchMove(event)
            }
            
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                handled = handleTouchEnd(event)
            }
        }
        
        invalidate() // Redraw controls
        return handled || super.onTouchEvent(event)
    }
    
    private fun handleTouchStart(event: MotionEvent): Boolean {
        var handled = false
        
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            val x = event.getX(i)
            val y = event.getY(i)
            
            when (getControlAt(x, y)) {
                "DPAD_UP" -> {
                    activeTouches[pointerId] = TouchRegion.DPAD_UP
                    pressButton("DPAD_UP")
                    handled = true
                }
                "DPAD_DOWN" -> {
                    activeTouches[pointerId] = TouchRegion.DPAD_DOWN
                    pressButton("DPAD_DOWN")
                    handled = true
                }
                "DPAD_LEFT" -> {
                    activeTouches[pointerId] = TouchRegion.DPAD_LEFT
                    pressButton("DPAD_LEFT")
                    handled = true
                }
                "DPAD_RIGHT" -> {
                    activeTouches[pointerId] = TouchRegion.DPAD_RIGHT
                    pressButton("DPAD_RIGHT")
                    handled = true
                }
                "LEFT_STICK" -> {
                    activeTouches[pointerId] = TouchRegion.LEFT_STICK
                    handled = true
                }
                "RIGHT_STICK" -> {
                    activeTouches[pointerId] = TouchRegion.RIGHT_STICK
                    handled = true
                }
                "FACE_BUTTON" -> {
                    activeTouches[pointerId] = TouchRegion.FACE_BUTTON
                    handleFaceButtonTouch(x, y)
                    handled = true
                }
                "L1" -> {
                    activeTouches[pointerId] = TouchRegion.L1
                    pressButton("L1")
                    handled = true
                }
                "R1" -> {
                    activeTouches[pointerId] = TouchRegion.R1
                    pressButton("R1")
                    handled = true
                }
                "START" -> {
                    activeTouches[pointerId] = TouchRegion.START
                    pressButton("START")
                    handled = true
                }
                "SELECT" -> {
                    activeTouches[pointerId] = TouchRegion.SELECT
                    pressButton("SELECT")
                    handled = true
                }
                null -> {
                    // Touch not on any control
                }
            }
        }
        
        return handled
    }
    
    private fun handleTouchMove(event: MotionEvent): Boolean {
        var handled = false
        
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            val x = event.getX(i)
            val y = event.getY(i)
            
            val touchRegion = activeTouches[pointerId] ?: continue
            
            when (touchRegion) {
                TouchRegion.LEFT_STICK -> {
                    updateStickPosition("LEFT", x, y, leftStickRect)
                    handled = true
                }
                TouchRegion.RIGHT_STICK -> {
                    updateStickPosition("RIGHT", x, y, rightStickRect)
                    handled = true
                }
                else -> {
                    // Other controls don't need move handling
                }
            }
        }
        
        return handled
    }
    
    private fun handleTouchEnd(event: MotionEvent): Boolean {
        var handled = false
        
        // Handle all touches that ended
        for (i in 0 until event.pointerCount) {
            if (event.actionIndex != i && event.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                continue
            }
            
            val pointerId = event.getPointerId(i)
            val touchRegion = activeTouches.remove(pointerId) ?: continue
            
            when (touchRegion) {
                TouchRegion.DPAD_UP -> releaseButton("DPAD_UP")
                TouchRegion.DPAD_DOWN -> releaseButton("DPAD_DOWN")
                TouchRegion.DPAD_LEFT -> releaseButton("DPAD_LEFT")
                TouchRegion.DPAD_RIGHT -> releaseButton("DPAD_RIGHT")
                TouchRegion.FACE_BUTTON -> releaseFaceButton()
                TouchRegion.L1 -> releaseButton("L1")
                TouchRegion.R1 -> releaseButton("R1")
                TouchRegion.START -> releaseButton("START")
                TouchRegion.SELECT -> releaseButton("SELECT")
                TouchRegion.LEFT_STICK -> {
                    stickPositions["LEFT"] = PointF(0f, 0f)
                    onStickMove?.invoke("LEFT", 0f, 0f)
                }
                TouchRegion.RIGHT_STICK -> {
                    stickPositions["RIGHT"] = PointF(0f, 0f)
                    onStickMove?.invoke("RIGHT", 0f, 0f)
                }
            }
            
            handled = true
        }
        
        return handled
    }
    
    private fun getControlAt(x: Float, y: Float): String? {
        return when {
            dpadRect.contains(x, y) -> {
                // Determine which D-pad direction
                val centerX = dpadRect.centerX()
                val centerY = dpadRect.centerY()
                val deltaX = x - centerX
                val deltaY = y - centerY
                
                when {
                    abs(deltaX) > abs(deltaY) -> {
                        if (deltaX > 0) "DPAD_RIGHT" else "DPAD_LEFT"
                    }
                    else -> {
                        if (deltaY > 0) "DPAD_DOWN" else "DPAD_UP"
                    }
                }
            }
            
            leftStickRect.contains(x, y) -> "LEFT_STICK"
            rightStickRect.contains(x, y) -> "RIGHT_STICK"
            faceButtonsRect.contains(x, y) -> "FACE_BUTTON"
            
            y < controlSize * 0.6f -> {
                // Check shoulder buttons
                val buttonWidth = controlSize * 0.8f
                if (x < controlSize + padding + buttonWidth) "L1" else "R1"
            }
            
            else -> {
                // Check start/select buttons
                val buttonWidth = controlSize * 0.6f
                val centerY = height.toFloat() - controlSize * 0.8f
                
                if (abs(y - centerY) < controlSize * 0.4f) {
                    if (x > width / 2f) "START" else "SELECT"
                } else {
                    null
                }
            }
        }
    }
    
    private fun handleFaceButtonTouch(x: Float, y: Float) {
        val centerX = faceButtonsRect.centerX()
        val centerY = faceButtonsRect.centerY()
        val buttonRadius = controlSize * 0.25f
        val distance = sqrt((x - centerX).pow(2) + (y - centerY).pow(2))
        
        if (distance <= buttonRadius * 3f) {
            val buttonName = when {
                y < centerY - buttonRadius && abs(x - centerX) < buttonRadius * 2f -> "Y"
                x > centerX + buttonRadius && abs(y - centerY) < buttonRadius * 2f -> "B"
                y > centerY + buttonRadius && abs(x - centerX) < buttonRadius * 2f -> "A"
                x < centerX - buttonRadius && abs(y - centerY) < buttonRadius * 2f -> "X"
                else -> "A" // Default fallback
            }
            
            pressButton(buttonName)
        }
    }
    
    private fun releaseFaceButton() {
        // Release all face buttons
        listOf("A", "B", "X", "Y").forEach { buttonName ->
            if (buttonStates[buttonName] == true) {
                releaseButton(buttonName)
            }
        }
    }
    
    private fun updateStickPosition(stickName: String, touchX: Float, touchY: Float, stickRect: RectF) {
        val centerX = stickRect.centerX()
        val centerY = stickRect.centerY()
        val radius = stickRect.width() / 2f
        
        val deltaX = touchX - centerX
        val deltaY = touchY - centerY
        val distance = sqrt(deltaX.pow(2) + deltaY.pow(2))
        
        val normalizedX = if (distance > 0) deltaX / radius else 0f
        val normalizedY = if (distance > 0) deltaY / radius else 0f
        
        // Clamp to stick boundary
        val clampedX = max(-1f, min(1f, normalizedX))
        val clampedY = max(-1f, min(1f, normalizedY))
        
        stickPositions[stickName] = PointF(clampedX, clampedY)
        onStickMove?.invoke(stickName, clampedX, clampedY)
    }
    
    private fun pressButton(buttonName: String) {
        if (buttonStates[buttonName] == true) return // Already pressed
        
        buttonStates[buttonName] = true
        onButtonPressed?.invoke(buttonName)
    }
    
    private fun releaseButton(buttonName: String) {
        if (buttonStates[buttonName] == false) return // Already released
        
        buttonStates[buttonName] = false
        onButtonReleased?.invoke(buttonName)
    }
    
    private fun isDPadPressed(direction: String): Boolean {
        return when (direction) {
            "UP" -> buttonStates["DPAD_UP"] == true
            "DOWN" -> buttonStates["DPAD_DOWN"] == true
            "LEFT" -> buttonStates["DPAD_LEFT"] == true
            "RIGHT" -> buttonStates["DPAD_RIGHT"] == true
            else -> false
        }
    }
    
    private fun isAnyDPadPressed(): Boolean {
        return listOf("DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT")
            .any { buttonStates[it] == true }
    }
    
    // Public API for callbacks
    fun setOnButtonPressedListener(listener: (String) -> Unit) {
        onButtonPressed = listener
    }
    
    fun setOnButtonReleasedListener(listener: (String) -> Unit) {
        onButtonReleased = listener
    }
    
    fun setOnStickMoveListener(listener: (String, Float, Float) -> Unit) {
        onStickMove = listener
    }
    
    fun setAlpha(alpha: Float) {
        paint.alpha = (alpha * 255).toInt()
        strokePaint.alpha = (alpha * 255).toInt()
        textPaint.alpha = (alpha * 255).toInt()
        buttonTextPaint.alpha = (alpha * 255).toInt()
        invalidate()
    }
    
    enum class TouchRegion {
        DPAD_UP,
        DPAD_DOWN,
        DPAD_LEFT,
        DPAD_RIGHT,
        LEFT_STICK,
        RIGHT_STICK,
        FACE_BUTTON,
        L1,
        R1,
        START,
        SELECT
    }
}