package com.kartingtracker.ui.comparison

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.kartingtracker.ui.ProjectedCurveUiState
import com.kartingtracker.ui.TrackInsightMarker
import kotlin.math.abs
import kotlin.math.max

class TrackMapOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var mapBitmap: Bitmap? = null
    private var projectedCurves: List<ProjectedCurveUiState> = emptyList()
    private var insightMarkers: List<TrackInsightMarker> = emptyList()
    private var onInsightTapped: ((TrackInsightMarker) -> Unit)? = null
    private val density = resources.displayMetrics.density

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = spToPx(12f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DCE6EB")
        style = Paint.Style.FILL
    }
    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#546E7A")
        textSize = spToPx(14f)
        textAlign = Paint.Align.CENTER
    }

    private fun spToPx(sp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
    }

    fun render(bitmap: Bitmap?, curves: List<ProjectedCurveUiState>, insights: List<TrackInsightMarker> = emptyList()) {
        mapBitmap = bitmap
        projectedCurves = curves
        insightMarkers = insights
        invalidate()
    }

    fun setOnInsightTapListener(listener: (TrackInsightMarker) -> Unit) {
        onInsightTapped = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || insightMarkers.isEmpty() || width == 0 || height == 0) {
            return super.onTouchEvent(event)
        }
        val bounds = resolveBitmapBounds(mapBitmap ?: return false, RectF(0f, 0f, width.toFloat(), height.toFloat()))
        val hit = insightMarkers.firstOrNull { marker ->
            val x = bounds.left + marker.x * bounds.width()
            val y = bounds.top + marker.y * bounds.height()
            val radius = 22f * density
            abs(event.x - x) <= radius && abs(event.y - y) <= radius
        }
        if (hit != null) {
            onInsightTapped?.invoke(hit)
            return true
        }
        return super.onTouchEvent(event)
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
        insightMarkers.forEach { marker ->
            val x = bitmapBounds.left + marker.x * bitmapBounds.width()
            val y = bitmapBounds.top + marker.y * bitmapBounds.height()
            val radius = (10f + marker.severity * 10f) * density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = when {
                    marker.severity >= 0.75f -> Color.parseColor("#C62828")
                    marker.severity >= 0.45f -> Color.parseColor("#EF6C00")
                    else -> Color.parseColor("#F9A825")
                }
            }
            canvas.drawCircle(x, y, radius, paint)
            canvas.drawCircle(x, y, radius, outlinePaint)
            canvas.drawText("!", x, y + (4f * density), textPaint)
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
            else -> Color.parseColor("#EF6C00")
        }
    }
}
