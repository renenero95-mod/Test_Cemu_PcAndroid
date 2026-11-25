package info.cemu.cemu.emulation

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import info.cemu.cemu.nativeinterface.NativeInput

class CanvasOnTouchListener(val isTV: Boolean) : View.OnTouchListener {
    private var currentPointerId: Int = -1
    private var lastX: Int = 0
    private var lastY: Int = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // a new gesture always starts here, even if a previous one was never closed
                // deliver the events of this gesture as soon as they are available instead of batching them per frame
                v.requestUnbufferedDispatch(event)
                return startTracking(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (currentPointerId != -1) {
                    return false
                }
                return startTracking(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                if (event.getPointerId(pointerIndex) != currentPointerId) {
                    return false
                }
                currentPointerId = -1
                NativeInput.onTouchUp(event.getX(pointerIndex).toInt(), event.getY(pointerIndex).toInt(), isTV)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(currentPointerId)
                if (pointerIndex == -1) {
                    return false
                }
                lastX = event.getX(pointerIndex).toInt()
                lastY = event.getY(pointerIndex).toInt()
                NativeInput.onTouchMove(lastX, lastY, isTV)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // the system took over the gesture (notification shade, system gestures...): release the touch
                if (currentPointerId == -1) {
                    return false
                }
                currentPointerId = -1
                NativeInput.onTouchUp(lastX, lastY, isTV)
                return true
            }
        }
        return false
    }

    private fun startTracking(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        currentPointerId = event.getPointerId(pointerIndex)
        lastX = event.getX(pointerIndex).toInt()
        lastY = event.getY(pointerIndex).toInt()
        NativeInput.onTouchDown(lastX, lastY, isTV)
        return true
    }
}
