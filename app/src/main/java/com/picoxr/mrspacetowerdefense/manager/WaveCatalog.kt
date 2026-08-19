package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.MonsterType
import com.picoxr.mrspacetowerdefense.model.WaveConfig
import kotlin.math.roundToLong

/** Explicit ten-wave design table. Composition changes cannot drift through cyclic generation. */
object WaveCatalog {
    const val TOTAL_WAVES = 10
    const val SPAWN_INTERVAL_MILLIS = 800L
    const val WAVE_REWARD_GOLD = 50
    const val FIRST_WAVE_RAY_INTERVAL_MILLIS = 6_000L
    const val FINAL_WAVE_RAY_INTERVAL_MILLIS = 3_000L

    private val definitions =
        listOf(
            definition(1, 1.00f, 1.00f, MonsterType.NORMAL to 5),
            definition(2, 1.10f, 1.02f, MonsterType.NORMAL to 6, MonsterType.FAST to 2),
            definition(3, 1.22f, 1.04f, MonsterType.NORMAL to 7, MonsterType.FAST to 4),
            definition(4, 1.36f, 1.06f, MonsterType.NORMAL to 6, MonsterType.FAST to 4, MonsterType.ARMORED to 4),
            definition(5, 1.52f, 1.08f, MonsterType.NORMAL to 6, MonsterType.FAST to 5, MonsterType.ARMORED to 6),
            definition(
                6, 1.70f, 1.10f,
                MonsterType.NORMAL to 6, MonsterType.FAST to 5, MonsterType.ARMORED to 5,
                MonsterType.SELF_DESTRUCT to 4,
            ),
            definition(
                7, 1.90f, 1.12f,
                MonsterType.NORMAL to 5, MonsterType.FAST to 5, MonsterType.ARMORED to 5,
                MonsterType.SELF_DESTRUCT to 4, MonsterType.ACID to 4,
            ),
            definition(
                8, 2.15f, 1.14f,
                MonsterType.NORMAL to 5, MonsterType.FAST to 5, MonsterType.ARMORED to 5,
                MonsterType.SELF_DESTRUCT to 4, MonsterType.ACID to 3, MonsterType.ELITE to 4,
            ),
            definition(
                9, 2.45f, 1.16f,
                MonsterType.NORMAL to 5, MonsterType.FAST to 5, MonsterType.ARMORED to 6,
                MonsterType.SELF_DESTRUCT to 4, MonsterType.ACID to 4, MonsterType.ELITE to 5,
            ),
            definition(
                10, 2.80f, 1.18f,
                MonsterType.NORMAL to 6, MonsterType.FAST to 5, MonsterType.ARMORED to 6,
                MonsterType.SELF_DESTRUCT to 4, MonsterType.ACID to 4, MonsterType.ELITE to 6,
                MonsterType.BOSS to 1,
            ),
        ).associateBy(WaveDefinition::waveIndex)

    fun get(waveIndex: Int): WaveConfig {
        val definition = definitions[waveIndex]
            ?: throw IllegalArgumentException("Wave index must be between 1 and $TOTAL_WAVES")
        return WaveConfig(
            waveIndex = waveIndex,
            monsterCount = definition.sequence.size,
            monsterTypes = definition.sequence.distinct(),
            hpMultiplier = definition.hpMultiplier,
            moveSpeedMultiplier = definition.moveSpeedMultiplier,
            rayRefreshInterval = rayRefreshInterval(waveIndex),
        )
    }

    fun rayRefreshInterval(waveIndex: Int): Long {
        require(waveIndex in 1..TOTAL_WAVES)
        val progress = (waveIndex - 1).toDouble() / (TOTAL_WAVES - 1).toDouble()
        return (
            FIRST_WAVE_RAY_INTERVAL_MILLIS +
                (FINAL_WAVE_RAY_INTERVAL_MILLIS - FIRST_WAVE_RAY_INTERVAL_MILLIS) * progress
        ).roundToLong()
    }

    fun spawnSequence(config: WaveConfig): List<MonsterType> =
        checkNotNull(definitions[config.waveIndex]).sequence

    private fun definition(
        waveIndex: Int,
        hpMultiplier: Float,
        moveSpeedMultiplier: Float,
        vararg composition: Pair<MonsterType, Int>,
    ): WaveDefinition {
        val bossCount = composition.firstOrNull { it.first == MonsterType.BOSS }?.second ?: 0
        val regularComposition = composition.filterNot { it.first == MonsterType.BOSS }
        val sequence = buildList {
            // Interleave types by rounds so special units are distributed through the wave.
            val maxCount = regularComposition.maxOfOrNull { it.second } ?: 0
            repeat(maxCount) { round ->
                regularComposition.forEach { (type, count) -> if (round < count) add(type) }
            }
            repeat(bossCount) { add(MonsterType.BOSS) }
        }
        return WaveDefinition(waveIndex, hpMultiplier, moveSpeedMultiplier, sequence)
    }

    private data class WaveDefinition(
        val waveIndex: Int,
        val hpMultiplier: Float,
        val moveSpeedMultiplier: Float,
        val sequence: List<MonsterType>,
    )
}
