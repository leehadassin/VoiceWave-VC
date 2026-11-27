package com.lee.voicewave

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class WaveView(context: Context) : View(context) {

    enum class Mode { WAVE, SCOPE }

    var mode = Mode.WAVE

    /** 0..1 loudness, written by the mic thread. */
    @Volatile var targetLevel = 0f

    private var level = 0f
    private var time = 0f
    private var lastFrame = 0L

    private val scope = FloatArray(512)
    private val scopeSnap = FloatArray(512)
    private val scopeLock = Any()

    private class Layer(val color: Int, val cycles: Float, val speed: Float, val amp: Float)

    private val layers = listOf(
        Layer(Color.rgb(120, 90, 255), 1.6f, 1.8f, 0.60f),  // violet, back
        Layer(Color.rgb(255, 64, 160), 3.1f, -3.4f, 0.75f), // pink
        Layer(Color.rgb(0, 229, 255), 2.2f, 2.6f, 1.00f),   // cyan, front
    )

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint().apply {
        color = Color.rgb(0, 60, 25)
        strokeWidth = 1f
    }
    private val path = Path()

    fun pushSamples(buf: ShortArray, n: Int) {
        synchronized(scopeLock) {
            val count = min(n, scope.size)
            System.arraycopy(scope, count, scope, 0, scope.size - count)
            val start = scope.size - count
            for (i in 0 until count) scope[start + i] = buf[n - count + i] / 32768f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = now
        time += dt

        // Quick rise when you speak, gentle fall afterwards
        val t = targetLevel
        val k = if (t > level) 18f else 3.5f
        level += (t - level) * min(1f, k * dt)

        canvas.drawColor(Color.BLACK)
        when (mode) {
            Mode.WAVE -> drawWave(canvas)
            Mode.SCOPE -> drawScope(canvas)
        }
        postInvalidateOnAnimation()
    }

    private fun drawWave(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f
        val maxAmp = h * 0.42f
        val idle = h * 0.012f + h * 0.006f * sin(time * 1.3f) // slow "breathing" when quiet
        val steps = 140
        val twoPi = (2 * PI).toFloat()

        for (layer in layers) {
            path.reset()
            val amp = idle + maxAmp * level * layer.amp
            for (i in 0..steps) {
                val x = i.toFloat() / steps
                val e = sin(PI.toFloat() * x)
                val env = e * e // taper to a point at both edges
                val y = mid + amp * env * sin(twoPi * layer.cycles * x + time * layer.speed)
                if (i == 0) path.moveTo(0f, y) else path.lineTo(x * w, y)
            }
            line.color = layer.color
            // Fake glow: wide faint stroke, medium, then a crisp core
            line.alpha = 35;  line.strokeWidth = h * 0.035f; c.drawPath(path, line)
            line.alpha = 80;  line.strokeWidth = h * 0.014f; c.drawPath(path, line)
            line.alpha = 255; line.strokeWidth = h * 0.0045f; c.drawPath(path, line)
        }
    }

    private fun drawScope(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f

        for (i in 1 until 10) { val x = w * i / 10f; c.drawLine(x, 0f, x, h, grid) }
        for (i in 1 until 8) { val y = h * i / 8f; c.drawLine(0f, y, w, y, grid) }

        synchronized(scopeLock) { System.arraycopy(scope, 0, scopeSnap, 0, scope.size) }

        val gain = 4f
        path.reset()
        val n = scopeSnap.size
        for (i in 0 until n) {
            val x = w * i / (n - 1)
            val y = mid - (scopeSnap[i] * gain).coerceIn(-1f, 1f) * h * 0.45f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        line.color = Color.rgb(60, 255, 120)
        line.alpha = 50;  line.strokeWidth = h * 0.02f;  c.drawPath(path, line)
        line.alpha = 255; line.strokeWidth = h * 0.004f; c.drawPath(path, line)
    }
}
