package com.picoxr.mrspacetowerdefense.model

data class PlayerState(
    val goldCoin: Int,
    val currentSafeGridIndex: Int,
    val isAlive: Boolean,
) {
    init {
        require(goldCoin >= 0) { "Player gold cannot be negative" }
        require(currentSafeGridIndex in 0..8) { "Safe-grid index must be within the 3x3 board" }
    }
}
