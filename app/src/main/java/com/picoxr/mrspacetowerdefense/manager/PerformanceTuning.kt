package com.picoxr.mrspacetowerdefense.manager

/** Pure scheduling rules shared by frame systems and covered by host-side tests. */
object PerformanceTuning {
    const val MONSTER_LOD_THRESHOLD = 20
    const val FAR_MONSTER_DISTANCE_METERS = 3f
    const val FAR_MONSTER_UPDATE_STRIDE = 3
    const val RAYCAST_INTERVAL_SECONDS = 0.1f
    const val NON_COMBAT_UPDATE_INTERVAL_NANOS = 33_333_333L

    fun shouldUpdateMonster(
        activeCount: Int,
        distanceSquaredToPlayer: Float,
        frameIndex: Long,
        bucket: Int,
    ): Boolean {
        if (activeCount <= MONSTER_LOD_THRESHOLD) return true
        if (distanceSquaredToPlayer <= FAR_MONSTER_DISTANCE_METERS * FAR_MONSTER_DISTANCE_METERS) return true
        return (frameIndex + bucket) % FAR_MONSTER_UPDATE_STRIDE == 0L
    }
}

class FrameRateGovernor {
    private var lastNonCombatUpdateNanos = 0L

    fun shouldRun(
        combatState: Boolean,
        applicationActive: Boolean = true,
        nowNanos: Long = System.nanoTime(),
    ): Boolean {
        if (!applicationActive) {
            lastNonCombatUpdateNanos = 0L
            return false
        }
        if (combatState) {
            lastNonCombatUpdateNanos = nowNanos
            return true
        }
        if (lastNonCombatUpdateNanos == 0L ||
            nowNanos - lastNonCombatUpdateNanos >= PerformanceTuning.NON_COMBAT_UPDATE_INTERVAL_NANOS
        ) {
            lastNonCombatUpdateNanos = nowNanos
            return true
        }
        return false
    }
}
