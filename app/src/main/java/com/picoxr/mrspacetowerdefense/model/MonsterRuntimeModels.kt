package com.picoxr.mrspacetowerdefense.model

enum class MonsterActionState {
    MOVING,
    SIEGING,
    DEAD,
}

data class MonsterRuntimeState(
    val id: String,
    val type: MonsterType,
    val currentHp: Float,
    val maxHp: Float,
    val moveSpeed: Float,
    val x: Float,
    val y: Float,
    val z: Float,
    val actionState: MonsterActionState,
)

data class MonsterPoolStats(
    val activeCount: Int,
    val createdCount: Int,
    val maximumCount: Int,
)

data class WaveRuntimeState(
    val currentWaveIndex: Int = 0,
    val totalWaves: Int = 10,
    val plannedMonsterCount: Int = 0,
    val spawnedMonsterCount: Int = 0,
    val killedMonsterCount: Int = 0,
    val isSpawning: Boolean = false,
    val rayRefreshIntervalMillis: Long = 6_000L,
)
