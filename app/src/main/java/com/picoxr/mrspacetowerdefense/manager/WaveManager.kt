package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import android.util.Log
import com.picoxr.mrspacetowerdefense.event.EventBus
import com.picoxr.mrspacetowerdefense.event.WaveCompleteEvent
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.MonsterType
import com.picoxr.mrspacetowerdefense.model.WaveConfig
import com.picoxr.mrspacetowerdefense.model.WaveRuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object WaveManager : BaseManager(), MonsterLifecycleListener {
    private const val TAG = "WaveManager"
    private val stateLock = Any()
    private val mutableCurrentWave = MutableStateFlow<WaveConfig?>(null)
    private val mutableRuntimeState = MutableStateFlow(WaveRuntimeState())

    private var spawnJob: Job? = null
    private var spawningFinished = false
    private var completedVisibleHazardCycle = false
    private var completionScheduled = false
    private var sessionTotalKills = 0

    val currentWave: StateFlow<WaveConfig?> = mutableCurrentWave.asStateFlow()
    val runtimeState: StateFlow<WaveRuntimeState> = mutableRuntimeState.asStateFlow()

    fun getSessionTotalKills(): Int = synchronized(stateLock) { sessionTotalKills }

    override fun onInitialize(applicationContext: Context) {
        MonsterManager.setLifecycleListener(this)
    }

    internal fun launchWave(waveIndex: Int) {
        check(GameStateManager.state.value == GameState.FIGHTING) { "Wave launch requires FIGHTING state" }
        val config = WaveCatalog.get(waveIndex)
        synchronized(stateLock) {
            spawnJob?.cancel()
            mutableCurrentWave.value = config
            mutableRuntimeState.value =
                WaveRuntimeState(
                    currentWaveIndex = waveIndex,
                    plannedMonsterCount = config.monsterCount,
                    rayRefreshIntervalMillis = config.rayRefreshInterval,
                    isSpawning = true,
                )
            spawningFinished = false
            completedVisibleHazardCycle = false
            completionScheduled = false
            spawnJob = managerScope.launch { spawnWave(config) }
        }
    }

    private suspend fun spawnWave(config: WaveConfig) {
        val sequence = WaveCatalog.spawnSequence(config)
        sequence.forEachIndexed { index, type ->
            while (GameStateManager.state.value == GameState.FIGHTING && !GameManager.isCombatSimulationActive()) {
                delay(50L)
            }
            var spawnRetries = 0
            while (GameStateManager.state.value == GameState.FIGHTING && !MonsterManager.spawn(type, config)) {
                spawnRetries++
                if (spawnRetries == 1 || spawnRetries % 20 == 0) {
                    Log.w(
                        TAG,
                        "Waiting for monster spawn slot wave=${config.waveIndex} " +
                            "index=$index type=$type retries=$spawnRetries",
                    )
                }
                delay(100L)
            }
            if (GameStateManager.state.value != GameState.FIGHTING) return
            synchronized(stateLock) {
                mutableRuntimeState.value =
                    mutableRuntimeState.value.copy(spawnedMonsterCount = index + 1)
            }
            if (index != sequence.lastIndex) delay(WaveCatalog.SPAWN_INTERVAL_MILLIS)
        }
        synchronized(stateLock) {
            spawningFinished = true
            mutableRuntimeState.value = mutableRuntimeState.value.copy(isSpawning = false)
            scheduleCompletionIfReadyLocked()
        }
    }

    override fun onMonsterKilled(monsterId: String, type: MonsterType) {
        synchronized(stateLock) {
            sessionTotalKills += 1
            mutableRuntimeState.value =
                mutableRuntimeState.value.copy(
                    killedMonsterCount = mutableRuntimeState.value.killedMonsterCount + 1,
                )
            scheduleCompletionIfReadyLocked()
        }
    }

    /**
     * Called after the grid has kept its first set of red beams fully visible for one second.
     * Fast-cleared early waves wait for this acknowledgement instead of hiding the hazard
     * before the player can ever see it.
     */
    internal fun onVisibleHazardCycleCompleted() = synchronized(stateLock) {
        if (GameStateManager.state.value != GameState.FIGHTING || completedVisibleHazardCycle) {
            return@synchronized
        }
        completedVisibleHazardCycle = true
        Log.i(TAG, "First visible red-beam cycle completed for wave=${mutableCurrentWave.value?.waveIndex}")
        scheduleCompletionIfReadyLocked()
    }

    private fun scheduleCompletionIfReadyLocked() {
        val runtime = mutableRuntimeState.value
        if (completionScheduled) return
        if (
            !WaveCompletionRules.canComplete(
                spawningFinished = spawningFinished,
                killedMonsterCount = runtime.killedMonsterCount,
                plannedMonsterCount = runtime.plannedMonsterCount,
                completedVisibleHazardCycle = completedVisibleHazardCycle,
            )
        ) return
        completionScheduled = true
        managerScope.launch { completeCurrentWave() }
    }

    private suspend fun completeCurrentWave() {
        val config = mutableCurrentWave.value ?: return
        GoldManager.addGold(WaveCatalog.WAVE_REWARD_GOLD, "wave_${config.waveIndex}_complete")
        EventBus.emit(WaveCompleteEvent(config.waveIndex, sessionTotalKills))
    }

    internal fun stopCombat() = synchronized(stateLock) {
        spawnJob?.cancel()
        spawnJob = null
        mutableRuntimeState.value = mutableRuntimeState.value.copy(isSpawning = false)
    }

    internal fun resetSession() = synchronized(stateLock) {
        stopCombat()
        mutableCurrentWave.value = null
        mutableRuntimeState.value = WaveRuntimeState()
        spawningFinished = false
        completedVisibleHazardCycle = false
        completionScheduled = false
        sessionTotalKills = 0
    }

    override fun onDestroy() {
        resetSession()
        MonsterManager.setLifecycleListener(null)
    }
}
