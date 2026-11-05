package com.neuralkeyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * Effect A: the enlarged character that pops up above the finger (Samsung style).
 *
 * Drawn by a transparent view that covers the whole keyboard window, so the bubble
 * of a top-row key can extend over the suggestion bar. It never takes touches.
 */
class KeyPreviewOverlay(context: Context) : View(context) {

    private var label: String? = null
    private val bubble = RectF()
    private val hideRunnable = Runnable {
        label = null
        invalidate()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.preview_background)
        setShadowLayer(dp(6f), 0f, dp(2f), 0x44000000)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_text)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30f, resources.displayMetrics)
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // (The shadow is drawn on Android 9+; older versions show a flat bubble.)
    }

    /** Shows [label] above [keyRect], which is in [source]'s coordinates. */
    fun show(label: String, keyRect: RectF, source: View) {
        removeCallbacks(hideRunnable)
        val here = IntArray(2).also(::getLocationInWindow)
        val there = IntArray(2).also(source::getLocationInWindow)
        val dx = (there[0] - here[0]).toFloat()
        val dy = (there[1] - here[1]).toFloat()

        val w = maxOf(keyRect.width() + dp(14f), dp(48f))
        val h = keyRect.height() + dp(14f)
        var left = keyRect.centerX() + dx - w / 2
        left = left.coerceIn(dp(2f), maxOf(dp(2f), width - w - dp(2f)))
        // Sit just above the key; if there is no room (top row), go as high as possible.
        val bottom = keyRect.top + dy + dp(4f)
        val top = maxOf(dp(1f), bottom - h)
        bubble.set(left, top, left + w, top + h)
        this.label = label
        invalidate()
    }

    /** Hides after a short delay so quick taps are still visible. */
    fun hide() {
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    /**
     * Take no space of our own: report 0 unless the parent gives an exact size.
     * Otherwise "match parent" inside the input window (which has no fixed height)
     * is measured as the whole screen and the keyboard grows to full height.
     * FrameLayout re-measures us with the keyboard's real size afterwards.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val l = label ?: return
        val r = dp(10f)
        canvas.drawRoundRect(bubble, r, r, fill)
        val baseline = bubble.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(l, bubble.centerX(), baseline, text)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        private const val HIDE_DELAY_MS = 70L
    }
}
