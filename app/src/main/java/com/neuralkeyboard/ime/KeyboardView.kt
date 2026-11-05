package com.neuralkeyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * All keys drawn by one View (like Samsung Keyboard / Gboard). Compared with one
 * Android view per key this redraws only what changed, never inflates views while
 * typing, and handles fast multi-finger typing:
 *  - a key fires on release, or immediately when the next finger lands (roll-over)
 *  - touches in the gaps go to the nearest key
 *  - long-press types the key's corner symbol, delete auto-repeats
 */
class KeyboardView(context: Context, private val onKey: (Key) -> Unit) : View(context) {

    var hapticsEnabled = true

    private var rows: List<KeyRow> = emptyList()
    private var shift = ShiftState.OFF
    private var enterLabel = EnterAction.ENTER

    private class KeyBox(val key: Key, val rect: RectF)

    private var boxes: List<KeyBox> = emptyList()

    private class Pointer(val box: KeyBox) {
        var fired = false
        var longPress: Runnable? = null
    }

    private val pointers = SparseArray<Pointer>()
    private val handlerMain = Handler(Looper.getMainLooper())

    private val rowHeight = dp(52f)
    private val gapX = dp(2.5f)
    private val gapY = dp(4f)
    private val radius = dp(7f)

    private val keyPaint = fill(R.color.key_background)
    private val keyPressedPaint = fill(R.color.key_background_pressed)
    private val specialPaint = fill(R.color.key_special_background)
    private val specialPressedPaint = fill(R.color.key_special_pressed)
    private val accentPaint = fill(R.color.accent)
    private val labelPaint = text(R.color.key_text, 22f)
    private val smallLabelPaint = text(R.color.key_text, 15f)
    private val hintPaint = text(R.color.suggestion_text, 10.5f).apply { alpha = 170 }
    private val iconSize = dp(23f)
    private val icons = HashMap<Int, Drawable>()

    /** Updates the keys. Cheap when nothing changed (e.g. most key presses). */
    fun setKeys(rows: List<KeyRow>, shift: ShiftState, enterLabel: String) {
        if (rows == this.rows && shift == this.shift && enterLabel == this.enterLabel) return
        val heightChanged = totalHeight(rows) != totalHeight(this.rows)
        this.rows = rows
        this.shift = shift
        this.enterLabel = enterLabel
        // Fingers still down keep their (old) key; only the drawing changes.
        if (heightChanged) requestLayout()
        layoutKeys()
        invalidate()
    }

    private fun totalHeight(rows: List<KeyRow>) = rows.sumOf { (it.height * rowHeight).toDouble() }.toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (totalHeight(rows) + paddingTop + paddingBottom).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        val width = (width - paddingLeft - paddingRight).toFloat()
        if (width <= 0) return
        val out = ArrayList<KeyBox>()
        var y = paddingTop.toFloat()
        for (row in rows) {
            val h = row.height * rowHeight
            val unit = width / row.keys.sumOf { it.weight.toDouble() }.toFloat()
            var x = paddingLeft.toFloat()
            for (key in row.keys) {
                val w = key.weight * unit
                if (key.type != Key.Type.SPACER) {
                    out.add(KeyBox(key, RectF(x + gapX, y + gapY, x + w - gapX, y + h - gapY)))
                }
                x += w
            }
            y += h
        }
        boxes = out
    }

    override fun onDraw(canvas: Canvas) {
        val pressed = HashSet<KeyBox>()
        for (i in 0 until pointers.size()) pressed.add(pointers.valueAt(i).box)
        val pressedKeys = pressed.map { it.key }.toSet()
        for (box in boxes) drawKey(canvas, box, box in pressed || box.key in pressedKeys)
    }

    private fun drawKey(canvas: Canvas, box: KeyBox, pressed: Boolean) {
        val key = box.key
        val r = box.rect
        val shiftActive = key.type == Key.Type.SHIFT && shift != ShiftState.OFF
        val bg = when {
            shiftActive -> accentPaint
            key.isSpecial -> if (pressed) specialPressedPaint else specialPaint
            else -> if (pressed) keyPressedPaint else keyPaint
        }
        canvas.drawRoundRect(r, radius, radius, bg)

        val icon = when (key.type) {
            Key.Type.SHIFT -> when (shift) {
                ShiftState.OFF -> R.drawable.ic_key_shift
                ShiftState.ONCE -> R.drawable.ic_key_shift_on
                ShiftState.LOCKED -> R.drawable.ic_key_caps_lock
            }
            Key.Type.DELETE -> R.drawable.ic_key_backspace
            Key.Type.LANGUAGE -> R.drawable.ic_key_globe
            Key.Type.ENTER -> when (enterLabel) {
                EnterAction.SEARCH -> R.drawable.ic_key_search
                EnterAction.SEND -> R.drawable.ic_key_send
                EnterAction.NEXT, EnterAction.GO -> R.drawable.ic_key_next
                EnterAction.DONE -> R.drawable.ic_key_done
                else -> R.drawable.ic_key_enter
            }
            else -> 0
        }
        if (icon != 0) {
            val d = icons.getOrPut(icon) { context.getDrawable(icon)!!.mutate() }
            d.setTint(if (shiftActive) Color.WHITE else labelPaint.color)
            val half = iconSize / 2
            d.setBounds((r.centerX() - half).toInt(), (r.centerY() - half).toInt(), (r.centerX() + half).toInt(), (r.centerY() + half).toInt())
            d.draw(canvas)
            return
        }
        val label = key.label
        val paint = if (key.type == Key.Type.CHAR && label.length <= 2) labelPaint else smallLabelPaint
        val color = paint.color
        if (shiftActive) paint.color = Color.WHITE
        val baseline = r.centerY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(label, r.centerX(), baseline, paint)
        paint.color = color

        key.hint?.let { hint ->
            canvas.drawText(hint, r.right - dp(7f), r.top - hintPaint.ascent() + dp(2f), hintPaint)
        }
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Roll-over: a new finger landing commits keys still held by other fingers.
                val held = (0 until pointers.size()).map { pointers.valueAt(it) }
                held.forEach(::fire)
                val idx = event.actionIndex
                val box = findKey(event.getX(idx), event.getY(idx)) ?: return true
                val p = Pointer(box)
                pointers.put(event.getPointerId(idx), p)
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when {
                    box.key.type == Key.Type.DELETE -> {
                        p.fired = true
                        onKey(box.key)
                        scheduleRepeat(p)
                    }
                    box.key.hint != null -> {
                        val r = Runnable {
                            if (!p.fired) {
                                p.fired = true
                                onKey(Key(Key.Type.CHAR, box.key.hint))
                                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                        p.longPress = r
                        handlerMain.postDelayed(r, LONG_PRESS_MS)
                    }
                }
                invalidate(box)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                val p = pointers.get(id) ?: return true
                fire(p)
                release(id, p)
            }
            MotionEvent.ACTION_CANCEL -> cancelAllPointers()
        }
        return true
    }

    private fun fire(p: Pointer) {
        if (p.fired) return
        p.fired = true
        p.longPress?.let { handlerMain.removeCallbacks(it) }
        onKey(p.box.key)
    }

    private fun release(id: Int, p: Pointer) {
        p.longPress?.let { handlerMain.removeCallbacks(it) }
        pointers.remove(id)
        invalidate(p.box)
    }

    private fun scheduleRepeat(p: Pointer) {
        val repeat = object : Runnable {
            override fun run() {
                if (pointers.indexOfValue(p) < 0) return
                onKey(p.box.key)
                handlerMain.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        p.longPress = repeat
        handlerMain.postDelayed(repeat, REPEAT_DELAY_MS)
    }

    private fun cancelAllPointers() {
        for (i in 0 until pointers.size()) pointers.valueAt(i).longPress?.let { handlerMain.removeCallbacks(it) }
        pointers.clear()
        invalidate()
    }

    /** Key under the finger, or the nearest key when the touch lands in a gap. */
    private fun findKey(x: Float, y: Float): KeyBox? {
        boxes.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        return boxes.minByOrNull { b ->
            val dx = maxOf(b.rect.left - x, 0f, x - b.rect.right)
            val dy = maxOf(b.rect.top - y, 0f, y - b.rect.bottom)
            hypot(dx, dy)
        }
    }

    private fun invalidate(box: KeyBox) {
        val r = box.rect
        invalidate(r.left.toInt() - 1, r.top.toInt() - 1, r.right.toInt() + 1, r.bottom.toInt() + 1)
    }

    override fun onDetachedFromWindow() {
        cancelAllPointers()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ helpers

    private fun fill(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(colorRes) }

    private fun text(colorRes: Int, sp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(colorRes)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        typeface = Typeface.DEFAULT
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        private const val LONG_PRESS_MS = 350L
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L
    }
}
