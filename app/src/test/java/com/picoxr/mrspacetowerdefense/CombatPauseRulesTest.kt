package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.CombatPauseRules
import com.picoxr.mrspacetowerdefense.model.CombatPauseReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombatPauseRulesTest {
    @Test
    fun `leaving grid freezes all combat and hazards`() {
        val reasons = setOf(CombatPauseReason.OUT_OF_BOUNDS)

        assertTrue(CombatPauseRules.freezesCombat(reasons))
        assertTrue(CombatPauseRules.pausesHazards(reasons))
    }

    @Test
    fun `background and tracking loss freeze all combat`() {
        listOf(CombatPauseReason.APP_BACKGROUND, CombatPauseReason.TRACKING_LOST).forEach { reason ->
            val reasons = setOf(reason)
            assertTrue(CombatPauseRules.freezesCombat(reasons))
            assertTrue(CombatPauseRules.pausesHazards(reasons))
        }
    }

    @Test
    fun `clearing all pause reasons resumes combat and hazards`() {
        assertFalse(CombatPauseRules.freezesCombat(emptySet()))
        assertFalse(CombatPauseRules.pausesHazards(emptySet()))
    }
}
