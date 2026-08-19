package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.event.EventBus
import com.picoxr.mrspacetowerdefense.event.GameStateChangedEvent
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.GameStateTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object GameStateManager : BaseManager() {
    private val transitionMutex = Mutex()
    private val mutableState = MutableStateFlow(GameState.IDLE)

    val state: StateFlow<GameState> = mutableState.asStateFlow()

    fun canTransition(trigger: GameStateTrigger): Boolean =
        GameStateRules.nextState(mutableState.value, trigger) != null

    fun allowedTriggers(): Set<GameStateTrigger> = GameStateRules.allowedTriggers(mutableState.value)

    suspend fun transition(trigger: GameStateTrigger): GameState =
        transitionMutex.withLock {
            check(isInitialized) { "GameStateManager is not initialized" }
            val previousState = mutableState.value
            val nextState = GameStateRules.requireNextState(previousState, trigger)
            mutableState.value = nextState
            EventBus.emit(
                GameStateChangedEvent(
                    previousState = previousState,
                    currentState = nextState,
                    trigger = trigger,
                ),
            )
            nextState
        }

    override fun onDestroy() {
        mutableState.value = GameState.IDLE
    }
}
