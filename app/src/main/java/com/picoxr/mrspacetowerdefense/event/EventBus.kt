package com.picoxr.mrspacetowerdefense.event

import com.picoxr.mrspacetowerdefense.manager.BaseManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

object EventBus : BaseManager() {
    private val mutableEvents =
        MutableSharedFlow<GameEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<GameEvent> = mutableEvents.asSharedFlow()

    suspend fun emit(event: GameEvent) {
        mutableEvents.emit(event)
    }

    fun post(event: GameEvent) {
        managerScope.launch { mutableEvents.emit(event) }
    }

    inline fun <reified T : GameEvent> eventsOf(): Flow<T> = events.filterIsInstance<T>()
}
