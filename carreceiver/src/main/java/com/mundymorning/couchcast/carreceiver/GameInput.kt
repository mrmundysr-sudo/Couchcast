package com.mundymorning.couchcast.carreceiver

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView

/** Turns phone-controller gestures into real touches and key presses on the game's WebView. */
class GameInput(
    private val view: WebView,
    private val pointer: PointerState,
    private val onChange: () -> Unit,
) : GameHost {
    private var downTime = 0L

    override fun handle(e: InputEvent): Boolean {
        when (e.type) {
            "move" -> {
                pointer.move(e.dx, e.dy)
                if (pointer.dragging) touch(MotionEvent.ACTION_MOVE)
            }
            "tap" -> {
                pointer.show()
                touch(MotionEvent.ACTION_DOWN)
                view.postDelayed({ touch(MotionEvent.ACTION_UP) }, 60)
            }
            "down" -> {
                pointer.show()
                pointer.dragging = true
                touch(MotionEvent.ACTION_DOWN)
            }
            "up" -> if (pointer.dragging) {
                pointer.dragging = false
                touch(MotionEvent.ACTION_UP)
            }
            "key" -> key(e.key)
            else -> return false
        }
        onChange()
        return true
    }

    private fun touch(action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val ev = MotionEvent.obtain(downTime, now, action, pointer.x, pointer.y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun key(name: String?) {
        val code = when (name) {
            "Up" -> KeyEvent.KEYCODE_DPAD_UP
            "Down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "Left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "Right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "Enter" -> KeyEvent.KEYCODE_ENTER
            else -> return
        }
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
