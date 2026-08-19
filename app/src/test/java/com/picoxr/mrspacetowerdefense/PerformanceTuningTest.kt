package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.FrameRateGovernor
import com.picoxr.mrspacetowerdefense.manager.PerformanceTuning
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTuningTest {
    @Test
    fun twentyOrFewerMonstersAlwaysUpdate() {
        assertTrue(PerformanceTuning.shouldUpdateMonster(20, 100f, 1L, 0))
    }

    @Test
    fun nearbyMonstersAlwaysUpdateAboveLodThreshold() {
        assertTrue(PerformanceTuning.shouldUpdateMonster(30, 4f, 1L, 0))
    }

    @Test
    fun farMonstersAreStripedAcrossThreeFrames() {
        assertFalse(PerformanceTuning.shouldUpdateMonster(30, 100f, 1L, 0))
        assertFalse(PerformanceTuning.shouldUpdateMonster(30, 100f, 2L, 0))
        assertTrue(PerformanceTuning.shouldUpdateMonster(30, 100f, 3L, 0))
    }

    @Test
    fun nonCombatGovernorTargetsThirtyHertz() {
        val governor = FrameRateGovernor()
        assertTrue(governor.shouldRun(combatState = false, nowNanos = 1L))
        assertFalse(governor.shouldRun(combatState = false, nowNanos = 10_000_000L))
        assertTrue(governor.shouldRun(combatState = false, nowNanos = 40_000_000L))
        assertTrue(governor.shouldRun(combatState = true, nowNanos = 41_000_000L))
    }

    @Test
    fun backgroundGovernorStopsApplicationUpdatesAndResetsCadence() {
        val governor = FrameRateGovernor()
        assertTrue(governor.shouldRun(combatState = false, nowNanos = 1L))
        assertFalse(
            governor.shouldRun(
                combatState = true,
                applicationActive = false,
                nowNanos = 2L,
            ),
        )
        assertTrue(governor.shouldRun(combatState = false, nowNanos = 3L))
    }
}
