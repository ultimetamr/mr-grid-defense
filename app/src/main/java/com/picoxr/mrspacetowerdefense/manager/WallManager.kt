package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Matrix4
import com.pico.spatial.core.math.Vector3
import com.picoxr.mrspacetowerdefense.event.EventBus
import com.picoxr.mrspacetowerdefense.event.WallBrokenEvent
import com.picoxr.mrspacetowerdefense.event.WallHpChangedEvent
import com.picoxr.mrspacetowerdefense.event.WallRepairedEvent
import com.picoxr.mrspacetowerdefense.event.WallUpgradedEvent
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.SceneLayout
import com.picoxr.mrspacetowerdefense.model.WallRepairRejectReason
import com.picoxr.mrspacetowerdefense.model.WallRepairResult
import com.picoxr.mrspacetowerdefense.model.WallDamageResult
import com.picoxr.mrspacetowerdefense.model.WallState
import com.picoxr.mrspacetowerdefense.model.WallUpgradeType
import com.picoxr.mrspacetowerdefense.model.WallUpgradeRejectReason
import com.picoxr.mrspacetowerdefense.model.WallUpgradeResult
import kotlin.math.ceil
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object WallManager : BaseManager() {
    private const val HIT_FLASH_DURATION_SECONDS = 0.22f

    private val stateLock = Any()
    private val mutableWallState =
        MutableStateFlow(
            WallState(
                maxHp = WallEconomyRules.INITIAL_MAX_HP,
                currentHp = WallEconomyRules.INITIAL_MAX_HP,
                level = 1,
            ),
        )

    private var hostRoot: Entity? = null
    private var wallRoot: Entity? = null
    private var wallMesh: MeshResource? = null
    private var wallMaterial: PhysicallyBasedMaterial? = null
    private var slotMesh: MeshResource? = null
    private var slotMaterial: PhysicallyBasedMaterial? = null
    private var debugMesh: MeshResource? = null
    private var debugMaterial: UnlitMaterial? = null
    private var layoutJob: Job? = null
    private var hitFlashRemaining = 0f
    private var regenerationElapsed = 0f
    private var feedbackMode = WallFeedbackMode.NORMAL
    private var brokenEventSent = false

    val wallState: StateFlow<WallState> = mutableWallState.asStateFlow()

    override fun onInitialize(applicationContext: Context) = Unit

    fun attach(root: Entity) = synchronized(stateLock) {
        if (hostRoot === root && layoutJob?.isActive == true) return@synchronized
        detachVisualsLocked()
        hostRoot = root
        layoutJob =
            managerScope.launch {
                SceneLayoutManager.layout.collectLatest { layout ->
                    synchronized(stateLock) { rebuildWallVisualLocked(layout) }
                }
            }
    }

    fun detach() = synchronized(stateLock) { detachVisualsLocked() }

    internal fun setSceneVisible(visible: Boolean) = synchronized(stateLock) {
        wallRoot?.enabled = visible
    }

    fun takeDamage(damage: Float): WallDamageResult = synchronized(stateLock) {
        val previous = mutableWallState.value
        if (damage <= 0f || damage.isNaN() || previous.currentHp <= 0) {
            return@synchronized WallDamageResult(damage, 0, 0f, previous.currentHp)
        }
        val sessionReduction = WallEconomyRules.sessionDamageReduction(previous.damageReductionLevel)
        val permanentReduction = SaveManager.bonuses.value.wallDamageReduction.coerceIn(0f, 0.8f)
        val combinedReduction = 1f - (1f - sessionReduction) * (1f - permanentReduction)
        val reducedDamage = damage * (1f - combinedReduction.coerceIn(0f, 0.9f))
        val integerDamage =
            if (reducedDamage.isFinite()) ceil(reducedDamage).toInt().coerceAtLeast(1)
            else previous.currentHp
        val current = previous.copy(currentHp = (previous.currentHp - integerDamage).coerceAtLeast(0))
        mutableWallState.value = current
        hitFlashRemaining = HIT_FLASH_DURATION_SECONDS
        feedbackMode =
            if (previous.reflectionLevel > 0) WallFeedbackMode.REFLECT else WallFeedbackMode.HIT
        applyFeedbackMaterialLocked()
        EventBus.post(WallHpChangedEvent(previous.currentHp, current.currentHp, current.maxHp))
        if (current.currentHp == 0 && !brokenEventSent) {
            brokenEventSent = true
            EventBus.post(WallBrokenEvent(current.level, current.maxHp))
        }
        WallDamageResult(
            incomingDamage = damage,
            appliedDamage = previous.currentHp - current.currentHp,
            reflectedDamage = damage * WallEconomyRules.reflectionRatio(previous.reflectionLevel),
            remainingHp = current.currentHp,
        )
    }

    internal fun resetSession() = synchronized(stateLock) {
        val initialHp = initialMaxHp()
        mutableWallState.value =
            WallState(
                maxHp = initialHp,
                currentHp = initialHp,
                level = 1,
            )
        brokenEventSent = false
        hitFlashRemaining = 0f
        regenerationElapsed = 0f
        feedbackMode = WallFeedbackMode.NORMAL
        applyFeedbackMaterialLocked()
        if (wallRoot == null) rebuildWallVisualLocked(SceneLayoutManager.layout.value)
    }

    internal fun releaseBattleResources() = synchronized(stateLock) {
        releaseWallVisualLocked()
        hitFlashRemaining = 0f
    }

    fun upgradeMaxHp(): WallUpgradeResult = synchronized(stateLock) {
        if (GameStateManager.state.value != GameState.WAVE_PAUSE) {
            return@synchronized WallUpgradeResult.Rejected(WallUpgradeRejectReason.NOT_WAVE_PAUSE)
        }
        val previous = mutableWallState.value
        if (previous.level >= WallEconomyRules.MAX_LEVEL) {
            return@synchronized WallUpgradeResult.Rejected(WallUpgradeRejectReason.MAX_LEVEL)
        }
        val cost = WallEconomyRules.upgradeCost(previous.level)
        if (!GoldManager.costGold(cost, "wall_max_hp_level_${previous.level + 1}")) {
            return@synchronized WallUpgradeResult.Rejected(WallUpgradeRejectReason.INSUFFICIENT_GOLD)
        }
        val current =
            previous.copy(
                maxHp = previous.maxHp + WallEconomyRules.HP_PER_LEVEL,
                level = previous.level + 1,
            )
        mutableWallState.value = current
        EventBus.post(WallHpChangedEvent(previous.currentHp, current.currentHp, current.maxHp))
        EventBus.post(
            WallUpgradedEvent(
                previousLevel = previous.level,
                currentLevel = current.level,
                previousMaxHp = previous.maxHp,
                currentMaxHp = current.maxHp,
                goldCost = cost,
            ),
        )
        WallUpgradeResult.Success(current, cost, WallUpgradeType.MAX_HP)
    }

    fun upgradeDamageReduction(): WallUpgradeResult = synchronized(stateLock) {
        upgradeSecondaryLocked(
            WallUpgradeType.DAMAGE_REDUCTION,
            mutableWallState.value.damageReductionLevel,
            WallEconomyRules.DAMAGE_REDUCTION_MAX_LEVEL,
            WallEconomyRules::damageReductionUpgradeCost,
        ) { state -> state.copy(damageReductionLevel = state.damageReductionLevel + 1) }
    }

    fun upgradeReflection(): WallUpgradeResult = synchronized(stateLock) {
        upgradeSecondaryLocked(
            WallUpgradeType.REFLECTION,
            mutableWallState.value.reflectionLevel,
            WallEconomyRules.REFLECTION_MAX_LEVEL,
            WallEconomyRules::reflectionUpgradeCost,
        ) { state -> state.copy(reflectionLevel = state.reflectionLevel + 1) }
    }

    fun upgradeRegeneration(): WallUpgradeResult = synchronized(stateLock) {
        upgradeSecondaryLocked(
            WallUpgradeType.REGENERATION,
            mutableWallState.value.regenerationLevel,
            WallEconomyRules.REGENERATION_MAX_LEVEL,
            WallEconomyRules::regenerationUpgradeCost,
        ) { state -> state.copy(regenerationLevel = state.regenerationLevel + 1) }
    }

    private fun upgradeSecondaryLocked(
        type: WallUpgradeType,
        currentLevel: Int,
        maxLevel: Int,
        costForLevel: (Int) -> Int,
        update: (WallState) -> WallState,
    ): WallUpgradeResult {
        if (GameStateManager.state.value != GameState.WAVE_PAUSE) {
            return WallUpgradeResult.Rejected(WallUpgradeRejectReason.NOT_WAVE_PAUSE)
        }
        if (currentLevel >= maxLevel) {
            return WallUpgradeResult.Rejected(WallUpgradeRejectReason.MAX_LEVEL)
        }
        val cost = costForLevel(currentLevel)
        if (!GoldManager.costGold(cost, "wall_${type.name.lowercase()}_${currentLevel + 1}")) {
            return WallUpgradeResult.Rejected(WallUpgradeRejectReason.INSUFFICIENT_GOLD)
        }
        val updated = update(mutableWallState.value)
        mutableWallState.value = updated
        return WallUpgradeResult.Success(updated, cost, type)
    }

    fun repair(): WallRepairResult = synchronized(stateLock) {
        if (GameStateManager.state.value != GameState.WAVE_PAUSE) {
            return@synchronized WallRepairResult.Rejected(WallRepairRejectReason.NOT_WAVE_PAUSE)
        }
        val previous = mutableWallState.value
        if (previous.currentHp >= previous.maxHp) {
            return@synchronized WallRepairResult.Rejected(WallRepairRejectReason.ALREADY_FULL)
        }
        if (!GoldManager.costGold(WallEconomyRules.REPAIR_COST, "wall_repair")) {
            return@synchronized WallRepairResult.Rejected(WallRepairRejectReason.INSUFFICIENT_GOLD)
        }
        val repairedHp =
            WallEconomyRules.repairedHp(
                previous.currentHp,
                previous.maxHp,
                SaveManager.bonuses.value.repairEfficiencyBonus,
            )
        val current = previous.copy(currentHp = repairedHp)
        mutableWallState.value = current
        showPositiveFeedbackLocked(WallFeedbackMode.HEAL)
        if (current.currentHp > 0) brokenEventSent = false
        EventBus.post(WallHpChangedEvent(previous.currentHp, current.currentHp, current.maxHp))
        EventBus.post(
            WallRepairedEvent(
                previousHp = previous.currentHp,
                currentHp = current.currentHp,
                maxHp = current.maxHp,
                goldCost = WallEconomyRules.REPAIR_COST,
            ),
        )
        WallRepairResult.Success(
            wallState = current,
            cost = WallEconomyRules.REPAIR_COST,
            recoveredHp = current.currentHp - previous.currentHp,
        )
    }

    fun onFrame(deltaTimeSeconds: Float) = synchronized(stateLock) {
        val delta = deltaTimeSeconds.coerceIn(0f, 0.1f)
        if (GameManager.isCombatSimulationActive() && mutableWallState.value.regenerationLevel > 0) {
            regenerationElapsed += delta
            if (regenerationElapsed >= 1f) {
                regenerationElapsed %= 1f
                healLocked(mutableWallState.value.regenerationLevel)
            }
        }
        if (hitFlashRemaining > 0f) {
            hitFlashRemaining = (hitFlashRemaining - delta).coerceAtLeast(0f)
            if (hitFlashRemaining == 0f) {
                feedbackMode = WallFeedbackMode.NORMAL
                applyFeedbackMaterialLocked()
            }
        }
    }

    private fun healLocked(amount: Int) {
        if (amount <= 0) return
        val previous = mutableWallState.value
        if (previous.currentHp <= 0 || previous.currentHp >= previous.maxHp) return
        val current = previous.copy(currentHp = (previous.currentHp + amount).coerceAtMost(previous.maxHp))
        mutableWallState.value = current
        showPositiveFeedbackLocked(WallFeedbackMode.HEAL)
        EventBus.post(WallHpChangedEvent(previous.currentHp, current.currentHp, current.maxHp))
    }

    private fun showPositiveFeedbackLocked(mode: WallFeedbackMode) {
        feedbackMode = mode
        hitFlashRemaining = HIT_FLASH_DURATION_SECONDS
        applyFeedbackMaterialLocked()
    }

    private fun initialMaxHp(): Int =
        PermanentGrowthCatalog.applyMultiplier(
            WallEconomyRules.INITIAL_MAX_HP,
            SaveManager.bonuses.value.wallInitialHpBonus,
        ).coerceAtLeast(1)

    private fun rebuildWallVisualLocked(layout: SceneLayout?) {
        releaseWallVisualLocked()
        val parent = hostRoot ?: return
        if (layout == null) return
        val wall = layout.wall
        val root = Entity().apply {
            setName("DefenseWallRuntimeRoot")
            enabled = GameStateManager.state.value != GameState.IDLE
            components[TransformComponent::class.java]?.setPosition(
                Vector3(wall.center.x, wall.center.y, wall.center.z),
            )
            components[TransformComponent::class.java]?.setQuaternion(
                Matrix4.rotateYByDegrees(Math.toDegrees(wall.rotationYRadians.toDouble()).toFloat()).rotation,
            )
        }
        val mesh = MeshResource.createBox(Vector3(wall.width, layout.wallHeight, wall.depth), 0.03f)
        val material = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE)
        val model =
            ModelEntity(
                mesh,
                material,
            ).apply {
                setName("DefenseWallVisual")
                components[TransformComponent::class.java]?.setPosition(
                    Vector3(0f, layout.wallHeight / 2f, 0f),
                )
            }
        root.addChild(model)
        val mountMesh =
            MeshResource.createBox(
                Vector3(
                    SceneLayoutCalculator.wallSlotPadWidth(wall.width),
                    SceneLayoutCalculator.WALL_SLOT_PAD_HEIGHT,
                    SceneLayoutCalculator.WALL_SLOT_PAD_DEPTH,
                ),
                0.004f,
            )
        val mountMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(0.08f, 0.32f, 0.58f, 1f))
                setEmissiveColor(Color4(0.02f, 0.22f, 0.55f, 1f))
            }
        val slotSpacing = wall.width / SceneLayoutCalculator.WALL_WEAPON_SLOT_COUNT
        repeat(SceneLayoutCalculator.WALL_WEAPON_SLOT_COUNT) { placementIndex ->
            root.addChild(
                ModelEntity(mountMesh, mountMaterial).apply {
                    setName("WallWeaponSlot_$placementIndex")
                    components[TransformComponent::class.java]?.setPosition(
                        Vector3(
                            WallWeaponSlotRules.lateralOffset(placementIndex, slotSpacing),
                            layout.wallHeight + SceneLayoutCalculator.WALL_SLOT_PAD_HEIGHT / 2f,
                            0f,
                        ),
                    )
                },
            )
        }
        if (SceneLayoutManager.isGroundingDebugEnabled()) {
            val markerMesh = MeshResource.createBox(Vector3(wall.width, 0.008f, wall.depth), 0f)
            val markerMaterial =
                UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                    setBaseColor(Color4(1f, 0f, 0f, 1f))
                }
            root.addChild(
                ModelEntity(
                    markerMesh,
                    markerMaterial,
                ).apply {
                    setName("DefenseWallGroundingDebugMarker")
                    components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0.004f, 0f))
                },
            )
            debugMesh = markerMesh
            debugMaterial = markerMaterial
        }
        parent.addChild(root)
        wallRoot = root
        wallMesh = mesh
        wallMaterial = material
        slotMesh = mountMesh
        slotMaterial = mountMaterial
        applyFeedbackMaterialLocked()
    }

    private fun applyFeedbackMaterialLocked() {
        wallMaterial?.apply {
            when (feedbackMode) {
                WallFeedbackMode.HIT -> {
                    setBaseColor(Color4(0.95f, 0.05f, 0.05f, 1f))
                    setEmissiveColor(Color4(0.85f, 0.01f, 0.01f, 1f))
                }
                WallFeedbackMode.REFLECT -> {
                    setBaseColor(Color4(0.65f, 0.12f, 0.9f, 1f))
                    setEmissiveColor(Color4(0.55f, 0.04f, 0.85f, 1f))
                }
                WallFeedbackMode.HEAL -> {
                    setBaseColor(Color4(0.12f, 0.78f, 0.32f, 1f))
                    setEmissiveColor(Color4(0.04f, 0.62f, 0.18f, 1f))
                }
                WallFeedbackMode.NORMAL -> {
                    setBaseColor(Color4(0.32f, 0.38f, 0.46f, 1f))
                    setEmissiveColor(Color4(0.015f, 0.02f, 0.03f, 1f))
                }
            }
        }
    }

    private fun detachVisualsLocked() {
        layoutJob?.cancel()
        layoutJob = null
        releaseWallVisualLocked()
        hostRoot = null
        hitFlashRemaining = 0f
    }

    private fun releaseWallVisualLocked() {
        wallRoot?.destroy()
        wallRoot = null
        wallMesh?.close()
        wallMaterial?.close()
        slotMesh?.close()
        slotMaterial?.close()
        debugMesh?.close()
        debugMaterial?.close()
        wallMesh = null
        wallMaterial = null
        slotMesh = null
        slotMaterial = null
        debugMesh = null
        debugMaterial = null
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            detachVisualsLocked()
            resetSession()
        }
    }

    private enum class WallFeedbackMode { NORMAL, HIT, REFLECT, HEAL }
}
