package com.picoxr.mrspacetowerdefense.event

import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.GameStateTrigger
import com.picoxr.mrspacetowerdefense.model.MonsterType
import com.picoxr.mrspacetowerdefense.model.TowerType

sealed interface GameEvent {
    val occurredAtMillis: Long
}

data class GameStateChangedEvent(
    val previousState: GameState,
    val currentState: GameState,
    val trigger: GameStateTrigger,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent

data class MonsterKilledEvent(
    val monsterId: String,
    val monsterType: MonsterType,
    val goldReward: Int,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent

data class TowerPlacedEvent(
    val towerId: String,
    val towerType: TowerType,
    val towerLevel: Int = 1,
    val x: Float,
    val z: Float,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent

data class TowerUpgradedEvent(
    val towerId: String,
    val towerType: TowerType,
    val previousLevel: Int,
    val currentLevel: Int,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent

data class WallHpChangedEvent(
    val previousHp: Int,
    val currentHp: Int,
    val maxHp: Int,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent

data class WallBrokenEvent(
    val wallLevel: Int,
    val maxHp: Int,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent

data class WallUpgradedEvent(
    val previousLevel: Int,
    val currentLevel: Int,
    val previousMaxHp: Int,
    val currentMaxHp: Int,
    val goldCost: Int,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent

data class WallRepairedEvent(
    val previousHp: Int,
    val currentHp: Int,
    val maxHp: Int,
    val goldCost: Int,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent

data class GoldChangedEvent(
    val previousGold: Int,
    val currentGold: Int,
    val reason: String,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent {
    val delta: Int = currentGold - previousGold
}

data class RayTriggeredEvent(
    val playerGridIndex: Int?,
    val safeGridIndex: Int,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent

data class PlayerDiedEvent(
    val playerGridIndex: Int?,
    val safeGridIndex: Int,
    val reason: String = "energy_ray",
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent

data class WaveCompleteEvent(
    val waveIndex: Int,
    val totalKill: Int,
    override val occurredAtMillis: Long = System.currentTimeMillis(),
) : GameEvent
