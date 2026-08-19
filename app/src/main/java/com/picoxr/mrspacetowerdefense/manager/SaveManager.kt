package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import com.picoxr.mrspacetowerdefense.model.GameResult
import com.picoxr.mrspacetowerdefense.model.PermanentBonusSnapshot
import com.picoxr.mrspacetowerdefense.model.PermanentProgress
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeType
import com.picoxr.mrspacetowerdefense.utils.LocalSaveStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SaveManager : BaseManager() {
    private val stateLock = Any()
    private val mutableProgress = MutableStateFlow(PermanentProgress())
    private val mutableBonuses = MutableStateFlow(PermanentBonusSnapshot())
    private var store: LocalSaveStore? = null

    val progress: StateFlow<PermanentProgress> = mutableProgress.asStateFlow()
    val bonuses: StateFlow<PermanentBonusSnapshot> = mutableBonuses.asStateFlow()

    override fun onInitialize(applicationContext: Context) {
        store = LocalSaveStore(applicationContext)
        mutableProgress.value = checkNotNull(store).load()
        mutableBonuses.value = PermanentGrowthCatalog.bonuses(mutableProgress.value)
    }

    fun recordSettlement(result: GameResult): PermanentProgress = synchronized(stateLock) {
        val current = mutableProgress.value
        val updated =
            current.copy(
                totalCrystals =
                    (current.totalCrystals.toLong() + result.crystalReward)
                        .coerceAtMost(LocalSaveStore.MAX_CRYSTALS.toLong())
                        .toInt(),
                highestWave = maxOf(current.highestWave, result.reachWave),
                highestKills = maxOf(current.highestKills, result.totalKill),
            )
        persistLocked(updated)
    }

    fun upgradeCost(type: PermanentUpgradeType): Int =
        PermanentGrowthCatalog.upgradeCost(type, mutableProgress.value.levelOf(type))

    fun upgrade(type: PermanentUpgradeType): Boolean = synchronized(stateLock) {
        val current = mutableProgress.value
        val config = PermanentGrowthCatalog.get(type)
        val level = current.levelOf(type)
        if (level >= config.maxLevel) return@synchronized false
        val cost = PermanentGrowthCatalog.upgradeCost(type, level)
        if (current.totalCrystals < cost) return@synchronized false
        val updatedLevels = current.upgradeLevels.toMutableMap().apply { this[type] = level + 1 }
        persistLocked(
            current.copy(
                totalCrystals = current.totalCrystals - cost,
                upgradeLevels = updatedLevels,
            ),
        )
        true
    }

    private fun persistLocked(value: PermanentProgress): PermanentProgress {
        store?.save(value)
        mutableProgress.value = value
        mutableBonuses.value = PermanentGrowthCatalog.bonuses(value)
        return value
    }

    override fun onDestroy() {
        store = null
        mutableProgress.value = PermanentProgress()
        mutableBonuses.value = PermanentBonusSnapshot()
    }
}
