package com.gamextra4u.fexdroid.input

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Maps touch screen input to mouse/keyboard events for touch devices
 * Supports tap gestures, drag movements, pinch-to-zoom, and virtual mouse cursor
 */
class TouchInputMapper(
    private val context: Context
) {
    
    private var config = TouchMappingConfig()
    private var isMouseMode = true
    private var gesturesEnabled = true
    
    // Touch tracking for gesture detection
    private var isTracking = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTouchTime = 0L
    private var touchCount = 0
    
    // Gesture detection
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        private var lastMoveTime = 0L
        
        override fun onDown(e: MotionEvent): Boolean {
            startX = e.x
            startY = e.y
            lastX = e.x
            lastY = e.y
            lastTouchTime = System.currentTimeMillis()
            
            sendTouchEvent(TouchInputEvent.TouchStart(
                x = e.x,
                y = e.y,
                touchCount = e.pointerCount,
                timestamp = System.currentTimeMillis()
            ))
            
            return true
        }
        
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            sendTouchEvent(TouchInputEvent.Tap(
                x = e.x,
                y = e.y,
                tapCount = 1,
                timestamp = System.currentTimeMillis()
            ))
            
            Log.d(TAG, "Single tap at ($e.x, $e.y)")
            return true
        }
        
        override fun onDoubleTap(e: MotionEvent): Boolean {
            sendTouchEvent(TouchInputEvent.DoubleTap(
                x = e.x,
                y = e.y,
                timestamp = System.currentTimeMillis()
            ))
            
            Log.d(TAG, "Double tap at ($e.x, $e.y)")
            return true
        }
        
        override fun onLongPress(e: MotionEvent) {
            sendTouchEvent(TouchInputEvent.LongPress(
                x = e.x,
                y = e.y,
                timestamp = System.currentTimeMillis()
            ))
            
            Log.d(TAG, "Long press at ($e.x, $e.y)")
        }
        
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e2.pointerCount == 1 && isMouseMode) {
                // Mouse-like drag with single finger
                val deltaX = e2.x - lastX
                val deltaY = e2.y - lastY
                
                sendTouchEvent(TouchInputEvent.Drag(
                    startX = startX,
                    startY = startY,
                    currentX = e2.x,
                    currentY = e2.y,
                    deltaX = deltaX,
                    deltaY = deltaY,
                    touchCount = e2.pointerCount,
                    timestamp = System.currentTimeMillis()
                ))
                
                lastX = e2.x
                lastY = e2.y
                
                Log.d(TAG, "Drag: ($deltaX, $deltaY)")
                return true
            } else if (e2.pointerCount == 2) {
                // Two-finger scroll gesture
                sendTouchEvent(TouchInputEvent.TwoFingerScroll(
                    deltaX = distanceX,
                    deltaY = distanceY,
                    timestamp = System.currentTimeMillis()
                ))
                
                Log.d(TAG, "Two-finger scroll: ($distanceX, $distanceY)")
                return true
            }
            return false
        }
        
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val direction = when {
                abs(velocityX) > abs(velocityY) -> {
                    if (velocityX > 0) "RIGHT" else "LEFT"
                }
                else -> {
                    if (velocityY > 0) "DOWN" else "UP"
                }
            }
            
            sendTouchEvent(TouchInputEvent.Fling(
                startX = e1?.x ?: 0f,
                startY = e1?.y ?: 0f,
                endX = e2.x,
                endY = e2.y,
                velocityX = velocityX,
                velocityY = velocityY,
                direction = direction,
                timestamp = System.currentTimeMillis()
            ))
            
            Log.d(TAG, "Fling $direction with velocity ($velocityX, $velocityY)")
            return true
        }
    })
    
    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var scaleFactor = 1.0f
        
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaleFactor = 1.0f
            return true
        }
        
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            
            sendTouchEvent(TouchInputEvent.Pinch(
                centerX = detector.focusX,
                centerY = detector.focusY,
                scaleFactor = detector.scaleFactor,
                cumulativeScale = scaleFactor,
                timestamp = System.currentTimeMillis()
            ))
            
            Log.d(TAG, "Pinch scale: ${detector.scaleFactor}, cumulative: $scaleFactor")
            return true
        }
        
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            sendTouchEvent(TouchInputEvent.PinchEnd(
                centerX = detector.focusX,
                centerY = detector.focusY,
                finalScale = scaleFactor,
                timestamp = System.currentTimeMillis()
            ))
            
            Log.d(TAG, "Pinch ended, final scale: $scaleFactor")
        }
    })

    /**
     * Configure the touch mapper with custom settings
     */
    fun configure(config: TouchMappingConfig) {
        this.config = config
        this.isMouseMode = config.mouseMode
        this.gesturesEnabled = config.gesturesEnabled
        Log.d(TAG, "Touch mappings configured: $config")
    }

    /**
     * Process touch screen events
     */
    fun processTouchEvent(event: MotionEvent): Boolean {
        var handled = false
        
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                isTracking = true
                touchCount = event.pointerCount
            }
            
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 1) {
                    isTracking = false
                }
                touchCount = event.pointerCount
                
                sendTouchEvent(TouchInputEvent.TouchEnd(
                    x = event.x,
                    y = event.y,
                    finalTouchCount = event.pointerCount,
                    timestamp = System.currentTimeMillis()
                ))
            }
            
            MotionEvent.ACTION_CANCEL -> {
                isTracking = false
                touchCount = 0
            }
        }
        
        // Process through gesture detectors
        if (gesturesEnabled) {
            handled = gestureDetector.onTouchEvent(event)
            handled = scaleGestureDetector.onTouchEvent(event) || handled
        }
        
        // Always process raw touch data for mouse simulation
        if (isMouseMode) {
            handled = processMouseSimulation(event) || handled
        }
        
        return handled
    }

    private fun processMouseSimulation(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    // Single finger = mouse move
                    sendTouchEvent(TouchInputEvent.MouseMove(
                        x = event.x,
                        y = event.y,
                        deltaX = event.x - lastX,
                        deltaY = event.y - lastY,
                        timestamp = System.currentTimeMillis()
                    ))
                    
                    lastX = event.x
                    lastY = event.y
                    true
                } else {
                    false
                }
            }
            
            MotionEvent.ACTION_DOWN -> {
                // Single tap = left click
                if (event.pointerCount == 1) {
                    sendTouchEvent(TouchInputEvent.MouseClick(
                        button = "LEFT",
                        x = event.x,
                        y = event.y,
                        clickCount = 1,
                        timestamp = System.currentTimeMillis()
                    ))
                    true
                } else {
                    false
                }
            }
            
            MotionEvent.ACTION_UP -> {
                if (event.pointerCount == 0) {
                    sendTouchEvent(TouchInputEvent.MouseRelease(
                        button = "LEFT",
                        timestamp = System.currentTimeMillis()
                    ))
                    true
                } else {
                    false
                }
            }
            
            MotionEvent.ACTION_BUTTON_PRESS -> {
                // Right click with multi-finger
                when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> {
                        sendTouchEvent(TouchInputEvent.MouseClick(
                            button = "RIGHT",
                            x = event.x,
                            y = event.y,
                            clickCount = 1,
                            timestamp = System.currentTimeMillis()
                        ))
                        true
                    }
                    MotionEvent.BUTTON_TERTIARY -> {
                        sendTouchEvent(TouchInputEvent.MouseClick(
                            button = "MIDDLE",
                            x = event.x,
                            y = event.y,
                            clickCount = 1,
                            timestamp = System.currentTimeMillis()
                        ))
                        true
                    }
                    else -> false
                }
            }
            
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> {
                        sendTouchEvent(TouchInputEvent.MouseRelease(
                            button = "RIGHT",
                            timestamp = System.currentTimeMillis()
                        ))
                        true
                    }
                    MotionEvent.BUTTON_TERTIARY -> {
                        sendTouchEvent(TouchInputEvent.MouseRelease(
                            button = "MIDDLE",
                            timestamp = System.currentTimeMillis()
                        ))
                        true
                    }
                    else -> false
                }
            }
            
            else -> false
        }
    }

    private fun sendTouchEvent(event: TouchInputEvent) {
        // Log the event for debugging
        Log.d(TAG, "Touch event: $event")
        
        // In a real implementation, this would send the mapped input
        // to the FEX emulation environment
        // For now, we just log it
    }

    companion object {
        private const val TAG = "TouchInputMapper"
    }
}

/**
 * Sealed hierarchy for touch input events
 */
sealed class TouchInputEvent(open val timestamp: Long) {
    data class TouchStart(
        val x: Float,
        val y: Float,
        val touchCount: Int,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class TouchEnd(
        val x: Float,
        val y: Float,
        val finalTouchCount: Int,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class Tap(
        val x: Float,
        val y: Float,
        val tapCount: Int,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class DoubleTap(
        val x: Float,
        val y: Float,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class LongPress(
        val x: Float,
        val y: Float,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class Drag(
        val startX: Float,
        val startY: Float,
        val currentX: Float,
        val currentY: Float,
        val deltaX: Float,
        val deltaY: Float,
        val touchCount: Int,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class Fling(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val velocityX: Float,
        val velocityY: Float,
        val direction: String,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class MouseMove(
        val x: Float,
        val y: Float,
        val deltaX: Float,
        val deltaY: Float,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class MouseClick(
        val button: String,
        val x: Float,
        val y: Float,
        val clickCount: Int,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class MouseRelease(
        val button: String,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class Pinch(
        val centerX: Float,
        val centerY: Float,
        val scaleFactor: Float,
        val cumulativeScale: Float,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class PinchEnd(
        val centerX: Float,
        val centerY: Float,
        val finalScale: Float,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
    
    data class TwoFingerScroll(
        val deltaX: Float,
        val deltaY: Float,
        override val timestamp: Long
    ) : TouchInputEvent(timestamp)
}