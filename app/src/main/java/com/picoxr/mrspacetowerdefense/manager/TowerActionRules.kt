package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.TowerType

/** Pure phase gates shared by tower input, placement, upgrades, and automatic combat. */
object TowerActionRules {
    fun canPlace(state: GameState): Boolean =
        state == GameState.PREPARE || state == GameState.WAVE_PAUSE

    fun canUpgrade(state: GameState): Boolean = state == GameState.WAVE_PAUSE

    fun isAutomaticCombatActive(state: GameState): Boolean = state == GameState.FIGHTING

    /** Every tower except the passive totem must produce a visible pooled projectile. */
    fun attacksWithProjectile(type: TowerType): Boolean = type != TowerType.TOTEM
}
