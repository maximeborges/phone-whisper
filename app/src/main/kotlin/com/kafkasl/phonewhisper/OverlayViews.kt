package com.kafkasl.phonewhisper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/** Animated equalizer bars shown inside the bubble while listening. */
class WaveformView(context: Context) : View(context) {
    /** Live input level 0..1, written from the capture thread. */
    @Volatile var level = 0f
    var barColor = 0xFF1C1C1E.toInt()
        set(v) { field = v; paint.color = v }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barColor; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val envelope = floatArrayOf(0.35f, 0.55f, 0.75f, 0.95f, 1f, 0.95f, 0.75f, 0.55f, 0.35f)
    private var smooth = 0f
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 900; repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onVisibilityChanged(changed: View, visibility: Int) {
        super.onVisibilityChanged(changed, visibility)
        if (visibility == VISIBLE) anim.start() else anim.cancel()
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        smooth += (level.coerceIn(0f, 1f) - smooth) * 0.25f
        val n = envelope.size
        val w = width.toFloat(); val h = height.toFloat()
        val slot = w * 0.64f / n
        val bar = slot * 0.6f
        paint.strokeWidth = bar
        val left = (w - slot * n) / 2f + slot / 2f
        val maxH = h * 0.62f
        val minH = bar
        val amp = 0.2f + 0.8f * smooth
        for (i in 0 until n) {
            val wob = 0.5f + 0.5f * sin(phase + i * 0.8f)
            val bh = minH + (maxH - minH) * envelope[i] * amp * (0.35f + 0.65f * wob)
            val x = left + i * slot
            c.drawLine(x, (h - bh) / 2f + bar / 2f, x, (h + bh) / 2f - bar / 2f, paint)
        }
    }
}

/** Rotating arc running around a rounded square (matches the squared bubble). */
class RoundedSpinnerView(context: Context) : View(context) {
    var color = 0xFFE8EAED.toInt()
        set(v) { field = v; paint.color = v }
    var cornerRadius = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@RoundedSpinnerView.color; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val full = Path()
    private val seg = Path()
    private val measure = PathMeasure()
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onVisibilityChanged(changed: View, visibility: Int) {
        super.onVisibilityChanged(changed, visibility)
        if (visibility == VISIBLE) anim.start() else anim.cancel()
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val sw = resources.displayMetrics.density * 3f
        paint.strokeWidth = sw
        val r = RectF(sw / 2, sw / 2, width - sw / 2, height - sw / 2)
        full.reset(); full.addRoundRect(r, cornerRadius, cornerRadius, Path.Direction.CW)
        measure.setPath(full, true)
        val len = measure.length
        val arc = len * 0.3f
        val start = t * len
        seg.reset()
        if (start + arc <= len) {
            measure.getSegment(start, start + arc, seg, true)
        } else {
            measure.getSegment(start, len, seg, true)
            measure.getSegment(0f, start + arc - len, seg, true)
        }
        c.drawPath(seg, paint)
    }
}
