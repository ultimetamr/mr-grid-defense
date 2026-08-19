package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.MonsterBehavior
import com.picoxr.mrspacetowerdefense.model.MonsterConfig

internal object MonsterAttackRules {
    /** Melee bodies touch the wall surface; only ranged monsters retain standoff range. */
    fun stopDistance(config: MonsterConfig, hitRadius: Float, wallDepth: Float): Float {
        require(hitRadius >= 0f && wallDepth >= 0f)
        val surfaceDistance = wallDepth / 2f + hitRadius
        return if (config.behavior == MonsterBehavior.RANGED) {
            surfaceDistance + config.attackRange
        } else {
            surfaceDistance
        }
    }

    fun attacksWithProjectile(config: MonsterConfig): Boolean =
        config.behavior == MonsterBehavior.RANGED
}
