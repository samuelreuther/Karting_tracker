package com.kartingtracker.domain

import com.kartingtracker.data.TrackCorner
import com.kartingtracker.data.TrackDirection
import com.kartingtracker.data.TrackLayout
import com.kartingtracker.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLayoutMapperTest {
    @Test
    fun sortAndRenameCorners_preservesManualCornerOrder() {
        val firstCorner = TrackCorner(name = "ignored", point = TrackPoint(0.8f, 0.2f))
        val secondCorner = TrackCorner(name = "ignored", point = TrackPoint(0.2f, 0.8f))
        val thirdCorner = TrackCorner(name = "ignored", point = TrackPoint(0.7f, 0.9f))
        val layout = TrackLayout(
            trackName = "Test",
            imagePath = "layout.png",
            lengthMeters = null,
            startPoint = TrackPoint(0.5f, 0.1f),
            direction = TrackDirection.COUNTER_CLOCKWISE,
            corners = listOf(firstCorner, secondCorner, thirdCorner)
        )

        val orderedCorners = TrackLayoutMapper.sortAndRenameCorners(layout)

        assertEquals(listOf(firstCorner.point, secondCorner.point, thirdCorner.point), orderedCorners.map { it.point })
        assertEquals(listOf("Kurve 1", "Kurve 2", "Kurve 3"), orderedCorners.map { it.name })
    }

    @Test
    fun buildCornerReferences_usesSavedCornerSequenceForMapping() {
        val layout = TrackLayout(
            trackName = "Test",
            imagePath = "layout.png",
            lengthMeters = null,
            direction = TrackDirection.COUNTER_CLOCKWISE,
            corners = listOf(
                TrackCorner(name = "A", point = TrackPoint(0.1f, 0.1f)),
                TrackCorner(name = "B", point = TrackPoint(0.5f, 0.5f)),
                TrackCorner(name = "C", point = TrackPoint(0.9f, 0.9f))
            )
        )
        val detectedCorners = listOf(
            DetectedCorner(0f, 10f, 5f, 1f),
            DetectedCorner(30f, 40f, 35f, 1f),
            DetectedCorner(60f, 70f, 65f, 1f)
        )

        val references = TrackLayoutMapper.buildCornerReferences(detectedCorners, layout)

        assertEquals(listOf("Kurve 1", "Kurve 2", "Kurve 3"), references.map { it.insightLabel })
        assertEquals(layout.corners.map { it.point }, references.mapNotNull { it.corner?.point })
    }
}
