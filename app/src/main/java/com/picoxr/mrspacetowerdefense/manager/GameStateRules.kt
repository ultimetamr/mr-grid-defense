package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.GameStateTrigger

object GameStateRules {
    private val transitions =
        mapOf(
            (GameState.IDLE to GameStateTrigger.START_CALIBRATION) to GameState.CALIBRATING,
            (GameState.CALIBRATING to GameStateTrigger.CALIBRATION_COMPLETED) to GameState.PREPARE,
            (GameState.CALIBRATING to GameStateTrigger.CALIBRATION_CANCELLED) to GameState.IDLE,
            (GameState.PREPARE to GameStateTrigger.START_FIGHT) to GameState.FIGHTING,
            (GameState.PREPARE to GameStateTrigger.CANCEL_PREPARATION) to GameState.IDLE,
            (GameState.FIGHTING to GameStateTrigger.WAVE_COMPLETED) to GameState.WAVE_PAUSE,
            (GameState.FIGHTING to GameStateTrigger.GAME_FINISHED) to GameState.SETTLE,
            (GameState.WAVE_PAUSE to GameStateTrigger.START_NEXT_WAVE) to GameState.FIGHTING,
            (GameState.WAVE_PAUSE to GameStateTrigger.GAME_FINISHED) to GameState.SETTLE,
            (GameState.SETTLE to GameStateTrigger.SETTLEMENT_COMPLETED) to GameState.IDLE,
            (GameState.SETTLE to GameStateTrigger.RESTART_GAME) to GameState.PREPARE,
        )

    fun nextState(currentState: GameState, trigger: GameStateTrigger): GameState? =
        transitions[currentState to trigger]

    fun requireNextState(currentState: GameState, trigger: GameStateTrigger): GameState =
        nextState(currentState, trigger)
            ?: throw IllegalStateException(
                "Illegal game-state transition: $currentState --$trigger--> ?",
            )

    fun allowedTriggers(currentState: GameState): Set<GameStateTrigger> =
        transitions.keys
            .asSequence()
            .filter { (state, _) -> state == currentState }
            .map { (_, trigger) -> trigger }
            .toSet()
}
