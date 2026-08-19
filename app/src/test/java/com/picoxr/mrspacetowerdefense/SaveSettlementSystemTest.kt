package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.GameStateRules
import com.picoxr.mrspacetowerdefense.model.GameResult
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.GameStateTrigger
import com.picoxr.mrspacetowerdefense.model.PermanentProgress
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeType
import com.picoxr.mrspacetowerdefense.utils.SaveChecksum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SaveSettlementSystemTest {
    @Test
    fun settlementRewardIsTenCrystalsPerReachedWave() {
        val result = GameResult(isWin = false, reachWave = 7, totalKill = 42, totalGold = 180)
        assertEquals(70, result.crystalReward)
    }

    @Test
    fun settlementCanRestartDirectlyIntoPrepare() {
        assertEquals(
            GameState.PREPARE,
            GameStateRules.nextState(GameState.SETTLE, GameStateTrigger.RESTART_GAME),
        )
    }

    @Test
    fun saveChecksumChangesWhenProtectedDataChanges() {
        val original = PermanentProgress(
            totalCrystals = 100,
            upgradeLevels = mapOf(PermanentUpgradeType.TOWER_DAMAGE to 2),
            highestWave = 5,
            highestKills = 30,
        )
        val modified = original.copy(totalCrystals = 10_000)
        assertNotEquals(SaveChecksum.calculate(original), SaveChecksum.calculate(modified))
    }

    @Test
    fun saveRepairPreservesValidatedMaximumValues() {
        val repaired =
            SaveChecksum.repair(
                PermanentProgress(
                    totalCrystals = 99_999_999,
                    upgradeLevels = PermanentUpgradeType.entries.associateWith { 10 },
                    highestWave = 10,
                    highestKills = 1_000_000,
                ),
            )
        assertEquals(99_999_999, repaired.totalCrystals)
        assertEquals(120, repaired.totalUpgradeLevels)
        assertEquals(10, repaired.levelOf(PermanentUpgradeType.DEATH_SHIELD))
        assertEquals(10, repaired.highestWave)
        assertEquals(1_000_000, repaired.highestKills)
    }
}
