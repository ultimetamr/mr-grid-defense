package com.picoxr.mrspacetowerdefense.manager

import kotlin.math.ceil

object WallEconomyRules {
    const val INITIAL_MAX_HP = 100
    const val HP_PER_LEVEL = 50
    const val MAX_LEVEL = 5
    const val REPAIR_COST = 80
    const val DAMAGE_REDUCTION_MAX_LEVEL = 5
    const val REFLECTION_MAX_LEVEL = 5
    const val REGENERATION_MAX_LEVEL = 3

    fun upgradeCost(currentLevel: Int): Int {
        require(currentLevel in 1..MAX_LEVEL)
        return 100 * currentLevel
    }

    fun damageReductionUpgradeCost(currentLevel: Int): Int {
        require(currentLevel in 0 until DAMAGE_REDUCTION_MAX_LEVEL)
        return 150 * (currentLevel + 1)
    }

    fun reflectionUpgradeCost(currentLevel: Int): Int {
        require(currentLevel in 0 until REFLECTION_MAX_LEVEL)
        return 120 * (currentLevel + 1)
    }

    fun regenerationUpgradeCost(currentLevel: Int): Int {
        require(currentLevel in 0 until REGENERATION_MAX_LEVEL)
        return 200 * (currentLevel + 1)
    }

    fun sessionDamageReduction(level: Int): Float = level.coerceIn(0, 5) * 0.10f

    fun reflectionRatio(level: Int): Float = level.coerceIn(0, 5) * 0.05f

    /** Literal product rule: restore 50% of the wall's current HP, capped by max HP. */
    fun repairedHp(currentHp: Int, maxHp: Int, efficiencyBonus: Float = 0f): Int {
        require(currentHp in 0..maxHp)
        val recovered = ceil(currentHp * 0.5f * (1f + efficiencyBonus.coerceAtLeast(0f))).toInt()
        return (currentHp + recovered).coerceAtMost(maxHp)
    }
}
