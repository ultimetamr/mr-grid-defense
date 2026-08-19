package com.picoxr.mrspacetowerdefense.model

data class WallState(
    val maxHp: Int,
    val currentHp: Int,
    val level: Int,
    val damageReductionLevel: Int = 0,
    val reflectionLevel: Int = 0,
    val regenerationLevel: Int = 0,
) {
    init {
        require(maxHp > 0) { "Wall max HP must be greater than zero" }
        require(currentHp in 0..maxHp) { "Wall current HP must be between zero and max HP" }
        require(level >= 1) { "Wall level must be at least one" }
        require(damageReductionLevel in 0..5)
        require(reflectionLevel in 0..5)
        require(regenerationLevel in 0..3)
    }
}

data class WallDamageResult(
    val incomingDamage: Float,
    val appliedDamage: Int,
    val reflectedDamage: Float,
    val remainingHp: Int,
)
