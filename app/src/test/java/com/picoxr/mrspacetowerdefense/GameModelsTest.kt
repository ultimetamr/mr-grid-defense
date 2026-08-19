package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.model.MonsterType
import com.picoxr.mrspacetowerdefense.model.PlayerState
import com.picoxr.mrspacetowerdefense.model.WallState
import com.picoxr.mrspacetowerdefense.model.WaveConfig
import org.junit.Assert.assertThrows
import org.junit.Test

class GameModelsTest {
    @Test
    fun wallHp_cannotExceedMaximum() {
        assertThrows(IllegalArgumentException::class.java) {
            WallState(maxHp = 100, currentHp = 101, level = 1)
        }
    }

    @Test
    fun playerGrid_mustStayInsideNineGridBoard() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayerState(goldCoin = 100, currentSafeGridIndex = 9, isAlive = true)
        }
    }

    @Test
    fun waveInterval_mustBePositive() {
        assertThrows(IllegalArgumentException::class.java) {
            WaveConfig(
                waveIndex = 1,
                monsterCount = 5,
                monsterTypes = listOf(MonsterType.NORMAL),
                hpMultiplier = 1f,
                moveSpeedMultiplier = 1f,
                rayRefreshInterval = 0L,
            )
        }
    }
}
