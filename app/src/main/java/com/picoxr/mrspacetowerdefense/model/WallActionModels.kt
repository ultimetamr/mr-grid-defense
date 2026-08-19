package com.picoxr.mrspacetowerdefense.model

enum class WallUpgradeType { MAX_HP, DAMAGE_REDUCTION, REFLECTION, REGENERATION }

sealed interface WallUpgradeResult {
    data class Success(
        val wallState: WallState,
        val cost: Int,
        val type: WallUpgradeType = WallUpgradeType.MAX_HP,
    ) : WallUpgradeResult

    data class Rejected(val reason: WallUpgradeRejectReason) : WallUpgradeResult
}

enum class WallUpgradeRejectReason {
    NOT_WAVE_PAUSE,
    MAX_LEVEL,
    INSUFFICIENT_GOLD,
}

sealed interface WallRepairResult {
    data class Success(val wallState: WallState, val cost: Int, val recoveredHp: Int) : WallRepairResult

    data class Rejected(val reason: WallRepairRejectReason) : WallRepairResult
}

enum class WallRepairRejectReason {
    NOT_WAVE_PAUSE,
    ALREADY_FULL,
    INSUFFICIENT_GOLD,
}
