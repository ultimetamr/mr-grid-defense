package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.SceneLayoutUpdatePolicy
import com.picoxr.mrspacetowerdefense.model.GameState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLayoutUpdatePolicyTest {
    @Test
    fun `head pose can define battlefield only during first explicit calibration`() {
        assertTrue(
            SceneLayoutUpdatePolicy.shouldApplyHeadDrivenPose(
                gameState = GameState.CALIBRATING,
                calibrationRequested = true,
                hasCommittedLayout = false,
            ),
        )
        assertFalse(
            SceneLayoutUpdatePolicy.shouldApplyHeadDrivenPose(
                gameState = GameState.CALIBRATING,
                calibrationRequested = false,
                hasCommittedLayout = false,
            ),
        )
    }

    @Test
    fun `committed battlefield never follows player movement or rotation`() {
        GameState.entries.forEach { state ->
            assertFalse(
                SceneLayoutUpdatePolicy.shouldApplyHeadDrivenPose(
                    gameState = state,
                    calibrationRequested = true,
                    hasCommittedLayout = true,
                ),
            )
        }
        listOf(GameState.PREPARE, GameState.FIGHTING, GameState.WAVE_PAUSE, GameState.SETTLE)
            .forEach { state ->
                assertFalse(
                    SceneLayoutUpdatePolicy.shouldApplyHeadDrivenPose(
                        gameState = state,
                        calibrationRequested = false,
                        hasCommittedLayout = true,
                    ),
                )
            }
    }

    @Test
    fun `plane refinements do not rebuild a committed battlefield`() {
        assertFalse(
            SceneLayoutUpdatePolicy.shouldGenerate(
                hasGroundSurface = true,
                hasCommittedLayout = true,
            ),
        )
    }

    @Test
    fun `temporary ground loss keeps a committed battlefield`() {
        assertTrue(
            SceneLayoutUpdatePolicy.shouldKeepCommittedLayout(
                hasGroundSurface = false,
                hasCommittedLayout = true,
            ),
        )
    }

    @Test
    fun `first valid floor generates the battlefield once`() {
        assertTrue(
            SceneLayoutUpdatePolicy.shouldGenerate(
                hasGroundSurface = true,
                hasCommittedLayout = false,
            ),
        )
    }
}
