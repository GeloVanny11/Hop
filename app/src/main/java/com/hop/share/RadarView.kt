package com.hop.share

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Radar that shows nearby phones. Tap a phone to send to it. */
class RadarView(ctx: Context) : View(ctx) {
    var peers: List<MainActivity.Peer> = emptyList()
        set(value) { field = value; invalidate() }
    var onTap: (MainActivity.Peer) -> Unit = {}

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.parseColor("#BFD3CD")
    }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var angle = 0f
    private val spots = ArrayList<Triple<MainActivity.Peer, Float, Float>>()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sweep.shader = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(Color.argb(0, 43, 92, 255), Color.argb(90, 43, 92, 255)),
            floatArrayOf(0.75f, 1f)
        )
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 8 * d
        for (k in 1..3) c.drawCircle(cx, cy, r * k / 3, ring)
        c.save(); c.rotate(angle, cx, cy); c.drawCircle(cx, cy, r, sweep); c.restore()

        fill.color = Color.parseColor("#FFC93C")
        c.drawCircle(cx, cy, 22 * d, fill)
        txt.color = Color.parseColor("#10302B"); txt.textSize = 13 * d; txt.typeface = Typeface.DEFAULT_BOLD
        c.drawText("You", cx, cy + 5 * d, txt)

        spots.clear()
        val n = peers.size
        peers.forEachIndexed { i, p ->
            val a = i.toDouble() / n * 2 * PI - PI / 2
            val rr = if (n > 3) r * (if (i % 2 == 0) 0.5f else 0.8f) else r * 0.62f
            val x = cx + (rr * cos(a)).toFloat()
            val y = cy + (rr * sin(a)).toFloat()
            fill.color = Color.parseColor("#2B5CFF")
            c.drawCircle(x, y, 23 * d, fill)
            txt.textSize = 20 * d; txt.color = Color.WHITE
            c.drawText("\uD83D\uDCF1", x, y + 7 * d, txt)
            txt.textSize = 11 * d; txt.color = Color.parseColor("#10302B")
            c.drawText(p.name.take(16), x, y + 38 * d, txt)
            spots.add(Triple(p, x, y))
        }
        angle = (angle + 1.6f) % 360f
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            spots.firstOrNull { hypot(it.second - e.x, it.third - e.y) < 36 * d }?.let {
                onTap(it.first); performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
