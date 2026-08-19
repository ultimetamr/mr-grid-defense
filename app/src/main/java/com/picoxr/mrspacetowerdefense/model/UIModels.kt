package com.picoxr.mrspacetowerdefense.model

enum class GamePanel {
    NONE,
    MAIN_MENU,
    CALIBRATION,
    SHOP,
    TOWER_UPGRADE,
    SAFETY_PAUSE,
    SETTLEMENT,
    PERMANENT_GROWTH,
}

data class UIRuntimeState(
    val activePanel: GamePanel = GamePanel.MAIN_MENU,
    val selectedTowerId: String? = null,
    val statusMessage: String = "正在初始化空间感知",
    val settlement: GameResult? = null,
)

data class PanelPoseState(
    val isPositioned: Boolean = false,
    val isRecentering: Boolean = false,
)
