package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Matrix4
import com.pico.spatial.core.math.Vector3
import com.picoxr.mrspacetowerdefense.event.EventBus
import com.picoxr.mrspacetowerdefense.event.PlayerDiedEvent
import com.picoxr.mrspacetowerdefense.event.RayTriggeredEvent
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.GridCellRuntimeState
import com.picoxr.mrspacetowerdefense.model.GridCellState
import com.picoxr.mrspacetowerdefense.model.GridPhase
import com.picoxr.mrspacetowerdefense.model.GridRuntimeState
import com.picoxr.mrspacetowerdefense.model.SceneLayout
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object GridManager : BaseManager() {
    private const val TAG = "GridManager"
    private const val GRID_VISUAL_SIZE = SceneLayoutCalculator.GRID_CELL_SIZE - 0.04f
    private const val FLOOR_THICKNESS = 0.014f

    private val stateLock = Any()
    private val mutableState = MutableStateFlow(GridRuntimeState())
    private val cellVisuals = ArrayList<GridCellVisual>(GridRules.GRID_COUNT)

    private var hostRoot: Entity? = null
    private var visualRoot: Entity? = null
    private var layoutJob: Job? = null
    private var floorMesh: MeshResource? = null
    private var beamMesh: MeshResource? = null
    private var sharedBeamMaterial: PhysicallyBasedMaterial? = null
    private var lastGameState = GameState.IDLE
    private var phase = GridPhase.PAUSED
    private var safeGridIndex: Int? = null
    private var pendingSafeGridIndex: Int? = null
    private var warningElapsed = 0f
    private var beamElapsed = 0f
    private var cycleElapsed = 0f
    private var safetyCheckElapsed = 0f
    private var playerX: Float? = null
    private var playerY: Float? = null
    private var playerZ: Float? = null
    private var deathHandled = false
    private var visibleHazardCycleReported = false

    val state: StateFlow<GridRuntimeState> = mutableState.asStateFlow()

    override fun onInitialize(applicationContext: Context) = Unit

    fun attach(root: Entity) = synchronized(stateLock) {
        if (hostRoot === root && layoutJob?.isActive == true) return@synchronized
        detachLocked()
        hostRoot = root
        layoutJob =
            managerScope.launch {
                SceneLayoutManager.layout.collectLatest { layout ->
                    synchronized(stateLock) { rebuildVisualsLocked(layout) }
                }
            }
    }

    fun detach() = synchronized(stateLock) { detachLocked() }

    internal fun setSceneVisible(visible: Boolean) = synchronized(stateLock) {
        visualRoot?.enabled = visible
    }

    fun updatePlayerWorldPosition(x: Float, y: Float, z: Float) = synchronized(stateLock) {
        playerX = x
        playerY = y
        playerZ = z
    }

    fun clearPlayerWorldPosition() = synchronized(stateLock) {
        playerX = null
        playerY = null
        playerZ = null
    }

    /** Invoked once per Spatial ECS frame by [GridSystem]. */
    fun onFrame(deltaTimeSeconds: Float) = synchronized(stateLock) {
        val gameState = GameStateManager.state.value
        if (gameState != lastGameState) {
            if (gameState == GameState.FIGHTING) {
                deathHandled = false
                visibleHazardCycleReported = false
                beginMovementWindowLocked()
            } else {
                resetToPausedLocked()
            }
            lastGameState = gameState
        }
        if (gameState != GameState.FIGHTING) return
        if (!GameManager.isHazardSimulationActive()) return

        val delta = deltaTimeSeconds.coerceIn(0f, 0.1f)
        when (phase) {
            GridPhase.COOLDOWN -> {
                cycleElapsed += delta
                if (cycleElapsed >= rayTriggerDelaySecondsLocked()) activateDangerLocked()
            }

            GridPhase.WARNING -> {
                warningElapsed += delta
                cycleElapsed += delta
                applyVisualsLocked()
                publishStateLocked()
                if (warningElapsed >= warningDurationSecondsLocked()) finishWarningLocked()
            }

            GridPhase.RAISING -> {
                beamElapsed += delta
                cycleElapsed += delta
                applyVisualsLocked()
                publishStateLocked()
                if (beamElapsed >= GridRules.BEAM_RAISE_DURATION_SECONDS) {
                    phase = GridPhase.ACTIVE
                    cycleElapsed = 0f
                    safetyCheckElapsed = PerformanceTuning.RAYCAST_INTERVAL_SECONDS
                    if (!visibleHazardCycleReported) {
                        Log.i(
                            TAG,
                            "First red beams fully raised for wave=${WaveManager.currentWave.value?.waveIndex}",
                        )
                    }
                    applyVisualsLocked()
                    publishStateLocked()
                    runSafetyCheckIfDueLocked()
                }
            }

            GridPhase.ACTIVE -> {
                cycleElapsed += delta
                safetyCheckElapsed += delta
                runSafetyCheckIfDueLocked()
                if (cycleElapsed >= GridRules.BEAM_ACTIVE_DURATION_SECONDS) {
                    if (!visibleHazardCycleReported) {
                        visibleHazardCycleReported = true
                        WaveManager.onVisibleHazardCycleCompleted()
                    }
                    // Completion can be delivered immediately on Dispatchers.Main.immediate.
                    // Do not restart warning visuals if that delivery already paused the wave.
                    if (GameStateManager.state.value == GameState.FIGHTING) {
                        beginMovementWindowLocked()
                    }
                }
            }

            GridPhase.PAUSED -> Unit
        }
    }

    private fun beginMovementWindowLocked() {
        safeGridIndex =
            GridRules.nextSafeIndex(
                previousIndex = safeGridIndex,
                randomValue = Random.nextInt(if (safeGridIndex == null) 9 else 8),
            )
        pendingSafeGridIndex = safeGridIndex
        TowerManager.updateSafeGridIndex(checkNotNull(safeGridIndex))
        phase = GridPhase.WARNING
        warningElapsed = 0f
        beamElapsed = 0f
        cycleElapsed = 0f
        safetyCheckElapsed = 0f
        applyVisualsLocked()
        publishStateLocked()
    }

    private fun finishWarningLocked() {
        pendingSafeGridIndex = null
        phase = GridPhase.COOLDOWN
        applyVisualsLocked()
        publishStateLocked()
    }

    private fun activateDangerLocked() {
        checkNotNull(safeGridIndex)
        pendingSafeGridIndex = null
        phase = GridPhase.RAISING
        beamElapsed = 0f
        cycleElapsed = 0f
        applyVisualsLocked()
        publishStateLocked()
    }

    private fun rayTriggerDelaySecondsLocked(): Float {
        val interval =
            (WaveManager.currentWave.value?.rayRefreshInterval
                ?: WaveCatalog.FIRST_WAVE_RAY_INTERVAL_MILLIS) / 1_000f
        return GridRules.rayTriggerDelaySeconds(
            interval,
            SaveManager.bonuses.value.safeGridWindowBonus,
            warningDurationSecondsLocked(),
        )
    }

    private fun warningDurationSecondsLocked(): Float =
        GridRules.warningDurationSeconds(SaveManager.bonuses.value.warningDurationBonus)

    private fun checkPlayerSafetyLocked() {
        if (deathHandled || phase != GridPhase.ACTIVE) return
        val layout = SceneLayoutManager.layout.value ?: return
        val x = playerX ?: return
        val y = playerY ?: return
        val z = playerZ ?: return
        val safeIndex = safeGridIndex ?: return
        val playerGrid = GridRules.cellIndexAt(layout.safeGridCells, x, z)
        val touchedBeamIndex =
            GridRules.touchedDangerBeamIndex(
                cells = layout.safeGridCells,
                safeIndex = safeIndex,
                helmetX = x,
                helmetZ = z,
                helmetRadius = GameplayTuning.HMD_HELMET_RADIUS_METERS,
            )
        if (!GridRules.isLethalBeamContact(touchedBeamIndex)) return
        deathHandled = true
        if (!TowerManager.markPlayerDead()) return
        Log.i(
            TAG,
            "HMD helmet touched lethal red beam=$touchedBeamIndex player=($x,$y,$z) radius=" +
                "${GameplayTuning.HMD_HELMET_RADIUS_METERS} playerGrid=$playerGrid safeGrid=$safeIndex",
        )
        EventBus.post(RayTriggeredEvent(playerGrid, safeIndex))
        EventBus.post(PlayerDiedEvent(playerGrid, safeIndex))
    }

    private fun runSafetyCheckIfDueLocked() {
        if (safetyCheckElapsed < PerformanceTuning.RAYCAST_INTERVAL_SECONDS) return
        safetyCheckElapsed %= PerformanceTuning.RAYCAST_INTERVAL_SECONDS
        checkPlayerSafetyLocked()
    }

    internal fun freezeCombat() = synchronized(stateLock) { resetToPausedLocked() }

    internal fun resetSession() = synchronized(stateLock) {
        if (visualRoot == null) rebuildVisualsLocked(SceneLayoutManager.layout.value)
        deathHandled = false
        visibleHazardCycleReported = false
        safeGridIndex = null
        pendingSafeGridIndex = null
        lastGameState = GameState.IDLE
        resetToPausedLocked()
    }

    internal fun releaseBattleResources() = synchronized(stateLock) {
        releaseVisualResourcesLocked()
        resetToPausedLocked()
    }

    private fun resetToPausedLocked() {
        phase = GridPhase.PAUSED
        visibleHazardCycleReported = false
        pendingSafeGridIndex = null
        warningElapsed = 0f
        beamElapsed = 0f
        cycleElapsed = 0f
        safetyCheckElapsed = 0f
        applyVisualsLocked()
        publishStateLocked()
    }

    private fun rebuildVisualsLocked(layout: SceneLayout?) {
        releaseVisualResourcesLocked()
        val parent = hostRoot ?: return
        if (layout == null) return

        val root = Entity().apply {
            setName("GridRuntimeRoot")
            enabled = GameStateManager.state.value != GameState.IDLE
            val gridCenter = layout.safeGridCells[GridRules.GRID_COUNT / 2]
            components[TransformComponent::class.java]?.setPosition(
                Vector3(gridCenter.center.x, gridCenter.center.y, gridCenter.center.z),
            )
            components[TransformComponent::class.java]?.setQuaternion(
                Matrix4.rotateYByDegrees(
                    Math.toDegrees(gridCenter.rotationYRadians.toDouble()).toFloat(),
                ).rotation,
            )
        }
        parent.addChild(root)
        visualRoot = root
        val floorMesh = MeshResource.createBox(Vector3(GRID_VISUAL_SIZE, FLOOR_THICKNESS, GRID_VISUAL_SIZE), 0f)
            .also { this.floorMesh = it }
        val beamMesh = MeshResource.createCylinder(
            GridRules.BEAM_HEIGHT_METERS,
            GridRules.BEAM_RADIUS_METERS,
        )
            .also { this.beamMesh = it }
        Log.i(
            TAG,
            "Created ${GridRules.GRID_COUNT} hazard columns diameter=" +
                "${GridRules.BEAM_DIAMETER_METERS}m height=${GridRules.BEAM_HEIGHT_METERS}m",
        )
        val beamMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(Color4(1f, 0.02f, 0.02f, 1f))
                setEmissiveColor(Color4(1f, 0.01f, 0.01f, 1f))
                setOpacity(0.72f)
            }.also { sharedBeamMaterial = it }
        layout.safeGridCells.forEachIndexed { index, _ ->
            val row = index / 3
            val column = index % 3
            val anchor = Entity().apply {
                setName("GridCell_$index")
                components[TransformComponent::class.java]?.setPosition(
                    Vector3(
                        (column - 1) * SceneLayoutCalculator.GRID_CELL_SIZE,
                        0f,
                        (row - 1) * SceneLayoutCalculator.GRID_CELL_SIZE,
                    ),
                )
            }
            val floorMaterial = PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT)
            val floor =
                ModelEntity(floorMesh, floorMaterial).apply {
                    setName("GridCell_${index}_Floor")
                    components[TransformComponent::class.java]?.setPosition(
                        Vector3(0f, FLOOR_THICKNESS / 2f, 0f),
                    )
                }
            val beam =
                ModelEntity(beamMesh, beamMaterial).apply {
                    setName("GridCell_${index}_EnergyRay")
                    enabled = false
                }
            anchor.addChild(floor)
            anchor.addChild(beam)
            root.addChild(anchor)
            cellVisuals += GridCellVisual(floorMaterial, beam)
        }
        applyVisualsLocked()
    }

    private fun applyVisualsLocked() {
        if (cellVisuals.size != GridRules.GRID_COUNT) return
        val flashOn = GridRules.warningFlashOn(warningElapsed)
        val beamProgress = if (phase == GridPhase.RAISING) GridRules.beamProgress(beamElapsed) else if (phase == GridPhase.ACTIVE) 1f else 0f
        cellVisuals.forEachIndexed { index, visual ->
            val designatedSafe =
                when (phase) {
                    GridPhase.RAISING, GridPhase.ACTIVE -> index == safeGridIndex
                    GridPhase.WARNING -> index == pendingSafeGridIndex
                    GridPhase.COOLDOWN -> index == safeGridIndex
                    GridPhase.PAUSED -> false
                }
            val cellState =
                when {
                    phase == GridPhase.PAUSED || designatedSafe ->
                        GridCellState.SAFE
                    phase == GridPhase.WARNING -> GridCellState.WARNING
                    else -> GridCellState.DANGER
                }
            val styleToken =
                phase.ordinal * 100 +
                    cellState.ordinal * 10 +
                    (if (flashOn) 1 else 0) +
                    (if (designatedSafe) 2 else 0)
            if (styleToken != visual.styleToken) {
                updateFloorMaterial(visual.floorMaterial, cellState, designatedSafe, flashOn)
                visual.styleToken = styleToken
            }
            val visibleBeamProgress = if (cellState == GridCellState.DANGER) beamProgress else 0f
            visual.beam.enabled = visibleBeamProgress > 0f
            if (visibleBeamProgress > 0f) {
                visual.beam.components[TransformComponent::class.java]?.apply {
                    setPosition(
                        Vector3(0f, GridRules.BEAM_HEIGHT_METERS * visibleBeamProgress / 2f, 0f),
                    )
                    setScaleVector(Vector3(1f, visibleBeamProgress.coerceAtLeast(0.001f), 1f))
                }
            }
        }
    }

    private fun updateFloorMaterial(
        material: PhysicallyBasedMaterial,
        state: GridCellState,
        designatedSafe: Boolean,
        flashOn: Boolean,
    ) {
        when (state) {
            GridCellState.SAFE -> {
                val strength = if (designatedSafe && phase == GridPhase.WARNING && flashOn) 0.75f else 0.28f
                material.setBaseColor(Color4(0.12f, 0.92f, 0.30f, 1f))
                material.setEmissiveColor(Color4(0.03f, strength, 0.08f, 1f))
                material.setOpacity(if (phase == GridPhase.PAUSED) 0.22f else 0.42f)
            }

            GridCellState.WARNING -> {
                val strength = if (flashOn) 1f else 0.18f
                material.setBaseColor(Color4(1f, 0.03f, 0.03f, 1f))
                material.setEmissiveColor(Color4(strength, 0.01f, 0.01f, 1f))
                material.setOpacity(if (flashOn) 0.72f else 0.22f)
            }

            GridCellState.DANGER -> {
                material.setBaseColor(Color4(0.95f, 0.02f, 0.02f, 1f))
                material.setEmissiveColor(Color4(0.72f, 0.01f, 0.01f, 1f))
                material.setOpacity(0.55f)
            }
        }
    }

    private fun publishStateLocked() {
        val progress = if (phase == GridPhase.RAISING) GridRules.beamProgress(beamElapsed) else if (phase == GridPhase.ACTIVE) 1f else 0f
        mutableState.value =
            GridRuntimeState(
                cells =
                    List(GridRules.GRID_COUNT) { index ->
                        val designatedSafe =
                            when (phase) {
                                GridPhase.RAISING, GridPhase.ACTIVE -> index == safeGridIndex
                                GridPhase.WARNING -> index == pendingSafeGridIndex
                                GridPhase.COOLDOWN -> index == safeGridIndex
                                GridPhase.PAUSED -> false
                            }
                        GridCellRuntimeState(
                            index = index,
                            state =
                                when {
                                    phase == GridPhase.PAUSED || designatedSafe ->
                                        GridCellState.SAFE
                                    phase == GridPhase.WARNING -> GridCellState.WARNING
                                    else -> GridCellState.DANGER
                                },
                            isDesignatedSafe = designatedSafe,
                            beamProgress = if (designatedSafe) 0f else progress,
                        )
                    },
                safeGridIndex =
                    when (phase) {
                        GridPhase.PAUSED -> null
                        GridPhase.COOLDOWN -> safeGridIndex
                        GridPhase.WARNING -> pendingSafeGridIndex
                        GridPhase.RAISING, GridPhase.ACTIVE -> safeGridIndex
                    },
                pendingSafeGridIndex = pendingSafeGridIndex,
                phase = phase,
            )
    }

    private fun detachLocked() {
        layoutJob?.cancel()
        layoutJob = null
        releaseVisualResourcesLocked()
        hostRoot = null
        playerX = null
        playerY = null
        playerZ = null
        lastGameState = GameState.IDLE
        resetToPausedLocked()
    }

    private fun releaseVisualResourcesLocked() {
        visualRoot?.destroy()
        visualRoot = null
        cellVisuals.forEach { it.floorMaterial.close() }
        cellVisuals.clear()
        floorMesh?.close()
        beamMesh?.close()
        sharedBeamMaterial?.close()
        floorMesh = null
        beamMesh = null
        sharedBeamMaterial = null
    }

    override fun onDestroy() {
        synchronized(stateLock) { detachLocked() }
    }

    private data class GridCellVisual(
        val floorMaterial: PhysicallyBasedMaterial,
        val beam: ModelEntity,
        var styleToken: Int = -1,
    )
}
