package com.picoxr.mrspacetowerdefense.model

import com.pico.spatial.core.math.Vector3

/** Runtime bridge implemented by the future MonsterManager. Positions use scene-root meters. */
interface MonsterCombatTarget {
    val id: String
    val type: MonsterType
    val killGoldReward: Int
    val hitRadius: Float

    fun worldPosition(): Vector3

    fun isAlive(): Boolean

    fun applyDamage(damage: Float): MonsterDamageResult

    fun applySlow(speedMultiplier: Float, durationSeconds: Float) = Unit

    fun applyStun(durationSeconds: Float) = Unit

    fun applyBurn(
        damagePerSecond: Float,
        durationSeconds: Float,
        speedMultiplier: Float,
        igniteOnKill: Boolean = false,
    ) = Unit

    fun consumeIgniteOnDeath(): Boolean = false

    /** Prevents projectile, DOT and wall reflection paths from granting the same reward twice. */
    fun claimKillReward(): Boolean = true
}

data class MonsterDamageResult(
    val appliedDamage: Float,
    val remainingHp: Float,
    val wasKilled: Boolean,
)
