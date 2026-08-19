package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.event.EventBus
import com.picoxr.mrspacetowerdefense.event.GoldChangedEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class EventBusTest {
    @Test
    fun typedFlow_receivesMatchingEvent() =
        runBlocking {
            val expected = GoldChangedEvent(previousGold = 100, currentGold = 75, reason = "tower")
            val received =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(1_000L) { EventBus.eventsOf<GoldChangedEvent>().first() }
                }

            EventBus.emit(expected)

            assertEquals(expected, received.await())
            assertEquals(-25, expected.delta)
        }
}
