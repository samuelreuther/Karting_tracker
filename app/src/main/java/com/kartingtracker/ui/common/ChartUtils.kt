package com.kartingtracker.ui.common

import android.content.Context
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineDataSet
import com.kartingtracker.R

object ChartUtils {
    fun configureLineChart(chart: LineChart) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = true
        chart.setTouchEnabled(true)
        chart.setScaleEnabled(true)
        chart.axisRight.isEnabled = false
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.axisMinimum = 0f
        chart.xAxis.axisMaximum = 100f
        chart.xAxis.granularity = 10f
        chart.axisLeft.setDrawGridLines(true)
        chart.xAxis.setDrawGridLines(false)
        chart.setNoDataText("No lap data available yet.")
    }

    fun createDataSet(
        context: Context,
        label: String,
        entries: List<Entry>,
        colorRes: Int
    ): LineDataSet {
        return LineDataSet(entries, label).apply {
            color = ContextCompat.getColor(context, colorRes)
            lineWidth = 2.2f
            setDrawCircles(false)
            setDrawValues(false)
            highLightColor = ContextCompat.getColor(context, R.color.karting_dark)
            setDrawHighlightIndicators(false)
            mode = LineDataSet.Mode.LINEAR
        }
    }

    fun createMarkerDataSet(
        context: Context,
        label: String,
        entries: List<Entry>,
        colorRes: Int
    ): LineDataSet {
        return LineDataSet(entries, label).apply {
            color = ContextCompat.getColor(context, colorRes)
            setCircleColor(ContextCompat.getColor(context, colorRes))
            lineWidth = 0f
            circleRadius = 4f
            setDrawCircles(true)
            setDrawCircleHole(false)
            setDrawValues(false)
            setDrawHighlightIndicators(false)
        }
    }
}
