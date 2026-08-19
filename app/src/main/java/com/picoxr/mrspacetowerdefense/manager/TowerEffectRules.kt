package com.picoxr.mrspacetowerdefense.manager

/** Pure combat-effect definitions shared by runtime attack code and unit verification. */
data class SlowEffect(
    val speedMultiplier: Float,
    val durationSeconds: Float,
)

data class FrostPulseEffect(
    val slow: SlowEffect,
    val freezeChance: Float,
    val freezeDurationSeconds: Float,
)

data class BurnEffect(
    val damagePerSecond: Float,
    val durationSeconds: Float,
    val speedMultiplier: Float,
    val igniteOnKill: Boolean,
)

data class TotemAuraEffect(
    val rangeMeters: Float,
    val damageBonus: Float,
    val attackSpeedBonus: Float,
)

object TowerEffectRules {
    private const val MAX_LEVEL = 5

    fun crossbowSlow(level: Int): SlowEffect? {
        requireLevel(level)
        return if (level >= MAX_LEVEL) SlowEffect(0.75f, 2f) else null
    }

    fun cannonStunSeconds(level: Int): Float {
        requireLevel(level)
        return if (level >= MAX_LEVEL) 1f else 0f
    }

    fun frost(level: Int): FrostPulseEffect {
        requireLevel(level)
        return if (level >= MAX_LEVEL) {
            FrostPulseEffect(SlowEffect(0.5f, 1.25f), 0.25f, 1.5f)
        } else {
            FrostPulseEffect(SlowEffect(0.7f, 1.25f), 0.10f, 1f)
        }
    }

    fun burn(level: Int): BurnEffect {
        requireLevel(level)
        return if (level >= MAX_LEVEL) {
            BurnEffect(16f, 3f, 0.8f, igniteOnKill = true)
        } else {
            BurnEffect(8f, 3f, 0.9f, igniteOnKill = false)
        }
    }

    fun totem(level: Int): TotemAuraEffect {
        requireLevel(level)
        return if (level >= MAX_LEVEL) {
            TotemAuraEffect(3.5f, 0.35f, 0.25f)
        } else {
            TotemAuraEffect(2f, 0.20f, 0.15f)
        }
    }

    private fun requireLevel(level: Int) {
        require(level in 1..MAX_LEVEL) { "Tower level must be in 1..$MAX_LEVEL" }
    }
}
