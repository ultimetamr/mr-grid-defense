package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import com.picoxr.mrspacetowerdefense.event.EventBus
import com.picoxr.mrspacetowerdefense.event.GoldChangedEvent
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GoldManager : BaseManager() {
    const val INITIAL_GOLD = 300

    private val stateLock = Any()
    private val ledger = GoldLedger(INITIAL_GOLD)
    private val mutableGold = MutableStateFlow(INITIAL_GOLD)

    val gold: StateFlow<Int> = mutableGold.asStateFlow()

    override fun onInitialize(applicationContext: Context) = Unit

    fun addGold(amount: Int) {
        addGold(amount, "add_gold")
    }

    internal fun addGold(amount: Int, reason: String) = synchronized(stateLock) {
        val bonus = SaveManager.bonuses.value
        val multiplier =
            when {
                reason.startsWith("kill_") -> 1f + bonus.killGoldBonus
                reason.startsWith("wave_") -> 1f + bonus.waveRewardBonus
                else -> 1f
            }
        val adjustedAmount = (amount * multiplier).roundToInt().coerceAtLeast(0)
        val change = ledger.add(adjustedAmount)
        publishLocked(change, reason)
    }

    fun costGold(amount: Int): Boolean = costGold(amount, "cost_gold")

    internal fun costGold(amount: Int, reason: String): Boolean = synchronized(stateLock) {
        val result = ledger.cost(amount)
        result.change?.let { publishLocked(it, reason) }
        result.succeeded
    }

    fun getCurrentGold(): Int = synchronized(stateLock) { ledger.current }

    internal fun resetSession() =
        setCurrentGold(
            PermanentGrowthCatalog.applyMultiplier(
                INITIAL_GOLD,
                SaveManager.bonuses.value.startingGoldBonus,
            ),
            "session_reset",
        )

    internal fun setCurrentGold(amount: Int, reason: String = "gold_corrected") = synchronized(stateLock) {
        publishLocked(ledger.set(amount), reason)
    }

    private fun publishLocked(change: GoldChange, reason: String) {
        mutableGold.value = change.current
        if (change.previous != change.current && EventBus.isInitialized) {
            EventBus.post(GoldChangedEvent(change.previous, change.current, reason))
        }
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            ledger.set(INITIAL_GOLD)
            mutableGold.value = INITIAL_GOLD
        }
    }
}

internal class GoldLedger(initialGold: Int) {
    var current: Int = initialGold.coerceAtLeast(0)
        private set

    fun add(amount: Int): GoldChange {
        val previous = current
        if (amount > 0) {
            current = (current.toLong() + amount.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return GoldChange(previous, current)
    }

    fun cost(amount: Int): GoldCostResult {
        if (amount < 0 || amount > current) return GoldCostResult(false, null)
        val previous = current
        current = (current - amount).coerceAtLeast(0)
        return GoldCostResult(true, GoldChange(previous, current))
    }

    fun set(amount: Int): GoldChange {
        val previous = current
        current = amount.coerceAtLeast(0)
        return GoldChange(previous, current)
    }
}

internal data class GoldChange(val previous: Int, val current: Int)

internal data class GoldCostResult(val succeeded: Boolean, val change: GoldChange?)
