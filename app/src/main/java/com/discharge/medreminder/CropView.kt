package com.discharge.medreminder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.Magnifier
import kotlin.math.hypot
import kotlin.math.min

/** Shows a photo with 4 draggable corner handles (coordinates normalised to 0..1). */
class CropView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var corners: Array<PointF> = defaultCorners()
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val dst = RectF()
    private var drag = -1
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E88E5.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1E88E5.toInt() }
    private val handleR = 14f * density
    private val touchR = 44f * density
    private val magnifier: Magnifier? = if (Build.VERSION.SDK_INT >= 28) Magnifier(this) else null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        if (b.isRecycled) return
        val scale = min(width / b.width.toFloat(), height / b.height.toFloat())
        val w = b.width * scale; val h = b.height * scale
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawBitmap(b, null, dst, null)
        val path = Path()
        corners.forEachIndexed { i, p -> if (i == 0) path.moveTo(vx(p), vy(p)) else path.lineTo(vx(p), vy(p)) }
        path.close()
        canvas.drawPath(path, linePaint)
        corners.forEach { canvas.drawCircle(vx(it), vy(it), handleR, handlePaint) }
    }

    private fun vx(p: PointF) = dst.left + p.x * dst.width()
    private fun vy(p: PointF) = dst.top + p.y * dst.height()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null || dst.width() <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nearest = corners.indices.minByOrNull { hypot(vx(corners[it]) - e.x, vy(corners[it]) - e.y) } ?: -1
                drag = if (nearest >= 0 && hypot(vx(corners[nearest]) - e.x, vy(corners[nearest]) - e.y) <= touchR) nearest else -1
                if (drag >= 0) parent?.requestDisallowInterceptTouchEvent(true)
                return drag >= 0
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) {
                corners[drag].set(
                    ((e.x - dst.left) / dst.width()).coerceIn(0f, 1f),
                    ((e.y - dst.top) / dst.height()).coerceIn(0f, 1f)
                )
                if (Build.VERSION.SDK_INT >= 28) magnifier?.show(e.x, e.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (Build.VERSION.SDK_INT >= 28) magnifier?.dismiss()
                val wasDragging = drag >= 0
                drag = -1
                if (wasDragging) performClick()
                return wasDragging
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        fun defaultCorners(inset: Float = 0.03f) = arrayOf(
            PointF(inset, inset), PointF(1 - inset, inset), PointF(1 - inset, 1 - inset), PointF(inset, 1 - inset)
        )
    }
}
