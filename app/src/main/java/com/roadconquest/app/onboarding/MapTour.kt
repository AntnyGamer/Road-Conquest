package com.roadconquest.app.onboarding

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.roadconquest.app.R
import com.roadconquest.app.util.Prefs

/** A modal guide over the real controls. Its demo never touches location or saved progress. */
@SuppressLint("ViewConstructor") // Created with its host and completion callback, never inflated from XML.
class MapTour(
    private val host: FrameLayout,
    initialStep: Int = 0,
    initialPreviewRevealed: Boolean = false,
    private val onFinished: () -> Unit
) : FrameLayout(host.context) {
    private data class Step(val title: Int, val body: Int, val target: Int = 0)
    private val steps = listOf(
        Step(R.string.tour_intro_title, R.string.tour_intro_body),
        Step(R.string.tour_tracking_title, R.string.tour_tracking_body, R.id.enableButton),
        Step(R.string.tour_layers_title, R.string.tour_layers_body, R.id.overlayButton),
        Step(R.string.tour_progress_title, R.string.tour_progress_body, R.id.exploreNav),
        Step(R.string.tour_garage_title, R.string.tour_garage_body, R.id.pointsText),
        Step(R.string.tour_settings_title, R.string.tour_settings_body, R.id.settingsButton)
    )
    var step = initialStep.coerceIn(steps.indices)
        private set
    var previewRevealed = initialPreviewRevealed
        private set
    private val card = LayoutInflater.from(context).inflate(R.layout.map_tour_card, this, false)
    private val title = card.findViewById<TextView>(R.id.tourTitle)
    private val preview = card.findViewById<DiscoveryPreview>(R.id.tourPreview)
    private val demo = card.findViewById<Button>(R.id.tourDemoButton)
    private val targetRect = RectF()
    private val shadePath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val priorFocus = host.findFocus()
    private var dismissed = false
    private var wideLayout = false
    private val accessibility = (0 until host.childCount).map {
        host.getChildAt(it).let { view -> view to view.importantForAccessibility }
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusableInTouchMode = true
        elevation = dp(24f)
        addView(card)
        accessibility.forEach { (view, _) -> view.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        host.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(this)
        card.findViewById<Button>(R.id.tourSkipButton).setOnClickListener { complete() }
        card.findViewById<Button>(R.id.tourBackButton).setOnClickListener { back() }
        card.findViewById<Button>(R.id.tourNextButton).setOnClickListener {
            if (step == steps.lastIndex) complete() else { step++; render() }
        }
        demo.setOnClickListener {
            previewRevealed = !previewRevealed
            preview.setRevealed(previewRevealed, animate = true)
            updateDemoLabel()
        }
        requestFocus()
        render()
    }

    /** Back at the introduction leaves the app without choosing Skip or starting setup. */
    fun back(): Boolean {
        if (step == 0) return false
        step--
        render()
        return true
    }

    override fun focusSearch(focused: View, direction: Int): View? =
        FocusFinder.getInstance().findNextFocus(this, focused, direction)
            ?: card.findViewById(if (direction == FOCUS_BACKWARD) R.id.tourNextButton else R.id.tourSkipButton)

    private fun updateDemoLabel() {
        demo.setText(if (previewRevealed) R.string.tour_demo_reset else R.string.tour_demo_action)
        card.findViewById<TextView>(R.id.tourDemoCaption)
            .setText(if (previewRevealed) R.string.tour_demo_revealed else R.string.tour_demo_hint)
    }

    private fun render() {
        val current = steps[step]
        title.setText(current.title)
        card.findViewById<TextView>(R.id.tourBody).setText(current.body)
        card.findViewById<TextView>(R.id.tourStep).text = context.getString(R.string.tour_step, step + 1, steps.size)
        card.findViewById<View>(R.id.tourDemo).visibility = if (step == 0) VISIBLE else GONE
        card.findViewById<Button>(R.id.tourBackButton).visibility = if (step == 0) GONE else VISIBLE
        card.findViewById<Button>(R.id.tourNextButton)
            .setText(if (step == steps.lastIndex) R.string.tour_finish else R.string.tour_next)
        preview.setRevealed(previewRevealed)
        updateDemoLabel()
        card.findViewById<View>(R.id.tourScroll).scrollTo(0, 0)
        ViewCompat.setAccessibilityPaneTitle(this, context.getString(current.title))
        requestLayout()
        invalidate()
    }

    private fun complete() {
        if (dismissed) return
        Prefs.setTourHandled(context, true)
        dismiss()
        onFinished()
    }

    /** Rotation/destruction removes the UI without recording a Skip or a completion. */
    fun dismiss() {
        if (dismissed) return
        dismissed = true
        preview.stopAnimation()
        host.removeView(this)
        accessibility.forEach { (view, mode) -> view.importantForAccessibility = mode }
        priorFocus?.requestFocus()
    }

    private fun updateTarget() {
        targetRect.setEmpty()
        val id = steps[step].target
        if (id == 0) return
        val target = host.findViewById<View>(id) ?: return
        if (target.width == 0 || target.height == 0) return
        val rect = Rect()
        target.getDrawingRect(rect)
        host.offsetDescendantRectToMyCoords(target, rect)
        rect.offset(-left, -top)
        targetRect.set(rect)
        targetRect.inset(-dp(5f), -dp(5f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        updateTarget()
        measureCard(width, height)
    }

    private fun measureCard(width: Int, height: Int) {
        val margin = dp(16f).toInt()
        val availableHeight = (height - paddingTop - paddingBottom - margin * 2).coerceAtLeast(1)
        val availableWidth = (width - paddingLeft - paddingRight - margin * 2).coerceAtLeast(1)
        wideLayout = availableWidth > availableHeight * 1.3f
        val cardWidth = minOf(dp(440f).toInt(), if (wideLayout) availableWidth / 2 else availableWidth)
        val room = if (targetRect.isEmpty) availableHeight else if (targetRect.centerY() > height / 2f) {
            (targetRect.top - paddingTop - margin * 2).toInt()
        } else {
            (height - paddingBottom - targetRect.bottom - margin * 2).toInt()
        }
        // Keep navigation visible; long copy scrolls inside the card at large font sizes.
        val minimum = dp(if (resources.configuration.fontScale >= 1.5f) 240f else 160f).toInt()
        val maxHeight = room.coerceIn(minOf(minimum, availableHeight), availableHeight)
        card.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        updateTarget()
        // The host lays out its controls after measuring this overlay, especially on rotation.
        // Reusing the same measure specs is cheap; changed target bounds need a new constraint.
        measureCard(width, height)
        val margin = dp(16f).toInt()
        val cardLeft = if (wideLayout && !targetRect.isEmpty) {
            if (targetRect.centerX() > width / 2f) paddingLeft + margin else width - paddingRight - margin - card.measuredWidth
        } else paddingLeft + (width - paddingLeft - paddingRight - card.measuredWidth) / 2
        val cardTop = if (!targetRect.isEmpty && targetRect.centerY() > height / 2f) paddingTop + margin
            else height - paddingBottom - margin - card.measuredHeight
        card.layout(cardLeft, cardTop, cardLeft + card.measuredWidth, cardTop + card.measuredHeight)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        shadePath.reset()
        shadePath.fillType = Path.FillType.EVEN_ODD
        shadePath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        if (!targetRect.isEmpty) shadePath.addRoundRect(targetRect, dp(20f), dp(20f), Path.Direction.CW)
        paint.color = Color.argb(205, 5, 12, 25)
        paint.style = Paint.Style.FILL
        canvas.drawPath(shadePath, paint)
        if (!targetRect.isEmpty) {
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            canvas.drawRoundRect(targetRect, dp(20f), dp(20f), paint)
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}

/** Lightweight, offline illustration; one short animation only when the user taps Try. */
class DiscoveryPreview(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val route = Path()
    private val routeMeasure = PathMeasure()
    private val carPosition = FloatArray(2)
    private var reveal = 0f
    private var animator: ValueAnimator? = null

    fun setRevealed(revealed: Boolean, animate: Boolean = false) {
        stopAnimation()
        val target = if (revealed) 1f else 0f
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(reveal, target).apply {
                duration = 450
                addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { reveal = target; invalidate() }
    }

    fun stopAnimation() { animator?.cancel(); animator = null }

    override fun onDetachedFromWindow() { stopAnimation(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 320f, height / 148f)
        route.reset()
        route.addRoundRect(0f, 0f, 320f, 148f, 20f, 20f, Path.Direction.CW)
        canvas.clipPath(route)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(15, 28, 49)
        canvas.drawRoundRect(0f, 0f, 320f, 148f, 20f, 20f, paint)
        paint.strokeWidth = 2f
        paint.color = Color.rgb(47, 66, 90)
        for (x in 22..310 step 48) canvas.drawLine(x.toFloat(), 0f, x + 40f, 148f, paint)
        for (y in 18..148 step 32) canvas.drawLine(0f, y.toFloat(), 320f, y - 28f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((reveal * 50).toInt(), 130, 178, 255)
        canvas.drawRoundRect(112f, 26f, 260f, 124f, 16f, 16f, paint)
        route.reset()
        route.moveTo(38f, 108f)
        route.cubicTo(90f, 108f, 84f, 65f, 136f, 65f)
        route.lineTo(224f, 65f)
        routeMeasure.setPath(route, false)
        routeMeasure.getPosTan(routeMeasure.length * reveal, carPosition, null)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 5f
        paint.color = Color.rgb(130, 178, 255)
        canvas.drawPath(route, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(((1f - reveal) * 240).toInt(), 22, 34, 53)
        canvas.drawRoundRect(112f, 26f, 260f, 124f, 16f, 16f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(carPosition[0], carPosition[1], 7f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.argb((reveal * 180).toInt(), 130, 178, 255)
        canvas.drawRoundRect(112f, 26f, 260f, 124f, 16f, 16f, paint)
        canvas.restore()
    }
}
