package com.picoxr.mrspacetowerdefense.model

enum class GridCellState {
    SAFE,
    WARNING,
    DANGER,
}

enum class GridPhase {
    PAUSED,
    COOLDOWN,
    WARNING,
    RAISING,
    ACTIVE,
}

data class GridCellRuntimeState(
    val index: Int,
    val state: GridCellState,
    val isDesignatedSafe: Boolean,
    val beamProgress: Float,
) {
    init {
        require(index in 0..8) { "Grid index must be within 0..8" }
        require(beamProgress in 0f..1f) { "Beam progress must be within 0..1" }
    }
}

data class GridRuntimeState(
    val cells: List<GridCellRuntimeState> =
        List(9) { GridCellRuntimeState(it, GridCellState.SAFE, false, 0f) },
    val safeGridIndex: Int? = null,
    val pendingSafeGridIndex: Int? = null,
    val phase: GridPhase = GridPhase.PAUSED,
)
