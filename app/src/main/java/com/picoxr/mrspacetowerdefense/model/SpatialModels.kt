package com.picoxr.mrspacetowerdefense.model

import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class GroundBounds(
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float,
) {
    init {
        require(minX <= maxX) { "minX must not exceed maxX" }
        require(minZ <= maxZ) { "minZ must not exceed maxZ" }
    }

    val width: Float get() = maxX - minX
    val depth: Float get() = maxZ - minZ

    fun contains(x: Float, z: Float): Boolean = x in minX..maxX && z in minZ..maxZ
}

data class GroundSurface(
    val anchorId: UUID,
    val height: Float,
    val bounds: GroundBounds,
    val area: Float,
)

data class ObstacleBounds(
    val anchorId: UUID,
    val bounds: GroundBounds,
) {
    fun contains(x: Float, z: Float): Boolean = bounds.contains(x, z)
}

data class ScenePoint(
    val x: Float,
    val y: Float,
    val z: Float,
)

data class SceneRect(
    val center: ScenePoint,
    val width: Float,
    val depth: Float,
    val rotationYRadians: Float = 0f,
) {
    fun containsHorizontal(x: Float, z: Float): Boolean {
        val dx = x - center.x
        val dz = z - center.z
        val localRight = dx * cos(rotationYRadians) + dz * sin(rotationYRadians)
        val localForward = dx * sin(rotationYRadians) - dz * cos(rotationYRadians)
        return abs(localRight) <= width / 2f && abs(localForward) <= depth / 2f
    }

    fun containsCircleHorizontal(x: Float, z: Float, radius: Float): Boolean {
        require(radius >= 0f && radius.isFinite())
        val dx = x - center.x
        val dz = z - center.z
        val localRight = dx * cos(rotationYRadians) + dz * sin(rotationYRadians)
        val localForward = dx * sin(rotationYRadians) - dz * cos(rotationYRadians)
        return abs(localRight) + radius <= width / 2f &&
            abs(localForward) + radius <= depth / 2f
    }

    fun pointAt(lateralMeters: Float, forwardMeters: Float = 0f): ScenePoint {
        val rightX = cos(rotationYRadians)
        val rightZ = sin(rotationYRadians)
        val forwardX = sin(rotationYRadians)
        val forwardZ = -cos(rotationYRadians)
        return ScenePoint(
            x = center.x + rightX * lateralMeters + forwardX * forwardMeters,
            y = center.y,
            z = center.z + rightZ * lateralMeters + forwardZ * forwardMeters,
        )
    }
}

data class SceneLayout(
    val wall: SceneRect,
    val wallHeight: Float,
    val wallWeaponMounts: List<ScenePoint>,
    val safeGridCells: List<SceneRect>,
    val towerPlacementZone: SceneRect,
    val monsterSpawnBoundary: SceneRect,
)
