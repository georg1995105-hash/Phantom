package xyz.wraithnet.phantom.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Quiet mint scanner guides: no duplicate ZXing labels or bright red laser. */
class QrScanOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(145, 5, 13, 18) }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(142, 226, 192)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val scan = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(155, 142, 226, 192)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private var phase = 0.38f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0.12f, 0.88f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width * .78f, height * .40f)
        val left = (width - side) / 2f
        val top = (height - side) / 2f - (8f * density)
        val right = left + side
        val bottom = top + side
        canvas.drawRect(0f, 0f, width.toFloat(), top, mask)
        canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), mask)
        canvas.drawRect(0f, top, left, bottom, mask)
        canvas.drawRect(right, top, width.toFloat(), bottom, mask)

        val edge = 25f * density
        val inset = 3f * density
        val l = left + inset
        val r = right - inset
        val t = top + inset
        val b = bottom - inset
        // Four rounded open corners
        canvas.drawLine(l, t + edge, l, t, guide)
        canvas.drawLine(l, t, l + edge, t, guide)
        canvas.drawLine(r - edge, t, r, t, guide)
        canvas.drawLine(r, t, r, t + edge, guide)
        canvas.drawLine(l, b - edge, l, b, guide)
        canvas.drawLine(l, b, l + edge, b, guide)
        canvas.drawLine(r - edge, b, r, b, guide)
        canvas.drawLine(r, b - edge, r, b, guide)

        val y = top + side * phase
        canvas.drawLine(left + 22f * density, y, right - 22f * density, y, scan)
    }
}
