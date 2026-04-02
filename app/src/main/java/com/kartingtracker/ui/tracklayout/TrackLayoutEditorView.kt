package com.kartingtracker.ui.tracklayout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import com.kartingtracker.data.TrackCorner
import com.kartingtracker.data.TrackDirection
import com.kartingtracker.data.TrackLayout
import com.kartingtracker.data.TrackMarker
import com.kartingtracker.data.TrackPoint
import kotlin.math.max

class TrackLayoutEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var onTrackTap: ((TrackPoint) -> Unit)? = null

    private var startPoint: TrackPoint = TrackLayout.DEFAULT_START_POINT
    private var corners: List<TrackCorner> = emptyList()
    private var direction: TrackDirection = TrackDirection.CLOCKWISE
    private var markers: List<TrackMarker> = emptyList()
    private var highlightedLabels: Set<String> = emptySet()

    private val density = resources.displayMetrics.density
    private val startFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")
        style = Paint.Style.FILL
    }
    private val cornerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2")
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = spToPx(12f)
    }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        textSize = spToPx(13f)
    }
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        style = Paint.Style.FILL
        alpha = 170
    }
    private val markerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4511E")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val markerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3E2723")
        textAlign = Paint.Align.CENTER
        textSize = spToPx(11f)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EEF2F5")
        style = Paint.Style.FILL
    }

    init {
        adjustViewBounds = true
        scaleType = ScaleType.FIT_CENTER
    }

    private fun spToPx(sp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
    }

    fun renderLayout(layout: TrackLayout) {
        startPoint = layout.startPoint
        corners = layout.corners
        direction = layout.direction
        invalidate()
    }

    fun renderMarkers(markers: List<TrackMarker>, highlightedLabels: Set<String>) {
        this.markers = markers
        this.highlightedLabels = highlightedLabels
        invalidate()
    }

    fun setTrackImage(imagePath: String) {
        if (imagePath.isBlank()) {
            setImageDrawable(null)
        } else {
            setImageURI(Uri.fromFile(java.io.File(imagePath)))
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        super.onDraw(canvas)

        val drawableBounds = resolveDrawableBounds() ?: return
        drawDirectionLegend(canvas, drawableBounds)
        drawTrackMarkers(canvas, drawableBounds)
        drawStartMarker(canvas, drawableBounds)
        drawCornerMarkers(canvas, drawableBounds)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) {
            return true
        }

        val drawableBounds = resolveDrawableBounds() ?: return false
        if (!drawableBounds.contains(event.x, event.y)) {
            return false
        }

        val normalizedPoint = TrackPoint(
            x = ((event.x - drawableBounds.left) / drawableBounds.width()).coerceIn(0f, 1f),
            y = ((event.y - drawableBounds.top) / drawableBounds.height()).coerceIn(0f, 1f)
        )
        onTrackTap?.invoke(normalizedPoint)
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun drawStartMarker(canvas: Canvas, drawableBounds: RectF) {
        drawMarker(
            canvas = canvas,
            drawableBounds = drawableBounds,
            point = startPoint,
            fillPaint = startFillPaint,
            label = "S"
        )
    }

    private fun drawCornerMarkers(canvas: Canvas, drawableBounds: RectF) {
        corners.forEachIndexed { index, corner ->
            drawMarker(
                canvas = canvas,
                drawableBounds = drawableBounds,
                point = corner.point,
                fillPaint = cornerFillPaint,
                label = (index + 1).toString()
            )
        }
    }

    private fun drawTrackMarkers(canvas: Canvas, drawableBounds: RectF) {
        markers.forEach { marker ->
            val centerX = drawableBounds.left + (marker.x * drawableBounds.width())
            val centerY = drawableBounds.top + (marker.y * drawableBounds.height())
            val markerRadius = (10f + (marker.severity.coerceIn(0f, 1f) * 14f)) * density
            canvas.drawCircle(centerX, centerY, markerRadius, markerFillPaint)
            canvas.drawCircle(centerX, centerY, markerRadius, outlinePaint)
            if (highlightedLabels.contains(marker.label)) {
                canvas.drawCircle(centerX, centerY, markerRadius + (3f * density), markerHighlightPaint)
            }
            canvas.drawText(marker.label, centerX, centerY - markerRadius - (4f * density), markerLabelPaint)
        }
    }

    private fun drawMarker(
        canvas: Canvas,
        drawableBounds: RectF,
        point: TrackPoint,
        fillPaint: Paint,
        label: String
    ) {
        val markerRadius = max(10f * density, drawableBounds.width() * 0.018f)
        val centerX = drawableBounds.left + (point.x * drawableBounds.width())
        val centerY = drawableBounds.top + (point.y * drawableBounds.height())
        canvas.drawCircle(centerX, centerY, markerRadius, fillPaint)
        canvas.drawCircle(centerX, centerY, markerRadius, outlinePaint)
        canvas.drawText(label, centerX, centerY + (4f * density), labelPaint)
    }

    private fun drawDirectionLegend(canvas: Canvas, drawableBounds: RectF) {
        val legendWidth = 44f * density
        val legendHeight = 22f * density
        val originX = drawableBounds.right - legendWidth - (8f * density)
        val originY = drawableBounds.top + (12f * density)
        val path = Path().apply {
            if (direction == TrackDirection.CLOCKWISE) {
                moveTo(originX, originY + legendHeight)
                lineTo(originX + legendWidth, originY + legendHeight)
                moveTo(originX + legendWidth, originY + legendHeight)
                lineTo(originX + legendWidth - (7f * density), originY + legendHeight - (5f * density))
                moveTo(originX + legendWidth, originY + legendHeight)
                lineTo(originX + legendWidth - (7f * density), originY + legendHeight + (5f * density))
            } else {
                moveTo(originX + legendWidth, originY + legendHeight)
                lineTo(originX, originY + legendHeight)
                moveTo(originX, originY + legendHeight)
                lineTo(originX + (7f * density), originY + legendHeight - (5f * density))
                moveTo(originX, originY + legendHeight)
                lineTo(originX + (7f * density), originY + legendHeight + (5f * density))
            }
        }
        canvas.drawText(
            if (direction == TrackDirection.CLOCKWISE) "CW" else "CCW",
            originX,
            originY,
            legendTextPaint
        )
        canvas.drawPath(path, legendPaint)
    }

    private fun resolveDrawableBounds(): RectF? {
        val drawable = drawable ?: return null
        val matrixValues = FloatArray(9)
        imageMatrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]
        val scaleY = matrixValues[Matrix.MSCALE_Y]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        return RectF(
            transX,
            transY,
            transX + (drawable.intrinsicWidth * scaleX),
            transY + (drawable.intrinsicHeight * scaleY)
        )
    }
}
