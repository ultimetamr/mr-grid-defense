package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.GridRules
import com.picoxr.mrspacetowerdefense.model.ScenePoint
import com.picoxr.mrspacetowerdefense.model.SceneRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class GridSystemTest {
    @Test
    fun `warning duration remains exactly one second`() {
        assertEquals(1f, GridRules.WARNING_DURATION_SECONDS, 0f)
        assertEquals(2f, GridRules.WARNING_FLASHES_PER_SECOND, 0f)
    }

    @Test
    fun `warning plus retracted cooldown equals configured movement interval`() {
        assertEquals(5f, GridRules.rayTriggerDelaySeconds(6f), 0.0001f)
        assertEquals(4.333f, GridRules.rayTriggerDelaySeconds(5.333f), 0.0001f)
        assertEquals(2f, GridRules.rayTriggerDelaySeconds(3f), 0.0001f)
        assertEquals(6f, GridRules.WARNING_DURATION_SECONDS + GridRules.rayTriggerDelaySeconds(6f), 0.0001f)
        assertEquals(1f, GridRules.BEAM_ACTIVE_DURATION_SECONDS, 0f)
    }

    @Test
    fun `permanent survival bonuses extend warning and movement window`() {
        val warning = GridRules.warningDurationSeconds(0.25f)
        val cooldown = GridRules.rayTriggerDelaySeconds(6f, safeWindowBonus = 0.20f, warningDurationSeconds = warning)
        assertEquals(1.25f, warning, 0.0001f)
        assertEquals(7.2f, warning + cooldown, 0.0001f)
    }

    @Test
    fun `next safe grid never repeats the previous index`() {
        for (previous in 0 until GridRules.GRID_COUNT) {
            for (randomValue in 0 until 100) {
                val next = GridRules.nextSafeIndex(previous, randomValue)
                assertNotEquals(previous, next)
                assertTrue(next in 0..8)
            }
        }
    }

    @Test
    fun `first safe grid accepts all nine indices`() {
        assertEquals((0..8).toSet(), (0..8).map { GridRules.nextSafeIndex(null, it) }.toSet())
    }

    @Test
    fun `horizontal grid lookup finds a cell and treats outside as unsafe`() {
        val cells =
            List(9) { index ->
                val row = index / 3
                val column = index % 3
                SceneRect(
                    center = ScenePoint((column - 1) * 0.8f, 42f, (row - 1) * 0.8f),
                    width = 0.8f,
                    depth = 0.8f,
                )
            }
        assertEquals(4, GridRules.cellIndexAt(cells, 0f, 0f))
        assertEquals(8, GridRules.cellIndexAt(cells, 0.8f, 0.8f))
        assertNull(GridRules.cellIndexAt(cells, 2f, 0f))
        assertTrue(GridRules.isInsideActivityArea(cells, -1.19f, 1.19f))
        assertFalse(GridRules.isInsideActivityArea(cells, -1.21f, 0f))
    }

    @Test
    fun `rotated safe cell tests the complete HMD helmet in local axes`() {
        val cell =
            SceneRect(
                center = ScenePoint(1f, 0f, 2f),
                width = 0.8f,
                depth = 0.8f,
                rotationYRadians = (PI / 4.0).toFloat(),
            )

        assertTrue(cell.containsCircleHorizontal(1f, 2f, 0.12f))
        assertFalse(cell.containsCircleHorizontal(1.45f, 2f, 0.12f))
    }

    @Test
    fun `helmet dies only when its horizontal proxy touches a rendered dangerous pillar`() {
        val cells =
            List(9) { index ->
                val row = index / 3
                val column = index % 3
                SceneRect(
                    center = ScenePoint((column - 1) * 0.8f, 0f, (row - 1) * 0.8f),
                    width = 0.8f,
                    depth = 0.8f,
                )
            }

        assertNull(
            GridRules.touchedDangerBeamIndex(cells, 4, 0f, 0f, 0.12f),
        )
        // Outside the green cell but in empty space between the visible pillars is safe.
        assertNull(
            GridRules.touchedDangerBeamIndex(cells, 4, 0.4f, 0f, 0.12f),
        )
        assertEquals(
            5,
            GridRules.touchedDangerBeamIndex(cells, 4, 0.8f, 0f, 0.12f),
        )
        assertTrue(GridRules.isLethalBeamContact(5))
        assertFalse(GridRules.isLethalBeamContact(null))
        assertEquals(
            GridRules.BEAM_DIAMETER_METERS,
            0.5f,
            0f,
        )
    }

    @Test
    fun `beam rise reaches full height in point three seconds`() {
        assertEquals(0f, GridRules.beamProgress(0f), 0.0001f)
        assertEquals(0.5f, GridRules.beamProgress(0.15f), 0.0001f)
        assertEquals(1f, GridRules.beamProgress(0.3f), 0.0001f)
        assertEquals(1f, GridRules.beamProgress(1f), 0.0001f)
    }

    @Test
    fun `warning flashes twice per second`() {
        assertTrue(GridRules.warningFlashOn(0f))
        assertFalse(GridRules.warningFlashOn(0.25f))
        assertTrue(GridRules.warningFlashOn(0.5f))
        assertFalse(GridRules.warningFlashOn(0.75f))
        assertTrue(GridRules.warningFlashOn(1f))
    }
}
