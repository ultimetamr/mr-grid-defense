package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.GroundBounds
import com.picoxr.mrspacetowerdefense.model.SceneLayout
import com.picoxr.mrspacetowerdefense.model.ScenePoint
import com.picoxr.mrspacetowerdefense.model.SceneRect
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object SceneLayoutCalculator {
    const val WALL_DISTANCE_FROM_PLAYER = 2f
    const val WALL_HEIGHT = 0.4f
    const val WALL_DEPTH = 0.18f
    const val WALL_SLOT_PAD_HEIGHT = 0.015f
    const val WALL_SLOT_PAD_DEPTH = 0.14f
    const val GRID_TO_WALL_CLEARANCE = 0.08f
    const val WALL_WEAPON_SLOT_COUNT = WallWeaponSlotRules.MAX_SLOTS
    const val GRID_CELL_SIZE = 0.8f
    const val GRID_SIZE = GRID_CELL_SIZE * 3f
    const val TOWER_ZONE_DEPTH = 1f
    const val SPAWN_DISTANCE_FROM_WALL = 5f
    private const val GROUND_EDGE_MARGIN = 0.2f

    fun calculate(
        groundHeight: Float,
        activityBounds: GroundBounds,
        playerX: Float = 0f,
        playerZ: Float = 0f,
        playerForwardX: Float = 0f,
        playerForwardZ: Float = -1f,
    ): SceneLayout {
        val forwardLength = sqrt(playerForwardX * playerForwardX + playerForwardZ * playerForwardZ)
        val forwardX = if (forwardLength > 0.0001f) playerForwardX / forwardLength else 0f
        val forwardZ = if (forwardLength > 0.0001f) playerForwardZ / forwardLength else -1f
        val yawRadians = atan2(forwardX, -forwardZ)
        val rightX = cos(yawRadians)
        val rightZ = sin(yawRadians)
        val pointAt: (Float, Float) -> ScenePoint = { lateral, forward ->
            ScenePoint(
                x = playerX + rightX * lateral + forwardX * forward,
                y = groundHeight,
                z = playerZ + rightZ * lateral + forwardZ * forward,
            )
        }
        val centeredWidth =
            2f * minOf(
                distanceToBounds(playerX, playerZ, rightX, rightZ, activityBounds),
                distanceToBounds(playerX, playerZ, -rightX, -rightZ, activityBounds),
            )
        val availableWidth = (centeredWidth - 0.2f).coerceAtLeast(GRID_SIZE)
        val wallWidth = availableWidth.coerceAtMost(6f)
        val wall =
            SceneRect(
                center = pointAt(0f, WALL_DISTANCE_FROM_PLAYER),
                width = wallWidth,
                depth = WALL_DEPTH,
                rotationYRadians = yawRadians,
            )
        // Monster pacing is defined from the wall, not from the detected plane extent.
        // Plane bounds can be temporarily short during calibration; clamping here used to
        // collapse a requested five-metre route to only 0.6 m on real devices.
        val spawnDistance = WALL_DISTANCE_FROM_PLAYER + SPAWN_DISTANCE_FROM_WALL
        val spawnCenter = pointAt(0f, spawnDistance)
        val spawnCenteredWidth =
            2f * minOf(
                distanceToBounds(spawnCenter.x, spawnCenter.z, rightX, rightZ, activityBounds),
                distanceToBounds(spawnCenter.x, spawnCenter.z, -rightX, -rightZ, activityBounds),
            )
        val spawnWidth =
            (spawnCenteredWidth - GROUND_EDGE_MARGIN)
                .coerceAtLeast(GRID_CELL_SIZE)
                .coerceAtMost(wallWidth)

        val cells =
            buildList(9) {
                // Keep the complete activity grid behind the wall's player-facing surface.
                // Previously the front row ended on the wall centreline and visibly intersected
                // half of the wall depth, which also made wall-mounted weapons look misplaced.
                val gridCenterDistance =
                    WALL_DISTANCE_FROM_PLAYER -
                        WALL_DEPTH / 2f -
                        GRID_TO_WALL_CLEARANCE -
                        GRID_SIZE / 2f
                for (row in 0 until 3) {
                    for (column in 0 until 3) {
                        add(
                            SceneRect(
                                center = pointAt(
                                    (column - 1) * GRID_CELL_SIZE,
                                    gridCenterDistance + (1 - row) * GRID_CELL_SIZE,
                                ),
                                width = GRID_CELL_SIZE,
                                depth = GRID_CELL_SIZE,
                                rotationYRadians = yawRadians,
                            ),
                        )
                    }
                }
            }

        return SceneLayout(
            wall = wall,
            wallHeight = WALL_HEIGHT,
            wallWeaponMounts =
                wallWeaponMounts(
                    wall = wall,
                    wallTopY = groundHeight + WALL_HEIGHT + WALL_SLOT_PAD_HEIGHT,
                ),
            safeGridCells = cells,
            towerPlacementZone =
                SceneRect(
                    center = pointAt(0f, WALL_DISTANCE_FROM_PLAYER + TOWER_ZONE_DEPTH / 2f),
                    width = wallWidth,
                    depth = TOWER_ZONE_DEPTH,
                    rotationYRadians = yawRadians,
                ),
            monsterSpawnBoundary =
                SceneRect(
                    center = spawnCenter,
                    width = spawnWidth,
                    depth = 0f,
                    rotationYRadians = yawRadians,
                ),
        )
    }

    /** Center-first mounting order keeps every purchase visually balanced on the wall. */
    private fun wallWeaponMounts(
        wall: SceneRect,
        wallTopY: Float,
    ): List<ScenePoint> {
        val slotSpacing = wall.width / WALL_WEAPON_SLOT_COUNT
        return List(WALL_WEAPON_SLOT_COUNT) { placementIndex ->
            wall.pointAt(
                WallWeaponSlotRules.lateralOffset(placementIndex, slotSpacing),
            ).copy(y = wallTopY)
        }
    }

    fun wallSlotPadWidth(wallWidth: Float): Float =
        (wallWidth / WALL_WEAPON_SLOT_COUNT * 0.62f).coerceIn(0.12f, 0.28f)

    private fun distanceToBounds(
        x: Float,
        z: Float,
        directionX: Float,
        directionZ: Float,
        bounds: GroundBounds,
    ): Float {
        var distance = Float.POSITIVE_INFINITY
        if (directionX > 0.0001f) distance = minOf(distance, (bounds.maxX - x) / directionX)
        if (directionX < -0.0001f) distance = minOf(distance, (bounds.minX - x) / directionX)
        if (directionZ > 0.0001f) distance = minOf(distance, (bounds.maxZ - z) / directionZ)
        if (directionZ < -0.0001f) distance = minOf(distance, (bounds.minZ - z) / directionZ)
        return distance.coerceAtLeast(0f)
    }
}
