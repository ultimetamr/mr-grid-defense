package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.SceneLayoutCalculator
import com.picoxr.mrspacetowerdefense.model.GroundBounds
import com.picoxr.mrspacetowerdefense.model.ObstacleBounds
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLayoutCalculatorTest {
    @Test
    fun `layout preserves required grounded distances and dimensions`() {
        val layout =
            SceneLayoutCalculator.calculate(
                groundHeight = 0.12f,
                activityBounds = GroundBounds(-2.5f, 2.5f, -8f, 2f),
            )

        assertEquals(-2f, layout.wall.center.z, 0.0001f)
        assertEquals(0.12f, layout.wall.center.y, 0.0001f)
        assertEquals(0.4f, layout.wallHeight, 0.0001f)
        assertEquals(9, layout.wallWeaponMounts.size)
        layout.wallWeaponMounts.forEach { mount ->
            assertEquals(0.535f, mount.y, 0.0001f)
            assertEquals(layout.wall.center.z, mount.z, 0.0001f)
        }
        assertEquals(layout.wall.center.x, layout.wallWeaponMounts[0].x, 0.0001f)
        assertTrue(layout.wallWeaponMounts[1].x < layout.wall.center.x)
        assertTrue(layout.wallWeaponMounts[2].x > layout.wall.center.x)
        assertTrue(
            layout.wallWeaponMounts.all {
                it.x in
                    (layout.wall.center.x - layout.wall.width / 2f)..
                        (layout.wall.center.x + layout.wall.width / 2f)
            },
        )
        assertEquals(9, layout.safeGridCells.size)
        assertTrue(layout.safeGridCells.all { it.width == 0.8f && it.depth == 0.8f })
        assertTrue(layout.safeGridCells.all { it.center.y == 0.12f })
        val frontRowCenter = layout.safeGridCells.first().center.z
        val frontGridEdge = frontRowCenter - SceneLayoutCalculator.GRID_CELL_SIZE / 2f
        val playerFacingWallEdge = layout.wall.center.z + layout.wall.depth / 2f
        assertEquals(
            SceneLayoutCalculator.GRID_TO_WALL_CLEARANCE,
            frontGridEdge - playerFacingWallEdge,
            0.0001f,
        )
        assertEquals(1f, layout.towerPlacementZone.depth, 0.0001f)
        assertEquals(-7f, layout.monsterSpawnBoundary.center.z, 0.0001f)
        assertEquals(5f, layout.wall.center.z - layout.monsterSpawnBoundary.center.z, 0.0001f)
        assertTrue(SceneLayoutCalculator.wallSlotPadWidth(layout.wall.width) > 0f)
    }

    @Test
    fun `spawn boundary remains centered on the calibrated player`() {
        val layout =
            SceneLayoutCalculator.calculate(
                groundHeight = 0f,
                activityBounds = GroundBounds(-1f, 5f, -7f, 2f),
            )

        assertEquals(0f, layout.monsterSpawnBoundary.center.x, 0.0001f)
        assertEquals(1.8f, layout.monsterSpawnBoundary.width, 0.0001f)
    }

    @Test
    fun `layout rotates to the horizontal HMD forward direction`() {
        val layout =
            SceneLayoutCalculator.calculate(
                groundHeight = 0.2f,
                activityBounds = GroundBounds(-4f, 8f, -4f, 8f),
                playerX = 1f,
                playerZ = 2f,
                playerForwardX = 1f,
                playerForwardZ = 0f,
            )

        assertEquals(3f, layout.wall.center.x, 0.0001f)
        assertEquals(2f, layout.wall.center.z, 0.0001f)
        assertEquals((Math.PI / 2.0).toFloat(), layout.wall.rotationYRadians, 0.0001f)
        assertEquals(1.63f, layout.safeGridCells[4].center.x, 0.0001f)
        assertEquals(2f, layout.safeGridCells[4].center.z, 0.0001f)
        assertTrue(layout.safeGridCells.all { it.center.y == 0.2f })
        val centerCell = layout.safeGridCells[4]
        layout.safeGridCells.forEachIndexed { index, cell ->
            val row = index / 3
            val column = index % 3
            val expected =
                centerCell.pointAt(
                    lateralMeters = (column - 1) * SceneLayoutCalculator.GRID_CELL_SIZE,
                    forwardMeters = (1 - row) * SceneLayoutCalculator.GRID_CELL_SIZE,
                )
            assertEquals(expected.x, cell.center.x, 0.0001f)
            assertEquals(expected.z, cell.center.z, 0.0001f)
            assertEquals(centerCell.rotationYRadians, cell.rotationYRadians, 0.0001f)
        }
        // A wall facing +X has its width axis along world Z. Every weapon must remain
        // on that exact wall line rather than forming a diagonal global-X row.
        assertTrue(layout.wallWeaponMounts.all { kotlin.math.abs(it.x - layout.wall.center.x) < 0.0001f })
        val sortedZ = layout.wallWeaponMounts.map { it.z }.sorted()
        sortedZ.zipWithNext().forEach { (left, right) ->
            assertEquals(layout.wall.width / 9f, right - left, 0.0001f)
        }
    }

    @Test
    fun `monster boundary keeps five metre wall distance on a short detected floor`() {
        val bounds = GroundBounds(-3f, 3f, -3f, 3f)
        val layout =
            SceneLayoutCalculator.calculate(
                groundHeight = 0f,
                activityBounds = bounds,
            )

        assertEquals(-7f, layout.monsterSpawnBoundary.center.z, 0.0001f)
        assertEquals(
            SceneLayoutCalculator.SPAWN_DISTANCE_FROM_WALL,
            layout.wall.center.z - layout.monsterSpawnBoundary.center.z,
            0.0001f,
        )
    }

    @Test
    fun `ground and obstacle rectangles include their edges`() {
        val bounds = GroundBounds(-1f, 1f, -2f, 2f)
        val obstacle = ObstacleBounds(UUID.randomUUID(), bounds)

        assertTrue(bounds.contains(1f, 2f))
        assertTrue(obstacle.contains(0f, 0f))
        assertFalse(obstacle.contains(1.01f, 0f))
    }
}
