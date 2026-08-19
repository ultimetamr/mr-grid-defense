package com.picoxr.mrspacetowerdefense.model

data class GameResult(
    val isWin: Boolean,
    val reachWave: Int,
    val totalKill: Int,
    val totalGold: Int,
    val crystalReward: Int = reachWave * 10,
) {
    init {
        require(reachWave >= 0) { "Reached wave cannot be negative" }
        require(totalKill >= 0) { "Total kills cannot be negative" }
        require(totalGold >= 0) { "Total gold cannot be negative" }
        require(crystalReward >= 0) { "Crystal reward cannot be negative" }
    }
}
