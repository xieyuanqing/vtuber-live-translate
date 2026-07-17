package com.xyq.livetranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleOverlayGeometryTest {
    @Test
    fun expandedPanelNeverUsesTheWholeDisplayWidth() {
        val displayWidth = 1080
        val width = SubtitleOverlayGeometry.expandedWidth(displayWidth, density = 3f)

        assertTrue(width < displayWidth)
        assertTrue(width <= displayWidth - (24 * 3))
        assertTrue(width >= 240 * 3)
    }

    @Test
    fun expandedPanelCapsItsWidthOnWideDisplays() {
        val width = SubtitleOverlayGeometry.expandedWidth(displayWidth = 3000, density = 3f)

        assertEquals(360 * 3, width)
    }

    @Test
    fun expandedPanelStillFitsACompactDisplay() {
        val displayWidth = 600
        val width = SubtitleOverlayGeometry.expandedWidth(displayWidth, density = 3f)

        assertEquals(displayWidth - (24 * 3), width)
    }

    @Test
    fun collapseChoosesTheNearestSideFromThePanelCenter() {
        assertTrue(
            SubtitleOverlayGeometry.collapseToLeft(
                currentX = 20,
                currentWidth = 400,
                displayWidth = 1080,
            ),
        )
        assertFalse(
            SubtitleOverlayGeometry.collapseToLeft(
                currentX = 700,
                currentWidth = 300,
                displayWidth = 1080,
            ),
        )
    }

    @Test
    fun collapsedHandleHugsTheChosenDisplayEdge() {
        assertEquals(
            0,
            SubtitleOverlayGeometry.collapsedX(
                displayWidth = 1080,
                collapsedWidth = 132,
                onLeft = true,
            ),
        )
        assertEquals(
            948,
            SubtitleOverlayGeometry.collapsedX(
                displayWidth = 1080,
                collapsedWidth = 132,
                onLeft = false,
            ),
        )
    }

    @Test
    fun verticalPositionIsClampedInsideTheDisplay() {
        assertEquals(
            0,
            SubtitleOverlayGeometry.clampedY(
                displayHeight = 2400,
                windowHeight = 180,
                requestedY = -20,
            ),
        )
        assertEquals(
            2220,
            SubtitleOverlayGeometry.clampedY(
                displayHeight = 2400,
                windowHeight = 180,
                requestedY = 2300,
            ),
        )
    }
}
