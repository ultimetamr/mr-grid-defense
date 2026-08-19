package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.GameStateRules
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.GameStateTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateRulesTest {
    @Test
    fun `calibration must stop in prepare before first fight`() {
        val calibrating =
            GameStateRules.requireNextState(GameState.IDLE, GameStateTrigger.START_CALIBRATION)
        val prepare =
            GameStateRules.requireNextState(calibrating, GameStateTrigger.CALIBRATION_COMPLETED)

        assertEquals(GameState.PREPARE, prepare)
        assertEquals(
            GameState.FIGHTING,
            GameStateRules.requireNextState(prepare, GameStateTrigger.START_FIGHT),
        )
    }

    @Test
    fun completeGameFlow_followsWhitelistedTransitions() {
        val triggers =
            listOf(
                GameStateTrigger.START_CALIBRATION,
                GameStateTrigger.CALIBRATION_COMPLETED,
                GameStateTrigger.START_FIGHT,
                GameStateTrigger.WAVE_COMPLETED,
                GameStateTrigger.START_NEXT_WAVE,
                GameStateTrigger.GAME_FINISHED,
                GameStateTrigger.SETTLEMENT_COMPLETED,
            )

        val finalState =
            triggers.fold(GameState.IDLE) { state, trigger ->
                GameStateRules.requireNextState(state, trigger)
            }

        assertEquals(GameState.IDLE, finalState)
    }

    @Test
    fun illegalJump_isRejected() {
        assertFalse(GameStateRules.nextState(GameState.IDLE, GameStateTrigger.START_FIGHT) != null)
        assertThrows(IllegalStateException::class.java) {
            GameStateRules.requireNextState(GameState.IDLE, GameStateTrigger.START_FIGHT)
        }
    }

    @Test
    fun fighting_exposesOnlyPauseOrSettlementTriggers() {
        val triggers = GameStateRules.allowedTriggers(GameState.FIGHTING)
        assertEquals(2, triggers.size)
        assertTrue(GameStateTrigger.WAVE_COMPLETED in triggers)
        assertTrue(GameStateTrigger.GAME_FINISHED in triggers)
    }
}
