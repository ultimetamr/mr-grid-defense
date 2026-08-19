package com.picoxr.mrspacetowerdefense.model

data class GameConfig(
    val boardRows: Int = 3,
    val boardColumns: Int = 3,
    val stageId: String = "MrGridDefenseStage",
)
