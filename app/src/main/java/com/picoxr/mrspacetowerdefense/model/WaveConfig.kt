package com.picoxr.mrspacetowerdefense.model

data class WaveConfig(
    val waveIndex: Int,
    val monsterCount: Int,
    val monsterTypes: List<MonsterType>,
    val hpMultiplier: Float,
    val moveSpeedMultiplier: Float,
    /** Milliseconds between ray refreshes for this wave. */
    val rayRefreshInterval: Long,
) {
    init {
        require(waveIndex >= 1) { "Wave index must start at one" }
        require(monsterCount > 0) { "A wave must contain at least one monster" }
        require(monsterTypes.isNotEmpty()) { "A wave must declare at least one monster type" }
        require(hpMultiplier > 0f) { "Wave HP multiplier must be greater than zero" }
        require(moveSpeedMultiplier > 0f) { "Wave move-speed multiplier must be greater than zero" }
        require(rayRefreshInterval > 0L) { "Ray refresh interval must be greater than zero milliseconds" }
    }
}
