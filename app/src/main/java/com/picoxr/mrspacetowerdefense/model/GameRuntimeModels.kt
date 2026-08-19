package com.picoxr.mrspacetowerdefense.model

enum class CombatPauseReason {
    APP_BACKGROUND,
    TRACKING_LOST,
    OUT_OF_BOUNDS,
}

data class PermanentProgress(
    val totalCrystals: Int = 0,
    val upgradeLevels: Map<PermanentUpgradeType, Int> = emptyMap(),
    val highestWave: Int = 0,
    val highestKills: Int = 0,
) {
    init {
        require(totalCrystals >= 0)
        require(upgradeLevels.all { (type, level) -> type in PermanentUpgradeType.entries && level in 0..10 })
        require(highestWave >= 0)
        require(highestKills >= 0)
    }

    fun levelOf(type: PermanentUpgradeType): Int = upgradeLevels[type] ?: 0

    val totalUpgradeLevels: Int get() = upgradeLevels.values.sum()
}

data class GameRuntimeState(
    val pauseReasons: Set<CombatPauseReason> = emptySet(),
    val result: GameResult? = null,
    val totalGoldEarned: Int = 0,
    val permanentProgress: PermanentProgress = PermanentProgress(),
) {
    val isSafetyPaused: Boolean get() = pauseReasons.isNotEmpty()
}
