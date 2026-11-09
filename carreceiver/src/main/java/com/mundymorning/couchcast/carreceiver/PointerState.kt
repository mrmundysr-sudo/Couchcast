package com.mundymorning.couchcast.carreceiver

/**
 * The on-screen pointer a phone touchpad moves. Deltas arrive as fractions of the touchpad, so the same swipe feels the same
 * on any screen size.
 */
class PointerState(width: Float = 1f, height: Float = 1f) {
    var width = width; private set
    var height = height; private set
    var x = width / 2; private set
    var y = height / 2; private set
    var visible = false; private set
    var dragging = false

    fun resize(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        x = (x / width * w).coerceIn(0f, w - 1); y = (y / height * h).coerceIn(0f, h - 1)
        width = w; height = h
    }

    fun move(fx: Float, fy: Float) {
        x = (x + fx * width * GAIN).coerceIn(0f, width - 1)
        y = (y + fy * height * GAIN).coerceIn(0f, height - 1)
        visible = true
    }

    fun show() { visible = true }
    fun hide() { visible = false; dragging = false }

    companion object { const val GAIN = 1.2f }
}
