package com.picoxr.mrspacetowerdefense.model

data class TowerRuntimeState(
    val id: String,
    val type: TowerType,
    val level: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val wallMountIndex: Int,
    val damage: Float,
    val attackIntervalSeconds: Float,
    val attackRange: Float,
    val upgradeCost: Int,
    val featureDescription: String,
    val lockedTargetId: String? = null,
)

data class TowerPlacementPreviewState(
    val selectedType: TowerType? = null,
    val x: Float = 0f,
    val z: Float = 0f,
    val isVisible: Boolean = false,
    val isInsidePlacementZone: Boolean = false,
    val isAffordable: Boolean = false,
    val isBlocked: Boolean = false,
) {
    val canPlace: Boolean
        get() = isVisible && isInsidePlacementZone && isAffordable && !isBlocked
}

sealed interface TowerPlacementResult {
    data class Success(val tower: TowerRuntimeState) : TowerPlacementResult

    data class Rejected(val reason: TowerPlacementRejectReason) : TowerPlacementResult
}

enum class TowerPlacementRejectReason {
    NOT_PLACEMENT_PHASE,
    WALL_SLOTS_FULL,
    NO_TOWER_SELECTED,
    RAY_OUTSIDE_ZONE,
    INSUFFICIENT_GOLD,
    OBSTRUCTED,
    OCCUPIED,
    SCENE_NOT_READY,
}

sealed interface TowerUpgradeResult {
    data class Success(val tower: TowerRuntimeState) : TowerUpgradeResult

    data class Rejected(val reason: TowerUpgradeRejectReason) : TowerUpgradeResult
}

enum class TowerUpgradeRejectReason {
    NOT_WAVE_PAUSE,
    TOWER_NOT_FOUND,
    MAX_LEVEL,
    INSUFFICIENT_GOLD,
}
