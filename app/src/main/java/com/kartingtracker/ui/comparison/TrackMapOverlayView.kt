package com.kartingtracker.ui.comparison

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.kartingtracker.ui.ProjectedCurveUiState
import kotlin.math.abs
import kotlin.math.max

class TrackMapOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var mapBitmap: Bitmap? = null
    private var projectedCurves: List<ProjectedCurveUiState> = emptyList()
    private val density = resources.displayMetrics.density

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        filterBitmap = true
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DCE6EB")
        style = Paint.Style.FILL
    }
    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#546E7A")
        textSize = 14f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    fun render(bitmap: Bitmap?, curves: List<ProjectedCurveUiState>) {
        mapBitmap = bitmap
        projectedCurves = curves
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val drawingBounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(drawingBounds, 18f * density, 18f * density, emptyPaint)
        val bitmap = mapBitmap
        if (bitmap == null || bitmap.isRecycled) {
            canvas.drawText("No track map available", drawingBounds.centerX(), drawingBounds.centerY(), emptyTextPaint)
            return
        }

        val bitmapBounds = resolveBitmapBounds(bitmap, drawingBounds)
        canvas.drawBitmap(bitmap, null, bitmapBounds, bitmapPaint)
        projectedCurves.forEach { curve ->
            val x = bitmapBounds.left + (curve.x * bitmapBounds.width())
            val y = bitmapBounds.top + (curve.y * bitmapBounds.height())
            val radius = max(10f * density, (8f + (curve.intensity * 10f)) * density)
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = resolveCurveColor(curve)
            }
            canvas.drawCircle(x, y, radius, fillPaint)
            canvas.drawCircle(x, y, radius, outlinePaint)
            canvas.drawText(curve.label, x, y - radius - (6f * density), textPaint)
        }
    }

    private fun resolveBitmapBounds(bitmap: Bitmap, drawingBounds: RectF): RectF {
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
        val viewAspect = drawingBounds.width() / drawingBounds.height().coerceAtLeast(1f)
        return if (bitmapAspect > viewAspect) {
            val targetHeight = drawingBounds.width() / bitmapAspect
            val top = drawingBounds.top + ((drawingBounds.height() - targetHeight) / 2f)
            RectF(drawingBounds.left, top, drawingBounds.right, top + targetHeight)
        } else {
            val targetWidth = drawingBounds.height() * bitmapAspect
            val left = drawingBounds.left + ((drawingBounds.width() - targetWidth) / 2f)
            RectF(left, drawingBounds.top, left + targetWidth, drawingBounds.bottom)
        }
    }

    private fun resolveCurveColor(curve: ProjectedCurveUiState): Int {
        return when {
            curve.deltaSeconds <= -0.08f -> Color.parseColor("#2E7D32")
            curve.deltaSeconds >= 0.08f -> Color.parseColor("#C62828")
            else -> {
                val intensity = curve.intensity.coerceIn(0f, 1f)
                val baseColor = if (intensity > 0.65f) {
                    intArrayOf(183, 28, 28)
                } else {
                    intArrayOf(239, 108, 0)
                }
                val alpha = (180 + (abs(curve.deltaSeconds).coerceIn(0f, 0.25f) * 240f)).toInt().coerceIn(180, 255)
                Color.argb(alpha, baseColor[0], baseColor[1], baseColor[2])
            }
        }
    }
}
