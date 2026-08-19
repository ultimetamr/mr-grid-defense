package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import com.picoxr.mrspacetowerdefense.event.EventBus
import com.picoxr.mrspacetowerdefense.event.GameEvent
import com.picoxr.mrspacetowerdefense.event.GoldChangedEvent
import com.picoxr.mrspacetowerdefense.event.PlayerDiedEvent
import com.picoxr.mrspacetowerdefense.event.WallBrokenEvent
import com.picoxr.mrspacetowerdefense.event.WaveCompleteEvent
import com.picoxr.mrspacetowerdefense.model.CombatPauseReason
import com.picoxr.mrspacetowerdefense.model.GameResult
import com.picoxr.mrspacetowerdefense.model.GameRuntimeState
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.GameStateTrigger
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/** Single orchestration boundary for session state, module gating, settlement and safety pauses. */
object GameManager : BaseManager() {
    private val operationMutex = Mutex()
    private val mutableRuntimeState = MutableStateFlow(GameRuntimeState())
    private var settlementCommitted = false
    @Volatile private var playerX = 0f
    @Volatile private var playerZ = 0f
    @Volatile private var appInForeground = true
    private var deathShieldCharges = 0

    val state: StateFlow<GameRuntimeState> = mutableRuntimeState.asStateFlow()

    override fun onInitialize(applicationContext: Context) {
        mutableRuntimeState.value =
            mutableRuntimeState.value.copy(permanentProgress = SaveManager.progress.value)
        managerScope.launch {
            SaveManager.progress.collect { progress ->
                mutableRuntimeState.value = mutableRuntimeState.value.copy(permanentProgress = progress)
            }
        }
        managerScope.launch { EventBus.events.collect(::handleEvent) }
    }

    fun startGame() {
        managerScope.launch {
            operationMutex.withLock {
                if (!GameStateManager.canTransition(GameStateTrigger.START_CALIBRATION)) return@withLock
                resetRunDataLocked()
                setBattleSceneVisible(true)
                GameStateManager.transition(GameStateTrigger.START_CALIBRATION)
                // Never reuse a stale battlefield pose. Calibration completes only after
                // SceneLayoutManager receives both the detected floor and a fresh HMD pose.
                SceneLayoutManager.beginCalibration()
            }
        }
    }

    fun onCalibrationCompleted() {
        managerScope.launch { operationMutex.withLock { calibrationCompletedLocked() } }
    }

    fun startFirstWave() {
        managerScope.launch {
            operationMutex.withLock {
                if (GameStateManager.state.value != GameState.PREPARE) return@withLock
                GameStateManager.transition(GameStateTrigger.START_FIGHT)
                WaveManager.launchWave(1)
            }
        }
    }

    fun startNextWave() {
        managerScope.launch {
            operationMutex.withLock {
                if (GameStateManager.state.value != GameState.WAVE_PAUSE) return@withLock
                val next = (WaveManager.currentWave.value?.waveIndex ?: 0) + 1
                if (next !in 1..WaveCatalog.TOTAL_WAVES) return@withLock
                GameStateManager.transition(GameStateTrigger.START_NEXT_WAVE)
                WaveManager.launchWave(next)
            }
        }
    }

    fun restartGame() {
        managerScope.launch {
            operationMutex.withLock {
                if (!GameStateManager.canTransition(GameStateTrigger.RESTART_GAME)) return@withLock
                resetRunDataLocked()
                setBattleSceneVisible(true)
                GameStateManager.transition(GameStateTrigger.RESTART_GAME)
            }
        }
    }

    fun returnToMainMenu() {
        managerScope.launch {
            operationMutex.withLock {
                if (!GameStateManager.canTransition(GameStateTrigger.SETTLEMENT_COMPLETED)) return@withLock
                resetRunDataLocked()
                setBattleSceneVisible(false)
                GameStateManager.transition(GameStateTrigger.SETTLEMENT_COMPLETED)
            }
        }
    }

    fun upgradePermanentGrowth(type: PermanentUpgradeType): Boolean = SaveManager.upgrade(type)

    fun consumeDeathShield(): Boolean = synchronized(this) {
        if (deathShieldCharges <= 0) return@synchronized false
        deathShieldCharges--
        true
    }

    fun getDeathShieldCharges(): Int = synchronized(this) { deathShieldCharges }

    fun onAppBackgrounded() {
        appInForeground = false
        setPauseReason(CombatPauseReason.APP_BACKGROUND, true)
    }

    /** Background pause is deliberately retained until the player explicitly confirms resume. */
    fun onAppForegrounded() {
        appInForeground = true
    }

    fun resumeAfterInterruption() {
        setPauseReason(CombatPauseReason.APP_BACKGROUND, false)
    }

    fun onTrackingChanged(available: Boolean) =
        setPauseReason(CombatPauseReason.TRACKING_LOST, !available)

    fun updatePlayerPosition(x: Float, z: Float) {
        playerX = x
        playerZ = z
        if (GameStateManager.state.value != GameState.FIGHTING) return
        val cells = SceneLayoutManager.layout.value?.safeGridCells ?: return
        setPauseReason(
            CombatPauseReason.OUT_OF_BOUNDS,
            !GridRules.isInsideActivityArea(cells, x, z),
        )
    }

    fun isCombatSimulationActive(): Boolean =
        appInForeground &&
            GameStateManager.state.value == GameState.FIGHTING &&
            !CombatPauseRules.freezesCombat(mutableRuntimeState.value.pauseReasons)

    fun isHazardSimulationActive(): Boolean =
        isCombatSimulationActive() &&
            !CombatPauseRules.pausesHazards(mutableRuntimeState.value.pauseReasons)

    fun isApplicationUpdateEnabled(): Boolean = appInForeground

    fun playerDistanceSquared(x: Float, z: Float): Float {
        val dx = x - playerX
        val dz = z - playerZ
        return dx * dx + dz * dz
    }

    fun refreshSceneVisibility() = setBattleSceneVisible(GameStateManager.state.value != GameState.IDLE)

    private suspend fun handleEvent(event: GameEvent) {
        when (event) {
            is GoldChangedEvent -> if (event.delta > 0 && event.reason != "session_reset" && !settlementCommitted) {
                val previous = mutableRuntimeState.value.totalGoldEarned
                mutableRuntimeState.value =
                    mutableRuntimeState.value.copy(
                        totalGoldEarned =
                            (previous.toLong() + event.delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    )
            }
            is WallBrokenEvent -> settle(win = false)
            is PlayerDiedEvent -> settle(win = false)
            is WaveCompleteEvent -> handleWaveComplete(event)
            else -> Unit
        }
    }

    private suspend fun handleWaveComplete(event: WaveCompleteEvent) = operationMutex.withLock {
        if (GameStateManager.state.value != GameState.FIGHTING || settlementCommitted) return@withLock
        GameStateManager.transition(GameStateTrigger.WAVE_COMPLETED)
        WaveManager.stopCombat()
        MonsterManager.pauseAndRecycleWave()
        TowerManager.stopCombat()
        GridManager.freezeCombat()
        if (event.waveIndex >= WaveCatalog.TOTAL_WAVES) settleLocked(win = true)
    }

    private suspend fun settle(win: Boolean) = operationMutex.withLock { settleLocked(win) }

    private suspend fun settleLocked(win: Boolean) {
        if (settlementCommitted) return
        if (GameStateManager.canTransition(GameStateTrigger.GAME_FINISHED)) {
            GameStateManager.transition(GameStateTrigger.GAME_FINISHED)
        } else if (GameStateManager.state.value != GameState.SETTLE) {
            return
        }
        settlementCommitted = true
        WaveManager.stopCombat()
        MonsterManager.pauseAndRecycleWave()
        TowerManager.stopCombat()
        GridManager.freezeCombat()
        val reachedWave = WaveManager.runtimeState.value.currentWaveIndex
        val result =
            GameResult(
                isWin = win,
                reachWave = reachedWave,
                totalKill = WaveManager.getSessionTotalKills(),
                totalGold = mutableRuntimeState.value.totalGoldEarned,
                crystalReward = reachedWave * 10,
            )
        val progress = SaveManager.recordSettlement(result)
        mutableRuntimeState.value =
            mutableRuntimeState.value.copy(
                pauseReasons = emptySet(),
                result = result,
                permanentProgress = progress,
            )
        // Settlement can be delivered re-entrantly from an ECS frame through
        // Dispatchers.Main.immediate. Let that frame unwind before destroying
        // monster/grid/tower/wall native resources.
        yield()
        releaseBattleResources()
    }

    private suspend fun calibrationCompletedLocked() {
        if (GameStateManager.canTransition(GameStateTrigger.CALIBRATION_COMPLETED)) {
            GameStateManager.transition(GameStateTrigger.CALIBRATION_COMPLETED)
        }
    }

    private fun setPauseReason(reason: CombatPauseReason, enabled: Boolean) {
        if (GameStateManager.state.value != GameState.FIGHTING && enabled) return
        val current = mutableRuntimeState.value.pauseReasons
        val updated = if (enabled) current + reason else current - reason
        if (updated != current) mutableRuntimeState.value = mutableRuntimeState.value.copy(pauseReasons = updated)
    }

    private fun resetRunDataLocked() {
        settlementCommitted = false
        WaveManager.resetSession()
        MonsterManager.resetSession()
        GoldManager.resetSession()
        TowerManager.resetSession()
        GridManager.resetSession()
        WallManager.resetSession()
        deathShieldCharges = SaveManager.bonuses.value.deathShieldCharges
        mutableRuntimeState.value =
            GameRuntimeState(permanentProgress = SaveManager.progress.value)
        playerX = 0f
        playerZ = 0f
    }

    private fun setBattleSceneVisible(visible: Boolean) {
        SceneLayoutManager.setSceneVisible(visible)
        WallManager.setSceneVisible(visible)
        TowerManager.setSceneVisible(visible)
        GridManager.setSceneVisible(visible)
    }

    private fun releaseBattleResources() {
        MonsterManager.releaseBattleResources()
        TowerManager.releaseBattleResources()
        GridManager.releaseBattleResources()
        WallManager.releaseBattleResources()
    }

    override fun onDestroy() {
        settlementCommitted = false
        appInForeground = false
        playerX = 0f
        playerZ = 0f
        mutableRuntimeState.value = GameRuntimeState()
        deathShieldCharges = 0
    }
}
