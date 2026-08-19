package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import android.util.Log
import com.picoxr.mrspacetowerdefense.BuildConfig
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Matrix4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider.DataListener
import com.pico.spatial.tracking.hmd.HMDPose
import com.pico.spatial.tracking.hmd.HMDTrackingData
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import com.picoxr.mrspacetowerdefense.model.GamePanel
import com.picoxr.mrspacetowerdefense.model.GameResult
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.PanelPoseState
import com.picoxr.mrspacetowerdefense.model.PlaneDetectionState
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeType
import com.picoxr.mrspacetowerdefense.model.TowerType
import com.picoxr.mrspacetowerdefense.model.TowerPlacementResult
import com.picoxr.mrspacetowerdefense.model.UIRuntimeState
import com.picoxr.mrspacetowerdefense.model.CombatPauseReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Owns single-modal world locking and the independently HMD-following combat HUD. */
object UIManager : BaseManager() {
    const val MAIN_PANEL_ATTACHMENT_ID = "game-main-panel"
    const val HUD_ATTACHMENT_ID = "game-hud"

    private const val TAG = "UIManager"
    private const val HMD_TRACKING_TIMEOUT_NANOS = 1_000_000_000L

    private val stateLock = Any()
    private val mutableState = MutableStateFlow(UIRuntimeState())
    private val mutablePoseState = MutableStateFlow(PanelPoseState())
    private val latestHeadPose = AtomicReference<HMDPose?>(null)
    private val latestHeadPoseNanos = AtomicLong(0L)
    private val receivedFirstHeadPose = AtomicBoolean(false)
    private val purchaseInFlight = AtomicBoolean(false)
    private var trackingProvider: HMDTrackingProvider? = null
    private val trackingListener =
        object : DataListener<HMDTrackingData> {
            override fun onProvideData(data: HMDTrackingData) {
                latestHeadPose.set(data.hmdPose)
                latestHeadPoseNanos.set(System.nanoTime())
                if (receivedFirstHeadPose.compareAndSet(false, true)) {
                    Log.i(TAG, "Received first live HMD pose at ${data.hmdPose.position}")
                }
            }
        }

    private var sceneRoot: Entity? = null
    private var mainPanel: Entity? = null
    private var hudPanel: Entity? = null
    private var shopSuppressedForPlacement = false
    private var growthPanelVisible = false
    private var trackedGameState = GameState.IDLE
    private var trackingActive = false
    private var hudPositioned = false
    private var hudYawRadians = 0f
    private var hudPitchRadians = -PanelPlacementRules.HUD_DOWN_ANGLE_RADIANS
    private var lockedModalPose: LockedModalPose? = null
    private var modalRecenter: ModalRecenter? = null
    private var lastFollowLogNanos = 0L

    val state: StateFlow<UIRuntimeState> = mutableState.asStateFlow()
    val poseState: StateFlow<PanelPoseState> = mutablePoseState.asStateFlow()

    override fun onInitialize(applicationContext: Context) {
        managerScope.launch {
            GameStateManager.state.collect { gameState ->
                synchronized(stateLock) {
                    if (trackedGameState != gameState) {
                        shopSuppressedForPlacement = false
                        trackedGameState = gameState
                    }
                    refreshVisiblePanelLocked()
                }
            }
        }
        managerScope.launch {
            GameManager.state.collect { runtime ->
                synchronized(stateLock) {
                    val pauseMessage =
                        when {
                            CombatPauseReason.OUT_OF_BOUNDS in runtime.pauseReasons ->
                                "已走出九宫格，战斗已暂停；返回3×3格子内自动继续"
                            CombatPauseReason.TRACKING_LOST in runtime.pauseReasons ->
                                "空间跟踪丢失，战斗已暂停"
                            CombatPauseReason.APP_BACKGROUND in runtime.pauseReasons ->
                                "游戏已暂停，确认后继续"
                            else -> mutableState.value.statusMessage
                        }
                    mutableState.value =
                        mutableState.value.copy(
                            statusMessage = pauseMessage,
                            settlement = runtime.result,
                        )
                    refreshVisiblePanelLocked()
                }
            }
        }
        managerScope.launch {
            SpatialManager.state.collect { planeState ->
                synchronized(stateLock) {
                    val message =
                        when (planeState) {
                            PlaneDetectionState.Ready -> "空间感知已就绪"
                            PlaneDetectionState.Starting -> "正在启动空间扫描"
                            is PlaneDetectionState.Running -> "已识别 ${planeState.detectedPlaneCount} 个空间平面"
                            PlaneDetectionState.Stopped -> "空间扫描已暂停"
                            is PlaneDetectionState.PermissionDenied -> "空间权限不足，已进入降级模式"
                            is PlaneDetectionState.Failed -> "空间扫描异常：${planeState.message}"
                        }
                    mutableState.value = mutableState.value.copy(statusMessage = message)
                }
            }
        }
    }

    fun attach(scene: Entity, main: Entity, hud: Entity) = synchronized(stateLock) {
        detachLocked()
        sceneRoot = scene
        mainPanel = main
        hudPanel = hud
        ensureTransform(main)
        ensureTransform(hud)
        resumeSpatialTrackingLocked()
        refreshVisiblePanelLocked()
        placeSafeFallbackBeforeFirstHmdFrameLocked()
    }

    fun detach() = synchronized(stateLock) { detachLocked() }

    fun pauseSpatialTracking() = synchronized(stateLock) {
        if (!trackingActive) return@synchronized
        trackingProvider?.let { provider ->
            runCatching { provider.removeListener(trackingListener) }
            runCatching { provider.stop() }
        }
        trackingProvider = null
        trackingActive = false
        latestHeadPose.set(null)
        latestHeadPoseNanos.set(0L)
        receivedFirstHeadPose.set(false)
    }

    fun resumeSpatialTracking() = synchronized(stateLock) { resumeSpatialTrackingLocked() }

    /** Called by [UIFollowSystem] on every Spatial ECS frame. */
    fun update(deltaTimeSeconds: Float) = synchronized(stateLock) {
        if (sceneRoot == null) return
        // AttachmentPanel entities are mounted directly into SpatialView content, so their
        // transforms and HMDTrackingProvider samples already share the same world space.
        // Converting the HMD through the unrelated gameplay root makes panel movement diverge.
        val head = latestFreshHeadWorldPose() ?: return
        val delta = deltaTimeSeconds.coerceIn(0f, 0.1f)
        if (hudPanel?.enabled == true) updateHudLocked(head, delta)
        updateMainPanelLocked(head, delta)
        logFollowStateIfDueLocked(head)
    }

    /** Shared fresh world-space HMD sample for gameplay and panel following. */
    fun latestFreshHeadWorldPose(): HMDPose? {
        val sampleNanos = latestHeadPoseNanos.get()
        if (sampleNanos == 0L || System.nanoTime() - sampleNanos > HMD_TRACKING_TIMEOUT_NANOS) {
            return null
        }
        return latestHeadPose.get()
    }

    fun purchaseTower(type: TowerType) {
        if (!purchaseInFlight.compareAndSet(false, true)) return
        synchronized(stateLock) {
            mutableState.value = mutableState.value.copy(statusMessage = "正在安装${TowerCatalog.get(type).name}")
        }
        Log.i(TAG, "purchase queued type=$type")
        managerScope.launch {
            // Finish the SpatialUI input dispatch before mutating the native ECS tree.
            yield()
            val startedAtNanos = System.nanoTime()
            try {
                val result = TowerManager.purchaseAndAutoPlace(type)
                Log.i(
                    TAG,
                    "purchase completed type=$type result=$result " +
                        "durationMs=${(System.nanoTime() - startedAtNanos) / 1_000_000f}",
                )
                synchronized(stateLock) {
                    mutableState.value =
                        mutableState.value.copy(
                            statusMessage =
                                when (result) {
                                    is TowerPlacementResult.Success ->
                                        "${TowerCatalog.get(type).name}已自动安装到城墙槽位${result.tower.wallMountIndex + 1}"
                                    is TowerPlacementResult.Rejected ->
                                        when (result.reason) {
                                            com.picoxr.mrspacetowerdefense.model.TowerPlacementRejectReason.NOT_PLACEMENT_PHASE ->
                                                "当前阶段不可购买防御塔"
                                            com.picoxr.mrspacetowerdefense.model.TowerPlacementRejectReason.WALL_SLOTS_FULL ->
                                                "城墙9个武器槽位已满"
                                            com.picoxr.mrspacetowerdefense.model.TowerPlacementRejectReason.INSUFFICIENT_GOLD ->
                                                "金币不足"
                                            com.picoxr.mrspacetowerdefense.model.TowerPlacementRejectReason.SCENE_NOT_READY ->
                                                "城墙槽位尚未准备完成"
                                            else -> "武器安装失败：${result.reason.name}"
                                        }
                                },
                        )
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "purchase failed type=$type", throwable)
                synchronized(stateLock) {
                    mutableState.value = mutableState.value.copy(statusMessage = "武器安装失败，请重试")
                }
            } finally {
                purchaseInFlight.set(false)
            }
        }
    }

    fun onTowerPlaced() = synchronized(stateLock) {
        shopSuppressedForPlacement = false
        refreshVisiblePanelLocked()
    }

    fun openTowerUpgrade(towerId: String): Boolean = synchronized(stateLock) {
        if (trackedGameState != GameState.WAVE_PAUSE || TowerManager.towers.value.none { it.id == towerId }) {
            return@synchronized false
        }
        val previousPanel = mutableState.value.activePanel
        mutableState.value =
            mutableState.value.copy(
                activePanel = GamePanel.TOWER_UPGRADE,
                selectedTowerId = towerId,
                statusMessage = "已选择防御塔",
        )
        applyVisibilityLocked()
        if (previousPanel != GamePanel.TOWER_UPGRADE) resetModalPoseLocked()
        true
    }

    fun closeTowerUpgrade() = synchronized(stateLock) {
        mutableState.value = mutableState.value.copy(selectedTowerId = null)
        refreshVisiblePanelLocked()
    }

    fun upgradeSelectedTower() = synchronized(stateLock) {
        val towerId = mutableState.value.selectedTowerId ?: return
        val result = TowerManager.upgradeTower(towerId)
        mutableState.value =
            mutableState.value.copy(
                statusMessage =
                    when (result) {
                        is com.picoxr.mrspacetowerdefense.model.TowerUpgradeResult.Success ->
                            "防御塔已升级至 ${result.tower.level} 级"
                        is com.picoxr.mrspacetowerdefense.model.TowerUpgradeResult.Rejected ->
                            "升级失败：${result.reason.toUiText()}"
                    },
            )
    }

    fun upgradeWall() = synchronized(stateLock) {
        val result = WallManager.upgradeMaxHp()
        mutableState.value =
            mutableState.value.copy(
                statusMessage =
                    when (result) {
                        is com.picoxr.mrspacetowerdefense.model.WallUpgradeResult.Success ->
                            "城墙已升级至 ${result.wallState.level} 级"
                        is com.picoxr.mrspacetowerdefense.model.WallUpgradeResult.Rejected ->
                            "城墙升级失败：${result.reason.name}"
                    },
            )
    }

    fun upgradeWallDamageReduction() = handleWallUpgrade(
        label = "城墙减伤",
        upgrade = WallManager::upgradeDamageReduction,
    )

    fun upgradeWallReflection() = handleWallUpgrade(
        label = "城墙反伤",
        upgrade = WallManager::upgradeReflection,
    )

    fun upgradeWallRegeneration() = handleWallUpgrade(
        label = "自动回血",
        upgrade = WallManager::upgradeRegeneration,
    )

    private fun handleWallUpgrade(
        label: String,
        upgrade: () -> com.picoxr.mrspacetowerdefense.model.WallUpgradeResult,
    ) = synchronized(stateLock) {
        val result = upgrade()
        mutableState.value =
            mutableState.value.copy(
                statusMessage =
                    when (result) {
                        is com.picoxr.mrspacetowerdefense.model.WallUpgradeResult.Success ->
                            "$label 已升级"
                        is com.picoxr.mrspacetowerdefense.model.WallUpgradeResult.Rejected ->
                            "$label 升级失败：${result.reason.name}"
                    },
            )
    }

    fun repairWall() = synchronized(stateLock) {
        val result = WallManager.repair()
        mutableState.value =
            mutableState.value.copy(
                statusMessage =
                    when (result) {
                        is com.picoxr.mrspacetowerdefense.model.WallRepairResult.Success ->
                            "城墙恢复 ${result.recoveredHp} 点生命"
                        is com.picoxr.mrspacetowerdefense.model.WallRepairResult.Rejected ->
                            "修复失败：${result.reason.name}"
                    },
            )
    }

    fun confirmCalibration() {
        synchronized(stateLock) {
            if (
                GameStateManager.state.value != GameState.CALIBRATING ||
                !SceneLayoutManager.requestCalibrationCommit()
            ) {
                mutableState.value = mutableState.value.copy(statusMessage = "请等待主地面与场景布局稳定")
                return
            }
            mutableState.value = mutableState.value.copy(statusMessage = "空间校准完成，请购买并布置防御塔")
            refreshVisiblePanelLocked()
        }
    }

    fun startFirstWave() {
        GameManager.startFirstWave()
        synchronized(stateLock) { mutableState.value = mutableState.value.copy(statusMessage = "第一波已开始，防御塔自动警戒") }
    }

    fun startNextWave() {
        GameManager.startNextWave()
        synchronized(stateLock) { mutableState.value = mutableState.value.copy(statusMessage = "下一波已开始") }
    }

    fun startGame() = GameManager.startGame()

    fun restartGame() = GameManager.restartGame()

    fun openPermanentGrowth() = synchronized(stateLock) {
        growthPanelVisible = true
        refreshVisiblePanelLocked()
    }

    fun closePermanentGrowth() = synchronized(stateLock) {
        growthPanelVisible = false
        refreshVisiblePanelLocked()
    }

    fun upgradePermanentGrowth(type: PermanentUpgradeType) = synchronized(stateLock) {
        val success = GameManager.upgradePermanentGrowth(type)
        val name = PermanentGrowthCatalog.get(type).name
        mutableState.value =
            mutableState.value.copy(statusMessage = if (success) "$name 已升级" else "晶核不足或已满级")
    }

    fun returnToMainMenu() {
        synchronized(stateLock) { growthPanelVisible = false }
        GameManager.returnToMainMenu()
    }

    fun resumeAfterInterruption() = GameManager.resumeAfterInterruption()

    fun isWorldInputBlocked(): Boolean = mutableState.value.activePanel != GamePanel.NONE

    private fun refreshVisiblePanelLocked() {
        val panel =
            when {
                trackedGameState == GameState.IDLE -> GamePanel.MAIN_MENU
                growthPanelVisible && trackedGameState == GameState.SETTLE -> GamePanel.PERMANENT_GROWTH
                trackedGameState == GameState.SETTLE -> GamePanel.SETTLEMENT
                CombatPauseRules.freezesCombat(GameManager.state.value.pauseReasons) &&
                    trackedGameState == GameState.FIGHTING -> GamePanel.SAFETY_PAUSE
                mutableState.value.selectedTowerId != null -> GamePanel.TOWER_UPGRADE
                trackedGameState == GameState.CALIBRATING -> GamePanel.CALIBRATION
                trackedGameState == GameState.PREPARE ->
                    PanelPlacementRules.preparationPanel(
                        shopSuppressedForPlacement = shopSuppressedForPlacement,
                    )
                trackedGameState == GameState.WAVE_PAUSE && !shopSuppressedForPlacement -> GamePanel.SHOP
                else -> GamePanel.NONE
            }
        val settlement = GameManager.state.value.result ?: mutableState.value.settlement
        val previousPanel = mutableState.value.activePanel
        val changed = previousPanel != panel
        mutableState.value = mutableState.value.copy(activePanel = panel, settlement = settlement)
        applyVisibilityLocked()
        // The shared modal AttachmentPanel guarantees only one visible modal. Every modal
        // replacement is a new one-shot placement and must not inherit the old world lock.
        if (changed) resetModalPoseLocked()
    }

    private fun applyVisibilityLocked() {
        val activePanel = mutableState.value.activePanel
        val visibility = PanelPlacementRules.visibilityFor(trackedGameState, activePanel)
        val hudVisibilityChanged = hudPanel?.enabled != visibility.showHud
        mainPanel?.enabled = visibility.showModal
        hudPanel?.enabled = visibility.showHud
        if (hudVisibilityChanged) hudPositioned = false
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "visibility active=$activePanel modal=${visibility.showModal} hud=${visibility.showHud}",
            )
        }
    }

    private fun updateHudLocked(head: HMDPose, deltaTimeSeconds: Float) {
        val entity = hudPanel ?: return
        val transform = ensureTransform(entity)
        val targetYaw = targetHudYawRadians(head.rotation, hudYawRadians)
        val targetPitch = targetHudPitchRadians(head)
        if (!hudPositioned) {
            hudYawRadians = targetYaw
            hudPitchRadians = targetPitch
            hudPositioned = true
        } else {
            val alpha = PanelPlacementRules.smoothingAlpha(deltaTimeSeconds)
            hudYawRadians = PanelPlacementRules.smoothAngleRadians(hudYawRadians, targetYaw, alpha)
            hudPitchRadians += (targetPitch - hudPitchRadians) * alpha
        }
        hudPitchRadians =
            hudPitchRadians.coerceAtLeast(
                PanelPlacementRules.minimumHudPitchRadians(
                    head.position.y,
                    SpatialManager.getGroundHeight(),
                ),
            )
        val target = targetHudPoseForHead(head, hudYawRadians, hudPitchRadians)
        transform.setPosition(target.position)
        transform.setQuaternion(target.rotation)
    }

    private fun updateMainPanelLocked(head: HMDPose, deltaTimeSeconds: Float) {
        val entity = mainPanel ?: return
        val activePanel = mutableState.value.activePanel
        if (!PanelPlacementRules.isWorldLockedModal(activePanel)) return
        val transform = ensureTransform(entity)
        if (!mutablePoseState.value.isPositioned) {
            // Opening and threshold-triggered recovery share the same damped flight.
            // The old direct assignment made panel replacements visibly teleport.
            beginModalRecenterLocked(transform, head, activePanel, "open")
            return
        }

        modalRecenter?.let { recenter ->
            updateModalRecenterLocked(transform, head, recenter, deltaTimeSeconds)
            return
        }

        val lock = lockedModalPose ?: return
        val forward = horizontalViewForward(head.rotation)
        if (
            PanelPlacementRules.shouldRecenterModal(
                headX = head.position.x,
                headZ = head.position.z,
                viewForwardX = forward.x,
                viewForwardZ = forward.z,
                panelX = transform.position.x,
                panelZ = transform.position.z,
                lockedDistanceMeters = lock.referenceDistanceMeters,
            )
        ) {
            beginModalRecenterLocked(transform, head, activePanel, "threshold")
        }
    }

    private fun beginModalRecenterLocked(
        transform: TransformComponent,
        head: HMDPose,
        panel: GamePanel,
        reason: String,
    ) {
        val target = targetModalPoseForHead(head, panel)
        val centerX = head.position.x
        val centerZ = head.position.z
        val fromDx = transform.position.x - centerX
        val fromDz = transform.position.z - centerZ
        val measuredFromRadius = sqrt(fromDx * fromDx + fromDz * fromDz)
        val targetRadius = target.distanceMeters
        val fromRadius =
            PanelPlacementRules.modalRecenterRadiusMeters(
                measuredRadiusMeters = measuredFromRadius,
                targetRadiusMeters = targetRadius,
                easedProgress = 0f,
            )
        val fromYaw =
            if (measuredFromRadius > 0.0001f) atan2(fromDx, -fromDz)
            else atan2(target.position.x - centerX, -(target.position.z - centerZ))
        transform.setPosition(
            Vector3(
                centerX + sin(fromYaw) * fromRadius,
                target.position.y,
                centerZ - cos(fromYaw) * fromRadius,
            ),
        )
        modalRecenter =
            ModalRecenter(
                panel = panel,
                fromYawRadians = fromYaw,
                fromRadiusMeters = fromRadius,
                targetRadiusMeters = targetRadius,
                fromRotation = transform.quaternion,
            )
        mutablePoseState.value = PanelPoseState(isPositioned = true, isRecentering = true)
        logPanelPlacement(panel, target, "$reason-flight-start")
    }

    private fun updateModalRecenterLocked(
        transform: TransformComponent,
        head: HMDPose,
        recenter: ModalRecenter,
        deltaTimeSeconds: Float,
    ) {
        recenter.elapsedSeconds += deltaTimeSeconds
        val progress = PanelPlacementRules.recenterProgress(recenter.elapsedSeconds)
        val eased = PanelPlacementRules.modalFlightEasedProgress(recenter.elapsedSeconds)
        val groundY = SpatialManager.getGroundHeight()
        val currentY = PanelPlacementRules.resolvePanelCenterY(head.position.y, groundY)
        // The destination must remain the latest HMD front throughout the damped move.
        // A start-frame snapshot completed beside the player if they kept turning.
        val liveTarget = targetModalPoseForHead(head, recenter.panel)
        val targetYaw =
            atan2(
                liveTarget.position.x - head.position.x,
                -(liveTarget.position.z - head.position.z),
            )
        val yaw =
            PanelPlacementRules.smoothAngleRadians(
                recenter.fromYawRadians,
                targetYaw,
                eased,
            )
        val radius =
            PanelPlacementRules.modalRecenterRadiusMeters(
                measuredRadiusMeters = recenter.fromRadiusMeters,
                targetRadiusMeters = recenter.targetRadiusMeters,
                easedProgress = eased,
            )
        transform.setPosition(
            Vector3(
                head.position.x + sin(yaw) * radius,
                currentY,
                head.position.z - cos(yaw) * radius,
            ),
        )
        transform.setQuaternion(Quat.slerp(recenter.fromRotation, liveTarget.rotation, eased))
        if (progress < 1f) return

        val finalPosition =
            Vector3(
                head.position.x + sin(targetYaw) * recenter.targetRadiusMeters,
                currentY,
                head.position.z - cos(targetYaw) * recenter.targetRadiusMeters,
            )
        transform.setPosition(finalPosition)
        transform.setQuaternion(liveTarget.rotation)
        lockedModalPose = LockedModalPose(recenter.panel, recenter.targetRadiusMeters)
        modalRecenter = null
        mutablePoseState.value = PanelPoseState(isPositioned = true, isRecentering = false)
        logPanelPlacement(
            recenter.panel,
            liveTarget.copy(
                position = finalPosition,
                distanceMeters = recenter.targetRadiusMeters,
                headY = head.position.y,
                groundY = groundY,
                usedFallback = currentY != head.position.y,
            ),
            "front-flight-complete-locked",
        )
    }

    /**
     * Attachment entities otherwise remain at the SDK default origin until the
     * asynchronous HMD provider emits its first pose. Keep them above the floor in
     * that interval, then let updateMainPanelLocked replace this with the real HMD pose.
     */
    private fun placeSafeFallbackBeforeFirstHmdFrameLocked() {
        val panel = mutableState.value.activePanel
        val groundY = SpatialManager.getGroundHeight().takeIf { it.isFinite() } ?: 0f
        val fallbackHead =
            HMDPose(
                position = Vector3(0f, groundY + PanelPlacementRules.FALLBACK_EYE_HEIGHT_METERS, 0f),
                rotation = Quat.identity(),
            )
        if (panel != GamePanel.NONE) {
            val target =
                targetModalPoseForHead(fallbackHead, panel).copy(
                    headY = Float.NaN,
                    groundY = groundY,
                    usedFallback = true,
                )
            mainPanel?.let { entity ->
                ensureTransform(entity).apply {
                    setPosition(target.position)
                    setQuaternion(target.rotation)
                }
            }
            logPanelPlacement(panel, target, "attach-fallback")
        }
        hudPanel?.let { entity ->
            val target =
                targetHudPoseForHead(
                    fallbackHead,
                    yawRadians = 0f,
                    pitchRadians = -PanelPlacementRules.HUD_DOWN_ANGLE_RADIANS,
                )
            ensureTransform(entity).apply {
                setPosition(target.position)
                setQuaternion(target.rotation)
            }
        }
        // The fallback is intentionally not considered a completed placement.
        resetModalPoseLocked()
        hudPositioned = false
    }

    private fun targetModalPoseForHead(head: HMDPose, panel: GamePanel): ResolvedPanelPose {
        val forward = horizontalViewForward(head.rotation)
        val requestedDistance = PanelPlacementRules.targetDistance(panel)
        val resolvedDistance =
            PanelPlacementRules.resolveCenteredModalDistance(requestedDistance) { candidateDistance ->
                isPanelFootprintInObstacle(
                    head = head.position,
                    forward = forward,
                    distance = candidateDistance,
                    halfWidth = PanelPlacementRules.panelHalfWidth(panel),
                )
            }
        val groundY = SpatialManager.getGroundHeight()
        val panelY = PanelPlacementRules.resolvePanelCenterY(head.position.y, groundY)
        val position =
            Vector3(
                head.position.x + forward.x * resolvedDistance,
                panelY,
                head.position.z + forward.z * resolvedDistance,
            )
        // Modal panels remain upright: +Z is the visible face and points back to the HMD.
        val levelHead = Vector3(head.position.x, panelY, head.position.z)
        val rotation = Matrix4.lookAt(levelHead, position, Vector3.UP).inverse().rotation
        return ResolvedPanelPose(
            position = position,
            rotation = rotation,
            distanceMeters = resolvedDistance,
            headY = head.position.y,
            groundY = groundY,
            usedFallback = panelY != head.position.y,
        )
    }

    private fun targetHudPoseForHead(
        head: HMDPose,
        yawRadians: Float,
        pitchRadians: Float,
    ): ResolvedPanelPose {
        val horizontalDistance = PanelPlacementRules.hudHorizontalDistanceAtPitch(pitchRadians)
        val panelY = head.position.y + PanelPlacementRules.hudVerticalOffsetAtPitch(pitchRadians)
        val groundY = SpatialManager.getGroundHeight()
        val position =
            Vector3(
                head.position.x + sin(yawRadians) * horizontalDistance,
                panelY,
                head.position.z - cos(yawRadians) * horizontalDistance,
            )
        // The HUD is highest-sort UI, not a physical world panel. Obstacle retreat
        // previously compressed its 1.2 m radial distance to as little as 0.43 m.
        // Modal panels retain centreline-only obstacle avoidance; HUD distance stays fixed.
        val rotation = Matrix4.lookAt(head.position, position, Vector3.UP).inverse().rotation
        return ResolvedPanelPose(
            position = position,
            rotation = rotation,
            distanceMeters = PanelPlacementRules.HUD_DISTANCE_METERS,
            headY = head.position.y,
            groundY = groundY,
            usedFallback = false,
        )
    }

    private fun isPanelFootprintInObstacle(
        head: Vector3,
        forward: Vector3,
        distance: Float,
        halfWidth: Float,
    ): Boolean {
        val centerX = head.x + forward.x * distance
        val centerZ = head.z + forward.z * distance
        val horizontalLength = sqrt(forward.x * forward.x + forward.z * forward.z)
        if (horizontalLength <= 0.0001f || halfWidth <= 0f) {
            return SpatialManager.isPositionInObstacle(centerX, centerZ)
        }
        val rightX = forward.z / horizontalLength
        val rightZ = -forward.x / horizontalLength
        return SpatialManager.isPositionInObstacle(centerX, centerZ) ||
            SpatialManager.isPositionInObstacle(centerX + rightX * halfWidth, centerZ + rightZ * halfWidth) ||
            SpatialManager.isPositionInObstacle(centerX - rightX * halfWidth, centerZ - rightZ * halfWidth)
    }

    private fun logPanelPlacement(panel: GamePanel, pose: ResolvedPanelPose, reason: String) {
        val position = pose.position
        Log.i(
            TAG,
            "panel=$panel reason=$reason 面板世界坐标(${position.x},${position.y},${position.z}) " +
                "头部Y坐标=${pose.headY} 地面Y坐标=${pose.groundY} " +
                "distance=${pose.distanceMeters} fallback=${pose.usedFallback}",
        )
    }

    private fun logFollowStateIfDueLocked(head: HMDPose) {
        if (!BuildConfig.DEBUG) return
        val now = System.nanoTime()
        if (now - lastFollowLogNanos < FOLLOW_LOG_INTERVAL_NANOS) return
        lastFollowLogNanos = now
        val panelPosition =
            mainPanel?.components?.get(TransformComponent::class.java)?.position ?: return
        val hudPosition = hudPanel?.components?.get(TransformComponent::class.java)?.position
        val hudDistance =
            if (hudPanel?.enabled == true && hudPosition != null) {
                val dx = hudPosition.x - head.position.x
                val dy = hudPosition.y - head.position.y
                val dz = hudPosition.z - head.position.z
                sqrt(dx * dx + dy * dy + dz * dz)
            } else {
                Float.NaN
            }
        Log.d(
            TAG,
            "follow head=(${head.position.x},${head.position.y},${head.position.z}) " +
                "panel=(${panelPosition.x},${panelPosition.y},${panelPosition.z}) " +
                "hudDistance=$hudDistance active=${mutableState.value.activePanel}",
        )
    }

    private fun horizontalViewForward(rotation: Quat): Vector3 {
        val raw = rotation.rotateVector(Vector3.BACK)
        val length = sqrt(raw.x * raw.x + raw.z * raw.z)
        if (length <= 0.0001f) return Vector3.BACK
        return Vector3(raw.x / length, 0f, raw.z / length)
    }

    private fun targetHudYawRadians(rotation: Quat, fallbackYawRadians: Float): Float {
        val raw = rotation.rotateVector(Vector3.BACK)
        val horizontalLength = sqrt(raw.x * raw.x + raw.z * raw.z)
        // Yaw is undefined while looking almost straight up/down. Retaining the
        // previous yaw prevents a sudden world-back snap during a deep head tilt.
        if (horizontalLength <= 0.05f) return fallbackYawRadians
        return atan2(raw.x, -raw.z)
    }

    private fun targetHudPitchRadians(head: HMDPose): Float {
        val raw = head.rotation.rotateVector(Vector3.BACK)
        val length = sqrt(raw.x * raw.x + raw.y * raw.y + raw.z * raw.z)
        if (length <= 0.0001f) return -PanelPlacementRules.HUD_DOWN_ANGLE_RADIANS
        val viewPitch = asin((raw.y / length).coerceIn(-1f, 1f))
        return (viewPitch - PanelPlacementRules.HUD_DOWN_ANGLE_RADIANS)
            .coerceIn(
                -PanelPlacementRules.HUD_MAX_PITCH_RADIANS,
                PanelPlacementRules.HUD_MAX_PITCH_RADIANS,
            )
            .coerceAtLeast(
                PanelPlacementRules.minimumHudPitchRadians(
                    head.position.y,
                    SpatialManager.getGroundHeight(),
                ),
            )
    }

    private fun horizontalDistance(first: Vector3, second: Vector3): Float {
        val dx = second.x - first.x
        val dz = second.z - first.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun resetModalPoseLocked() {
        lockedModalPose = null
        modalRecenter = null
        mutablePoseState.value = PanelPoseState()
    }

    private fun ensureTransform(entity: Entity): TransformComponent =
        entity.components[TransformComponent::class.java]
            ?: TransformComponent().also { entity.components[TransformComponent::class.java] = it }

    private data class ResolvedPanelPose(
        val position: Vector3,
        val rotation: Quat,
        val distanceMeters: Float,
        val headY: Float,
        val groundY: Float,
        val usedFallback: Boolean,
    )

    private data class LockedModalPose(
        val panel: GamePanel,
        val referenceDistanceMeters: Float,
    )

    private data class ModalRecenter(
        val panel: GamePanel,
        val fromYawRadians: Float,
        val fromRadiusMeters: Float,
        val targetRadiusMeters: Float,
        val fromRotation: Quat,
        var elapsedSeconds: Float = 0f,
    )

    private fun detachLocked() {
        if (trackingActive) {
            trackingProvider?.let { provider ->
                runCatching { provider.removeListener(trackingListener) }
                runCatching { provider.stop() }
            }
        }
        trackingProvider = null
        trackingActive = false
        latestHeadPose.set(null)
        latestHeadPoseNanos.set(0L)
        receivedFirstHeadPose.set(false)
        lastFollowLogNanos = 0L
        sceneRoot = null
        mainPanel = null
        hudPanel = null
        hudPositioned = false
        hudYawRadians = 0f
        hudPitchRadians = -PanelPlacementRules.HUD_DOWN_ANGLE_RADIANS
        resetModalPoseLocked()
    }

    private fun resumeSpatialTrackingLocked() {
        if (trackingActive || sceneRoot == null) return
        // Provider construction is deliberately deferred until the Stage/SpatialView exists.
        // Creating it during Application singleton initialization can return SUCCESS without
        // delivering pose frames on a physical device.
        val provider = HMDTrackingProvider()
        trackingProvider = provider
        provider.addListener(trackingListener)
        val startResult = provider.start()
        Log.i(
            TAG,
            "HMD tracking start result=$startResult state=${provider.state} support=${provider.supportState}",
        )
        trackingActive = true
    }

    private const val FOLLOW_LOG_INTERVAL_NANOS = 1_000_000_000L

    override fun onDestroy() {
        synchronized(stateLock) {
            purchaseInFlight.set(false)
            detachLocked()
            shopSuppressedForPlacement = false
            growthPanelVisible = false
            trackedGameState = GameState.IDLE
            mutableState.value = UIRuntimeState()
        }
    }

    private fun com.picoxr.mrspacetowerdefense.model.TowerUpgradeRejectReason.toUiText(): String =
        when (this) {
            com.picoxr.mrspacetowerdefense.model.TowerUpgradeRejectReason.NOT_WAVE_PAUSE -> "仅波次暂停时可升级"
            com.picoxr.mrspacetowerdefense.model.TowerUpgradeRejectReason.TOWER_NOT_FOUND -> "防御塔不存在"
            com.picoxr.mrspacetowerdefense.model.TowerUpgradeRejectReason.MAX_LEVEL -> "已达到最高等级"
            com.picoxr.mrspacetowerdefense.model.TowerUpgradeRejectReason.INSUFFICIENT_GOLD -> "金币不足"
        }
}
