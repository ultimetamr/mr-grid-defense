package com.picoxr.mrspacetowerdefense.model

enum class TowerType {
    ARCHER,
    CROSSBOW,
    CANNON,
    FROST,
    BURN,
    TOTEM,
}

enum class TowerRole { OUTPUT, SUPPORT }

data class TowerConfig(
    val type: TowerType,
    val name: String,
    val cost: Int,
    val damage: Float,
    val attackSpeed: Float,
    val attackRange: Float,
    val splashRadius: Float,
    val maxLevel: Int,
    val damageBonusPerLevel: Float,
    val attackSpeedBonusPerLevel: Float,
    val height: Float = 1f,
    val role: TowerRole = TowerRole.OUTPUT,
    val description: String,
) {
    init {
        require(name.isNotBlank()) { "Tower name cannot be blank" }
        require(cost >= 0) { "Tower cost cannot be negative" }
        require(damage >= 0f) { "Tower damage cannot be negative" }
        require(attackSpeed > 0f) { "Tower attack speed must be greater than zero" }
        require(attackRange > 0f) { "Tower attack range must be greater than zero" }
        require(splashRadius >= 0f) { "Tower splash radius cannot be negative" }
        require(maxLevel >= 1) { "Tower max level must be at least one" }
        require(damageBonusPerLevel >= 0f) { "Damage bonus cannot be negative" }
        require(attackSpeedBonusPerLevel >= 0f) { "Attack-speed bonus cannot be negative" }
        require(height > 0f) { "Tower height must be greater than zero" }
        require(description.isNotBlank()) { "Tower description cannot be blank" }
    }
}
