package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.CombatPauseReason

/** One authoritative pause gate shared by every combat subsystem. */
object CombatPauseRules {
    fun freezesCombat(reasons: Set<CombatPauseReason>): Boolean =
        CombatPauseReason.APP_BACKGROUND in reasons ||
            CombatPauseReason.TRACKING_LOST in reasons ||
            CombatPauseReason.OUT_OF_BOUNDS in reasons

    fun pausesHazards(reasons: Set<CombatPauseReason>): Boolean =
        freezesCombat(reasons)
}
