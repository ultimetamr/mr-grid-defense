package com.picoxr.mrspacetowerdefense.manager

import kotlin.math.max
import kotlin.math.min

/** Centralized physical tuning for procedural combat entities and the HMD hazard proxy. */
object GameplayTuning {
    const val COMBAT_MODEL_SCALE = 0.1f
    const val IMPORTED_MONSTER_ADDITIONAL_DOWNSCALE = 100f
    const val IMPORTED_MONSTER_MODEL_SCALE =
        COMBAT_MODEL_SCALE / IMPORTED_MONSTER_ADDITIONAL_DOWNSCALE
    const val MONSTER_SPEED_SCALE = 0.5f
    const val MIN_MONSTER_GAP_METERS = 0.03f
    const val HMD_HELMET_RADIUS_METERS = 0.12f
    const val TOWER_INTERACTION_RADIUS_METERS = 0.08f
    const val GROUND_SURFACE_CLEARANCE_METERS = 0.005f

    fun modelSize(originalMeters: Float): Float = originalMeters * COMBAT_MODEL_SCALE

    fun monsterMoveSpeed(baseSpeed: Float, waveMultiplier: Float): Float =
        baseSpeed * waveMultiplier * MONSTER_SPEED_SCALE

    fun monsterTravelSeconds(distanceMeters: Float, baseSpeed: Float, waveMultiplier: Float = 1f): Float {
        require(distanceMeters >= 0f && distanceMeters.isFinite())
        return distanceMeters / monsterMoveSpeed(baseSpeed, waveMultiplier)
    }

    /** Keeps tiny procedural models visibly above the depth-tested real floor. */
    fun groundedBaseY(committedGroundHeight: Float): Float =
        committedGroundHeight + GROUND_SURFACE_CLEARANCE_METERS

    fun circlesOverlap(
        firstX: Float,
        firstZ: Float,
        firstRadius: Float,
        secondX: Float,
        secondZ: Float,
        secondRadius: Float,
        gap: Float = MIN_MONSTER_GAP_METERS,
    ): Boolean {
        val dx = firstX - secondX
        val dz = firstZ - secondZ
        val minimumDistance = firstRadius + secondRadius + gap
        return dx * dx + dz * dz < minimumDistance * minimumDistance
    }

    fun circleInsideRectangle(
        circleX: Float,
        circleZ: Float,
        radius: Float,
        centerX: Float,
        centerZ: Float,
        width: Float,
        depth: Float,
    ): Boolean {
        val left = centerX - width / 2f
        val right = centerX + width / 2f
        val back = centerZ - depth / 2f
        val front = centerZ + depth / 2f
        return circleX - radius >= min(left, right) &&
            circleX + radius <= max(left, right) &&
            circleZ - radius >= min(back, front) &&
            circleZ + radius <= max(back, front)
    }
}
