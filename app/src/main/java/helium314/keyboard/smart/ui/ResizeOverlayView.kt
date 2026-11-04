// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.smart.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.settings.findIndexOfDefaultSetting
import helium314.keyboard.latin.utils.FoldableUtils
import helium314.keyboard.latin.utils.prefs
import kotlin.math.abs

/**
 * Resize mode: the keyboard stays at its place, dimmed, with a thin frame and drag handles.
 * Top handle changes the height, side handles the width, the center button moves the keyboard up.
 * "Сброс" and "Готово" are anchored to the top strip instead of hiding the keys.
 */
class ResizeOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    var ime: LatinIME? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private val controls = LinearLayout(context)
    private val moveButton = ImageView(context)

    private enum class Drag { NONE, TOP, LEFT, RIGHT, MOVE }
    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var startHeightScale = 1f
    private var startSideScale = 0f
    private var startBottomScale = 0f
    private var startKeyboardHeight = 1
    private var lastApplied = 0L

    init {
        visibility = GONE
        setWillNotDraw(false)
        isClickable = true
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private fun buildControls() {
        controls.removeAllViews()
        val reset = SvUi.chip(context, context.getString(R.string.sv_resize_reset)).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(SvUi.icon(context, R.drawable.ic_sv_reset), null, null, null)
            compoundDrawablePadding = SvUi.dp(context, 6)
            setOnClickListener { SvUi.haptic(it); reset() }
        }
        val done = SvUi.chip(context, context.getString(R.string.sv_resize_done), filled = true).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(SvUi.icon(context, R.drawable.ic_sv_check, SvUi.onAccent()), null, null, null)
            compoundDrawablePadding = SvUi.dp(context, 6)
            setOnClickListener { SvUi.haptic(it); SmartUi.finishResize() }
        }
        val size = SvUi.dp(context, 40)
        moveButton.setImageDrawable(SvUi.icon(context, R.drawable.ic_sv_move, SvUi.onAccent()))
        moveButton.scaleType = ImageView.ScaleType.CENTER
        moveButton.background = SvUi.rounded(SvUi.accent(), size / 2f)
        moveButton.contentDescription = context.getString(R.string.sv_resize_move)
        val m = SvUi.dp(context, 10)
        controls.addView(reset)
        controls.addView(moveButton, LinearLayout.LayoutParams(size, size).apply { leftMargin = m; rightMargin = m })
        controls.addView(done)
    }

    fun show() {
        buildControls()
        visibility = VISIBLE
        bringToFront()
        invalidate()
    }

    fun hide() {
        visibility = GONE
        drag = Drag.NONE
    }

    // ---------- prefs ----------

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val folded get() = FoldableUtils.isFolded
    private val split get() = Settings.getValues()?.mIsSplitKeyboardEnabled == true
    private fun heightKey() = createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, findIndexOfDefaultSetting(landscape, folded), 2)
    private fun bottomKey() = createPrefKeyForBooleanSettings(Settings.PREF_BOTTOM_PADDING_SCALE_PREFIX, findIndexOfDefaultSetting(landscape, folded), 2)
    private fun sideKey() = createPrefKeyForBooleanSettings(Settings.PREF_SIDE_PADDING_SCALE_PREFIX, findIndexOfDefaultSetting(landscape, split, folded), 3)

    private fun heightScale() = Settings.readHeightScale(context.prefs(), landscape, folded)
    private fun bottomScale() = Settings.readBottomPaddingScale(context.prefs(), landscape, folded)
    private fun sideScale() = Settings.readSidePaddingScale(context.prefs(), landscape, split, folded)

    private fun apply(height: Float? = null, side: Float? = null, bottom: Float? = null, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastApplied < 40) return
        lastApplied = now
        context.prefs().edit {
            height?.let { putFloat(heightKey(), it) }
            side?.let { putFloat(sideKey(), it) }
            bottom?.let { putFloat(bottomKey(), it) }
        }
        val settings = Settings.getInstance()
        val current = settings.current
        settings.loadSettings(context, current.mLocale, current.mInputAttributes)
        KeyboardSwitcher.getInstance().reloadKeyboard()
        requestLayout()
        invalidate()
    }

    private fun reset() {
        val index2 = findIndexOfDefaultSetting(landscape, folded)
        val index3 = findIndexOfDefaultSetting(landscape, split, folded)
        apply(
            height = Defaults.PREF_KEYBOARD_HEIGHT_SCALE[index2],
            side = Defaults.PREF_SIDE_PADDING_SCALE[index3],
            bottom = Defaults.PREF_BOTTOM_PADDING_SCALE[index2],
            force = true,
        )
    }

    // ---------- geometry ----------

    private fun keyboardFrame(): View? = KeyboardSwitcher.getInstance().wrapperView

    private fun updateFrame() {
        val wrapper = keyboardFrame() ?: return
        val strip = KeyboardSwitcher.getInstance().stripContainer
        val loc = IntArray(2)
        val own = IntArray(2)
        getLocationInWindow(own)
        wrapper.getLocationInWindow(loc)
        val top = if (strip != null && strip.isShown) { val s = IntArray(2); strip.getLocationInWindow(s); s[1] } else loc[1]
        val w = wrapper.width.toFloat()
        val sidePx = sideScale() * SIDE_FRACTION * w
        val bottomPx = bottomScale() * BOTTOM_FRACTION * wrapper.height
        frame.set(loc[0] - own[0] + sidePx, (top - own[1]).toFloat(), loc[0] - own[0] + w - sidePx, loc[1] - own[1] + wrapper.height - bottomPx)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateFrame()
        // The top suggestion strip is reserved for controls while resizing.
        // The typing keys and resize handles remain visible and uncovered.
        controls.translationX = frame.centerX() - controls.width / 2f - controls.left
        controls.translationY = frame.top + SvUi.dp(context, 4) - controls.top
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateFrame()
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(SvUi.panelBackground(), 0xB8)
        // dim only the keyboard, the app above stays readable
        canvas.drawRect(0f, frame.top, width.toFloat(), height.toFloat(), paint)
        val accent = SvUi.accent()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = SvUi.dp(context, 2).toFloat()
        paint.color = accent
        val r = SvUi.dp(context, 12).toFloat()
        canvas.drawRoundRect(frame, r, r, paint)
        paint.style = Paint.Style.FILL
        // handles
        val hw = SvUi.dp(context, 44).toFloat()
        val hh = SvUi.dp(context, 6).toFloat()
        canvas.drawRoundRect(frame.centerX() - hw / 2, frame.top - hh / 2, frame.centerX() + hw / 2, frame.top + hh / 2, hh, hh, paint)
        val sh = SvUi.dp(context, 40).toFloat()
        canvas.drawRoundRect(frame.left - hh / 2, frame.centerY() - sh / 2, frame.left + hh / 2, frame.centerY() + sh / 2, hh, hh, paint)
        canvas.drawRoundRect(frame.right - hh / 2, frame.centerY() - sh / 2, frame.right + hh / 2, frame.centerY() + sh / 2, hh, hh, paint)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val target = hitTest(ev.x, ev.y)
            if (target != Drag.NONE) { startDrag(target, ev); return true }
        }
        return drag != Drag.NONE
    }

    private fun hitTest(x: Float, y: Float): Drag {
        updateFrame()
        val slop = SvUi.dp(context, 26).toFloat()
        val moveLoc = IntArray(2); val own = IntArray(2)
        moveButton.getLocationInWindow(moveLoc); getLocationInWindow(own)
        val mx = moveLoc[0] - own[0]; val my = moveLoc[1] - own[1]
        if (x >= mx - 8 && x <= mx + moveButton.width + 8 && y >= my - 8 && y <= my + moveButton.height + 8) return Drag.MOVE
        if (abs(y - frame.top) < slop && x > frame.left && x < frame.right) return Drag.TOP
        if (abs(x - frame.left) < slop && y > frame.top && y < frame.bottom) return Drag.LEFT
        if (abs(x - frame.right) < slop && y > frame.top && y < frame.bottom) return Drag.RIGHT
        return Drag.NONE
    }

    private fun startDrag(target: Drag, ev: MotionEvent) {
        drag = target
        downX = ev.rawX
        downY = ev.rawY
        startHeightScale = heightScale()
        startSideScale = sideScale()
        startBottomScale = bottomScale()
        startKeyboardHeight = keyboardFrame()?.height?.coerceAtLeast(1) ?: 1
        SvUi.haptic(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = hitTest(ev.x, ev.y)
                if (target != Drag.NONE) startDrag(target, ev)
            }
            MotionEvent.ACTION_MOVE -> onDrag(ev, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onDrag(ev, true)
                drag = Drag.NONE
            }
        }
        return true
    }

    private fun onDrag(ev: MotionEvent, final: Boolean) {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        val width = keyboardFrame()?.width?.toFloat() ?: return
        when (drag) {
            Drag.TOP -> {
                val newHeight = startKeyboardHeight - dy
                val scale = (startHeightScale * newHeight / startKeyboardHeight).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
                if (abs(scale - heightScale()) > 0.005f || final) apply(height = scale, force = final)
            }
            Drag.LEFT, Drag.RIGHT -> {
                val inward = if (drag == Drag.LEFT) dx else -dx
                val px = startSideScale * SIDE_FRACTION * width + inward
                val scale = (px / (SIDE_FRACTION * width)).coerceIn(0f, MAX_SIDE)
                if (abs(scale - sideScale()) > 0.01f || final) apply(side = scale, force = final)
            }
            Drag.MOVE -> {
                val startBottomPx = startBottomScale * BOTTOM_FRACTION * startKeyboardHeight
                val px = startBottomPx - dy
                val b = (px / (BOTTOM_FRACTION * startKeyboardHeight)).coerceIn(0f, MAX_BOTTOM)
                // keep the key area unchanged when moving: the bottom padding is taken from the keyboard height
                val h = (startHeightScale * (1 - BOTTOM_FRACTION * startBottomScale) / (1 - BOTTOM_FRACTION * b)).coerceIn(MIN_HEIGHT, MAX_HEIGHT + 0.3f)
                if (abs(b - bottomScale()) > 0.02f || final) apply(height = h, bottom = b, force = final)
            }
            Drag.NONE -> {}
        }
    }

    companion object {
        // must match keyboardLeftPadding / keyboardBottomPadding of the keyboard styles
        const val SIDE_FRACTION = 0.08f
        const val BOTTOM_FRACTION = 0.04f
        const val MIN_HEIGHT = 0.6f
        const val MAX_HEIGHT = 1.5f
        const val MAX_SIDE = 2.6f
        const val MAX_BOTTOM = 5f
    }
}
