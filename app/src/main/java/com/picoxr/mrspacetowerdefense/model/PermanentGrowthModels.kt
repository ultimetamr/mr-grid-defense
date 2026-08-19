package com.picoxr.mrspacetowerdefense.model

enum class PermanentUpgradeCategory(val displayName: String) {
    ECONOMY("经济"),
    DEFENSE("防御"),
    ATTACK("攻击"),
    SURVIVAL("生存"),
}

enum class PermanentUpgradeType(val category: PermanentUpgradeCategory) {
    STARTING_GOLD(PermanentUpgradeCategory.ECONOMY),
    KILL_GOLD(PermanentUpgradeCategory.ECONOMY),
    WAVE_REWARD(PermanentUpgradeCategory.ECONOMY),
    WALL_INITIAL_HP(PermanentUpgradeCategory.DEFENSE),
    WALL_DAMAGE_REDUCTION(PermanentUpgradeCategory.DEFENSE),
    REPAIR_EFFICIENCY(PermanentUpgradeCategory.DEFENSE),
    TOWER_DAMAGE(PermanentUpgradeCategory.ATTACK),
    TOWER_ATTACK_SPEED(PermanentUpgradeCategory.ATTACK),
    TOWER_RANGE(PermanentUpgradeCategory.ATTACK),
    RAY_WARNING(PermanentUpgradeCategory.SURVIVAL),
    DEATH_SHIELD(PermanentUpgradeCategory.SURVIVAL),
    SAFE_GRID_WINDOW(PermanentUpgradeCategory.SURVIVAL),
}

data class PermanentUpgradeConfig(
    val type: PermanentUpgradeType,
    val name: String,
    val description: String,
    val bonusPerLevel: Float,
    val baseCost: Int,
    val costStep: Int,
    val maxLevel: Int = 10,
) {
    init {
        require(name.isNotBlank())
        require(description.isNotBlank())
        require(bonusPerLevel >= 0f)
        require(baseCost > 0)
        require(costStep > 0)
        require(maxLevel > 0)
    }
}

/** Immutable, allocation-free runtime snapshot calculated whenever the save changes. */
data class PermanentBonusSnapshot(
    val startingGoldBonus: Float = 0f,
    val killGoldBonus: Float = 0f,
    val waveRewardBonus: Float = 0f,
    val wallInitialHpBonus: Float = 0f,
    val wallDamageReduction: Float = 0f,
    val repairEfficiencyBonus: Float = 0f,
    val towerDamageBonus: Float = 0f,
    val towerAttackSpeedBonus: Float = 0f,
    val towerRangeBonus: Float = 0f,
    val warningDurationBonus: Float = 0f,
    val deathShieldCharges: Int = 0,
    val safeGridWindowBonus: Float = 0f,
)

