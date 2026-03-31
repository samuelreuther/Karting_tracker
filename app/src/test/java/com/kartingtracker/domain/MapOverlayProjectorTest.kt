package com.kartingtracker.domain

import com.kartingtracker.data.CurveDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOverlayProjectorTest {
    private val projector = MapOverlayProjector()

    @Test
    fun projectCurves_withoutMapFallsBackToPerimeterApproximation() {
        val curves = listOf(
            CurveDefinition(index = 1, startPercent = 5f, endPercent = 15f, peakPercent = 10f, intensity = 0.4f),
            CurveDefinition(index = 2, startPercent = 45f, endPercent = 55f, peakPercent = 50f, intensity = 0.7f),
            CurveDefinition(index = 3, startPercent = 80f, endPercent = 90f, peakPercent = 85f, intensity = 0.9f)
        )

        val projected = projector.projectCurves(
            track = null,
            trackLayout = null,
            referenceLap = null,
            curves = curves
        )

        assertEquals(3, projected.size)
        assertEquals(listOf("T1", "T2", "T3"), projected.map { curve -> curve.label })
        assertTrue(projected.all { curve -> curve.position.x in 0.12f..0.88f && curve.position.y in 0.12f..0.88f })
    }
}
