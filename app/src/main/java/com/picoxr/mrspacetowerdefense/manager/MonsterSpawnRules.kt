package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.ScenePoint
import com.picoxr.mrspacetowerdefense.model.SceneRect
import kotlin.math.cos
import kotlin.math.sin

/** Allocation-light pseudo-random spawn sampling with deterministic retry coverage. */
object MonsterSpawnRules {
    const val CANDIDATE_COUNT = 64
    const val MAX_DEPTH_RETREAT_METERS = 1.25f
    const val MIN_SPAWN_CENTER_DISTANCE_METERS = 0.30f
    const val WALL_TARGET_CONVERGENCE_RATIO = 0.60f
    private const val EDGE_PADDING_RATIO = 0.08f

    fun candidate(
        boundary: SceneRect,
        spawnOrdinal: Int,
        attemptIndex: Int,
        maxDepthRetreatMeters: Float = MAX_DEPTH_RETREAT_METERS,
    ): ScenePoint {
        require(spawnOrdinal >= 0)
        require(attemptIndex in 0 until CANDIDATE_COUNT)
        require(maxDepthRetreatMeters >= 0f && maxDepthRetreatMeters.isFinite())
        val lateralUnit = randomUnit(spawnOrdinal, attemptIndex, 0x13579BDF)
        val depthUnit = randomUnit(spawnOrdinal, attemptIndex, 0x02468ACE)
        val usableHalfWidth = boundary.width * 0.5f * (1f - EDGE_PADDING_RATIO)
        val lateral = (lateralUnit * 2f - 1f) * usableHalfWidth
        // Negative local-forward values retreat from the outer boundary toward the wall,
        // producing a two-dimensional spawn band instead of one straight row.
        return boundary.pointAt(lateral, -depthUnit * maxDepthRetreatMeters)
    }

    /** Keeps each monster moving toward the wall lane corresponding to its spawn side. */
    fun wallTargetForSpawn(
        wall: SceneRect,
        spawnBoundary: SceneRect,
        spawnPoint: ScenePoint,
        edgePaddingMeters: Float,
    ): ScenePoint {
        require(edgePaddingMeters >= 0f && edgePaddingMeters.isFinite())
        val dx = spawnPoint.x - spawnBoundary.center.x
        val dz = spawnPoint.z - spawnBoundary.center.z
        val spawnLateral =
            dx * cos(spawnBoundary.rotationYRadians) + dz * sin(spawnBoundary.rotationYRadians)
        val normalizedLateral =
            if (spawnBoundary.width <= 0.0001f) 0f else {
                (spawnLateral / (spawnBoundary.width * 0.5f)).coerceIn(-1f, 1f)
            }
        val targetHalfWidth =
            (wall.width * 0.5f - edgePaddingMeters).coerceAtLeast(0f) *
                WALL_TARGET_CONVERGENCE_RATIO
        return wall.pointAt(normalizedLateral * targetHalfWidth)
    }

    private fun randomUnit(spawnOrdinal: Int, attemptIndex: Int, salt: Int): Float {
        var value = spawnOrdinal * 73_856_093 xor attemptIndex * 19_349_663 xor salt
        value = value xor (value ushr 16)
        value *= 0x045D9F3B
        value = value xor (value ushr 16)
        return (value and Int.MAX_VALUE).toFloat() / Int.MAX_VALUE.toFloat()
    }
}
