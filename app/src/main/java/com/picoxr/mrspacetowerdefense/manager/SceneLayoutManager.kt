package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Matrix4
import com.pico.spatial.core.math.Vector3
import com.picoxr.mrspacetowerdefense.extension.alignToGround
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.GroundSurface
import com.picoxr.mrspacetowerdefense.model.SceneLayout
import com.picoxr.mrspacetowerdefense.model.ScenePoint
import com.picoxr.mrspacetowerdefense.model.SceneRect
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Builds the calibrated board as runtime ECS primitives beneath one disposable root. */
object SceneLayoutManager : BaseManager() {
    private const val TAG = "SceneLayoutManager"
    private const val DEBUG_MARKER_THICKNESS = 0.008f

    private var hostRoot: Entity? = null
    private var generatedRoot: Entity? = null
    private var observationJob: Job? = null
    private var groundingDebugEnabled = false
    @Volatile private var calibrationRequested = false
    private val generatedMeshes = ArrayList<MeshResource>()
    private val generatedMaterials = ArrayList<UnlitMaterial>()
    private val mutableLayout = MutableStateFlow<SceneLayout?>(null)

    val layout: StateFlow<SceneLayout?> = mutableLayout.asStateFlow()

    override fun onInitialize(applicationContext: Context) = Unit

    fun attach(root: Entity) {
        if (hostRoot === root && observationJob?.isActive == true) return
        detach()
        hostRoot = root
        SpatialManager.attachSceneRoot(root)
        observationJob =
            managerScope.launch {
                SpatialManager.groundSurface.collectLatest { surface ->
                    val hasCommittedLayout = mutableLayout.value != null
                    if (SceneLayoutUpdatePolicy.shouldKeepCommittedLayout(
                            hasGroundSurface = surface != null,
                            hasCommittedLayout = hasCommittedLayout,
                        )
                    ) {
                        // A temporary tracking gap must not destroy an accepted battlefield.
                        Log.w(TAG, "Primary floor unavailable; keeping calibrated layout")
                    }
                }
            }
    }

    fun detach() {
        observationJob?.cancel()
        observationJob = null
        calibrationRequested = false
        clearGeneratedLayout()
        hostRoot?.let(SpatialManager::detachSceneRoot)
        hostRoot = null
    }

    fun setGroundingDebugEnabled(enabled: Boolean) {
        groundingDebugEnabled = enabled
    }

    fun isGroundingDebugEnabled(): Boolean = groundingDebugEnabled

    /**
     * Gameplay must stay on the floor accepted during calibration. Plane updates may
     * continue afterwards, but they must not move only a subset of the fixed board.
     */
    fun getCommittedGroundHeight(): Float =
        mutableLayout.value?.wall?.center?.y ?: SpatialManager.getGroundHeight()

    internal fun setSceneVisible(visible: Boolean) {
        generatedRoot?.enabled = visible
    }

    /** Starts scanning for a fresh calibration without committing a premature HMD pose. */
    fun beginCalibration() {
        calibrationRequested = false
        clearGeneratedLayout()
        Log.i(TAG, "Fresh floor scan started; waiting for explicit player confirmation")
    }

    /** Commits on the next fresh ECS-frame HMD pose, i.e. the confirmation-time heading. */
    fun requestCalibrationCommit(): Boolean {
        if (GameStateManager.state.value != GameState.CALIBRATING) return false
        if (SpatialManager.groundSurface.value == null) return false
        calibrationRequested = true
        Log.i(TAG, "Calibration confirmed; waiting for next fresh HMD pose")
        return true
    }

    /**
     * Commits layout only when both a tracked floor and a fresh HMD pose are available.
     * The HMD forward vector is flattened so every entity remains exactly on the floor.
     */
    fun tryCommitCalibration(headWorldPosition: Vector3, headWorldForward: Vector3) {
        if (
            !SceneLayoutUpdatePolicy.shouldApplyHeadDrivenPose(
                gameState = GameStateManager.state.value,
                calibrationRequested = calibrationRequested,
                hasCommittedLayout = mutableLayout.value != null,
            )
        ) return
        val surface = SpatialManager.groundSurface.value ?: return
        val headScene = SpatialManager.worldToScenePosition(headWorldPosition)
        val forwardPointScene =
            SpatialManager.worldToScenePosition(
                Vector3(
                    headWorldPosition.x + headWorldForward.x,
                    headWorldPosition.y + headWorldForward.y,
                    headWorldPosition.z + headWorldForward.z,
                ),
            )
        val forwardX = forwardPointScene.x - headScene.x
        val forwardZ = forwardPointScene.z - headScene.z
        calibrationRequested = false
        runCatching {
            generate(
                surface = surface,
                playerX = headScene.x,
                playerZ = headScene.z,
                playerForwardX = forwardX,
                playerForwardZ = forwardZ,
            )
        }.onFailure {
            calibrationRequested = true
            Log.e(TAG, "Unable to generate calibrated scene", it)
        }
    }

    private fun generate(
        surface: GroundSurface,
        playerX: Float,
        playerZ: Float,
        playerForwardX: Float,
        playerForwardZ: Float,
    ) {
        val parent = hostRoot ?: run {
            calibrationRequested = true
            return
        }
        clearGeneratedLayout()
        val layout =
            SceneLayoutCalculator.calculate(
                groundHeight = surface.height,
                activityBounds = surface.bounds,
                playerX = playerX,
                playerZ = playerZ,
                playerForwardX = playerForwardX,
                playerForwardZ = playerForwardZ,
            )
        val root = Entity().apply {
            setName("GeneratedDefenseLayout")
            enabled = GameStateManager.state.value != com.picoxr.mrspacetowerdefense.model.GameState.IDLE
            components[TransformComponent::class.java]
                ?: run { components[TransformComponent::class.java] = TransformComponent() }
        }
        parent.addChild(root)
        generatedRoot = root

        try {
            createMonsterSpawnBoundary(root, layout.monsterSpawnBoundary)
        } catch (throwable: Throwable) {
            clearGeneratedLayout()
            throw throwable
        }
        mutableLayout.value = layout

        Log.i(
            TAG,
            "Committed calibrated layout anchor=${surface.anchorId} " +
                "groundY=${surface.height} bounds=${surface.bounds} " +
                "wall=${layout.wall.center} wallYaw=${layout.wall.rotationYRadians} " +
                "gridCenter=${layout.safeGridCells[4].center} " +
                "gridWallClearance=${SceneLayoutCalculator.GRID_TO_WALL_CLEARANCE}",
        )

        GameManager.onCalibrationCompleted()
    }

    private fun createMonsterSpawnBoundary(parent: Entity, boundary: SceneRect) {
        // The boundary root intentionally has no ModelComponent in normal builds.
        addGroundedAnchor(
            parent = parent,
            name = "MonsterSpawnBoundary",
            point = boundary.center,
            markerWidth = boundary.width,
            markerDepth = 0.04f,
            rotationYRadians = boundary.rotationYRadians,
        )
    }

    private fun addGroundedAnchor(
        parent: Entity,
        name: String,
        point: ScenePoint,
        markerWidth: Float,
        markerDepth: Float,
        rotationYRadians: Float,
    ): Entity {
        val anchor = Entity().apply {
            setName(name)
            components[TransformComponent::class.java]
                ?: run { components[TransformComponent::class.java] = TransformComponent() }
            components[TransformComponent::class.java]?.setPosition(
                Vector3(point.x, point.y, point.z).alignToGround(),
            )
            components[TransformComponent::class.java]?.setQuaternion(
                Matrix4.rotateYByDegrees(Math.toDegrees(rotationYRadians.toDouble()).toFloat()).rotation,
            )
        }
        parent.addChild(anchor)
        if (groundingDebugEnabled) addDebugGroundMarker(anchor, markerWidth, markerDepth)
        return anchor
    }

    private fun addDebugGroundMarker(parent: Entity, width: Float, depth: Float) {
        val mesh =
            MeshResource.createBox(
                Vector3(
                    width.coerceAtLeast(0.08f),
                    DEBUG_MARKER_THICKNESS,
                    depth.coerceAtLeast(0.04f),
                ),
                0f,
            ).also(generatedMeshes::add)
        val material =
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 0f, 0f, 1f))
            }.also(generatedMaterials::add)
        val marker =
            ModelEntity(
                mesh,
                material,
            ).apply {
                setName("GroundingDebugMarker")
                components[TransformComponent::class.java]?.setPosition(
                    Vector3(0f, DEBUG_MARKER_THICKNESS / 2f, 0f),
                )
            }
        parent.addChild(marker)
    }

    private fun clearGeneratedLayout() {
        generatedRoot?.destroy()
        generatedRoot = null
        generatedMeshes.forEach(MeshResource::close)
        generatedMaterials.forEach(UnlitMaterial::close)
        generatedMeshes.clear()
        generatedMaterials.clear()
        mutableLayout.value = null
    }

    override fun onDestroy() {
        calibrationRequested = false
        detach()
    }
}
