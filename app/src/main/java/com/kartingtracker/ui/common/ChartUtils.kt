package com.kartingtracker.ui.common

import android.content.Context
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.kartingtracker.R

object ChartUtils {
    fun configureLineChart(chart: LineChart) {
        chart.description.isEnabled = false
        chart.setBackgroundColor(ContextCompat.getColor(chart.context, R.color.karting_panel))
        chart.setExtraOffsets(8f, 8f, 8f, 12f)
        chart.setNoDataText("No lap data available yet.")
        chart.setNoDataTextColor(ContextCompat.getColor(chart.context, R.color.karting_muted))

        chart.legend.apply {
            isEnabled = true
            textColor = ContextCompat.getColor(chart.context, R.color.karting_text_secondary)
            textSize = 11f
            form = Legend.LegendForm.LINE
            formLineWidth = 3f
            yEntrySpace = 4f
        }

        chart.setTouchEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.axisRight.isEnabled = false

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            axisMinimum = 0f
            axisMaximum = 100f
            granularity = 10f
            textColor = ContextCompat.getColor(chart.context, R.color.karting_text_secondary)
            gridColor = ContextCompat.getColor(chart.context, R.color.karting_stroke)
        }

        chart.axisLeft.apply {
            textColor = ContextCompat.getColor(chart.context, R.color.karting_text_secondary)
            gridColor = ContextCompat.getColor(chart.context, R.color.karting_stroke)
            setDrawAxisLine(false)
        }
    }

    fun createDataSet(
        context: Context,
        label: String,
        entries: List<Entry>,
        colorRes: Int
    ): LineDataSet {
        return LineDataSet(entries, label).apply {
            color = ContextCompat.getColor(context, colorRes)
            lineWidth = 2.6f
            setDrawCircles(false)
            setDrawValues(false)
            highLightColor = ContextCompat.getColor(context, R.color.karting_dark)
            setDrawHighlightIndicators(false)
            mode = LineDataSet.Mode.HORIZONTAL_BEZIER
            cubicIntensity = 0.18f
        }
    }

    fun createMarkerDataSet(
        context: Context,
        label: String,
        entries: List<Entry>,
        colorRes: Int
    ): LineDataSet {
        return LineDataSet(entries, label).apply {
            val color = ContextCompat.getColor(context, colorRes)
            this.color = color
            setCircleColor(color)
            lineWidth = 0f
            circleRadius = 3.8f
            setDrawCircles(true)
            setDrawCircleHole(true)
            circleHoleRadius = 1.6f
            setCircleHoleColor(ContextCompat.getColor(context, R.color.karting_panel))
            setDrawValues(false)
            setDrawHighlightIndicators(false)
        }
    }
}
