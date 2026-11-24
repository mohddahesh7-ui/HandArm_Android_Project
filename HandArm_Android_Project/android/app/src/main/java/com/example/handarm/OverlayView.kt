package com.example.handarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Draws tracking lines + landmark dots over the (letter-boxed, FIT_CENTER) camera preview. */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var pts: List<PointF>? = null
    private var imgW = 1
    private var imgH = 1
    private var mirror = false

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }

    private class Chain(val idx: IntArray, val color: Int)
    private val chains = listOf(
        Chain(intArrayOf(0, 1, 2, 3, 4), Color.rgb(255, 160, 0)),      // thumb
        Chain(intArrayOf(0, 5, 6, 7, 8), Color.rgb(80, 220, 80)),      // index
        Chain(intArrayOf(0, 9, 10, 11, 12), Color.rgb(0, 200, 255)),   // middle
        Chain(intArrayOf(0, 13, 14, 15, 16), Color.rgb(200, 100, 255)),// ring
        Chain(intArrayOf(0, 17, 18, 19, 20), Color.rgb(255, 80, 80)),  // pinky
        Chain(intArrayOf(5, 9, 13, 17), Color.WHITE),                  // palm
    )

    fun setHand(p: List<PointF>?, imageW: Int, imageH: Int, mirrored: Boolean) {
        pts = p; imgW = imageW.coerceAtLeast(1); imgH = imageH.coerceAtLeast(1); mirror = mirrored
        invalidate()
    }

    fun clear() { pts = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val p = pts ?: return
        if (p.size < 21) return
        val scale = min(width.toFloat() / imgW, height.toFloat() / imgH)
        val offX = (width - imgW * scale) / 2f
        val offY = (height - imgH * scale) / 2f
        fun px(i: Int) = (if (mirror) 1f - p[i].x else p[i].x) * imgW * scale + offX
        fun py(i: Int) = p[i].y * imgH * scale + offY

        for (c in chains) {
            line.color = c.color
            for (k in 0 until c.idx.size - 1) canvas.drawLine(px(c.idx[k]), py(c.idx[k]), px(c.idx[k + 1]), py(c.idx[k + 1]), line)
        }
        for (c in chains.take(5)) {
            dot.color = c.color
            for (i in c.idx.drop(1)) canvas.drawCircle(px(i), py(i), 9f, dot)
        }
        dot.color = Color.WHITE
        canvas.drawCircle(px(0), py(0), 16f, dot)                     // wrist
        canvas.drawCircle(px(0), py(0), 22f, ring)
    }
}
