package app.maillage

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** Local-only opening animation. Does not perform any network or permission operations. */
internal class LaunchSplash(context: Context, private val onReady: () -> Unit) : FrameLayout(context) {
    private var notified = false
    private val logo = MeshLogoView(context)
    private val skip: TextView
    private var logoAnimator: ValueAnimator? = null
    private val readyCallback = Runnable { notifyReady() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Ouverture de Maillage"
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(22, 29, 50), Color.rgb(19, 17, 47))
        )
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        center.addView(logo, LinearLayout.LayoutParams(dp(190), dp(190)))
        val title = TextView(context).apply {
            text = "Maillage."
            textSize = 34f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(245, 246, 255))
            gravity = Gravity.CENTER
        }
        center.addView(title, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        val tagline = TextView(context).apply {
            text = "Le lien, de proche en proche."
            textSize = 14f
            setTextColor(Color.rgb(192, 195, 218))
            gravity = Gravity.CENTER
        }
        center.addView(tagline, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(center, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        skip = TextView(context).apply {
            text = "Passer →"
            textSize = 14f
            setTextColor(Color.rgb(220, 221, 240))
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(76)
            setPadding(dp(12), 0, dp(12), 0)
            contentDescription = "Passer l’animation d’ouverture"
            setOnClickListener { notifyReady() }
        }
        addView(skip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12); marginEnd = dp(12)
        })
    }

    fun start() {
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            logo.progress = 1f
            post(readyCallback)
            return
        }
        logoAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L
            interpolator = DecelerateInterpolator()
            addUpdateListener { logo.progress = it.animatedValue as Float }
            start()
        }
        postDelayed(readyCallback, 2600L)
    }

    private fun notifyReady() {
        if (notified) return
        notified = true
        removeCallbacks(readyCallback)
        logoAnimator?.cancel()
        logo.progress = 1f
        skip.isEnabled = false
        onReady()
    }

    /** Fade only after the destination is ready; no artificial progress bar. */
    fun fadeOut(onDone: () -> Unit) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            onDone()
        } else {
            animate().alpha(0f).setDuration(650L).setInterpolator(DecelerateInterpolator())
                .withEndAction { onDone() }.start()
        }
    }

    fun dispose() {
        removeCallbacks(readyCallback)
        logoAnimator?.cancel()
        animate().setListener(null)
        animate().cancel()
    }

    private class MeshLogoView(context: Context) : View(context) {
        var progress = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(55, 193, 194, 237)
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(226, 228, 255)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 228, 255) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = min(width, height).toFloat()
            val cx = width / 2f; val cy = height / 2f
            ring.strokeWidth = size * .005f
            ring.alpha = (55 * progress).toInt()
            canvas.drawCircle(cx, cy, size * .48f, ring)
            canvas.drawCircle(cx, cy, size * .37f, ring)
            val points = arrayOf(
                floatArrayOf(cx - size * .23f, cy + size * .12f),
                floatArrayOf(cx - size * .05f, cy - size * .23f),
                floatArrayOf(cx + size * .22f, cy - size * .09f),
                floatArrayOf(cx + size * .14f, cy + size * .25f)
            )
            val edges = arrayOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 1 to 3, 0 to 2)
            line.strokeWidth = size * .013f
            edges.forEachIndexed { index, (from, to) ->
                val portion = (progress * edges.size - index).coerceIn(0f, 1f)
                if (portion > 0f) {
                    val a = points[from]; val b = points[to]
                    canvas.drawLine(a[0], a[1], a[0] + (b[0] - a[0]) * portion, a[1] + (b[1] - a[1]) * portion, line)
                }
            }
            points.forEachIndexed { index, point ->
                val visible = (progress * 5f - index).coerceIn(0f, 1f)
                if (visible > 0f) canvas.drawCircle(point[0], point[1], size * .026f * visible, dot)
            }
        }
    }
}
