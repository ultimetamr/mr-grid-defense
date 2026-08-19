package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.base.SemanticLabelType
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneOrientation
import com.pico.spatial.sense.plane.PlaneTrackingManager
import com.picoxr.mrspacetowerdefense.model.GroundBounds
import com.picoxr.mrspacetowerdefense.model.GroundSurface
import com.picoxr.mrspacetowerdefense.model.ObstacleBounds
import com.picoxr.mrspacetowerdefense.model.PlaneDetectionState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Application-scoped owner of PICO plane tracking and spatial query state. */
object SpatialManager : BaseManager() {
    private const val TAG = "SpatialManager"
    private const val MIN_GROUND_AREA_SQUARE_METERS = 4f
    private const val MIN_OBSTACLE_THICKNESS_METERS = 0.15f

    private val anchors = linkedMapOf<UUID, PlaneAnchor>()
    private val mutableState = MutableStateFlow<PlaneDetectionState>(PlaneDetectionState.Stopped)
    private val mutableGroundSurface = MutableStateFlow<GroundSurface?>(null)

    private var planeSubscription: Cancellable? = null
    private var sceneRoot: Entity? = null
    private var startRequested = false

    @Volatile private var playerFeetHeight = 0f
    @Volatile private var cachedGroundHeight = 0f
    @Volatile private var obstacleSnapshot: List<ObstacleBounds> = emptyList()

    val state: StateFlow<PlaneDetectionState> = mutableState.asStateFlow()
    val groundSurface: StateFlow<GroundSurface?> = mutableGroundSurface.asStateFlow()

    override fun onInitialize(applicationContext: Context) {
        cachedGroundHeight = playerFeetHeight
        mutableState.value = PlaneDetectionState.Ready
    }

    fun attachSceneRoot(root: Entity) {
        sceneRoot = root
        managerScope.launch { refreshSpatialSnapshot() }
    }

    fun detachSceneRoot(root: Entity) {
        if (sceneRoot === root) sceneRoot = null
    }

    fun updatePlayerFeetHeight(worldY: Float) {
        require(worldY.isFinite()) { "Player feet height must be finite" }
        playerFeetHeight = worldY
        if (mutableGroundSurface.value == null) cachedGroundHeight = worldY
    }

    /** Ground height always has a value; before detection it falls back to the player's feet. */
    fun getGroundHeight(): Float = cachedGroundHeight

    fun getActivityBounds(): GroundBounds? = mutableGroundSurface.value?.bounds

    /** Converts an HMD/world-space sample into the gameplay root coordinate system. */
    fun worldToScenePosition(worldPosition: Vector3): Vector3 =
        sceneRoot?.convertPositionFrom(worldPosition, null) ?: worldPosition

    fun isPositionInObstacle(x: Float, z: Float): Boolean {
        if (!x.isFinite() || !z.isFinite()) return true
        return obstacleSnapshot.any { it.contains(x, z) }
    }

    fun startPlaneDetection() {
        check(isInitialized) { "SpatialManager must be initialized with an Application context" }
        if (startRequested) return
        startRequested = true

        managerScope.launch {
            runCatching {
                mutableState.value = PlaneDetectionState.Starting
                ensurePlaneSubscription()
                PlaneTrackingManager.start()
                PlaneTrackingManager.loadAllAnchors().forEach { anchors[it.anchorUUID] = it }
                refreshSpatialSnapshot()
                mutableState.value = PlaneDetectionState.Running(anchors.size)
            }.onFailure { throwable ->
                startRequested = false
                Log.e(TAG, "Unable to start PICO plane detection", throwable)
                mutableState.value =
                    PlaneDetectionState.Failed(
                        throwable.message ?: "PICO plane detection failed",
                    )
            }
        }
    }

    fun stopPlaneDetection() {
        if (!isInitialized) return
        managerScope.launch {
            runCatching { PlaneTrackingManager.stop() }
                .onFailure { Log.w(TAG, "Unable to stop PICO plane detection cleanly", it) }
            startRequested = false
            mutableState.value = PlaneDetectionState.Stopped
        }
    }

    fun onPermissionDenied(deniedPermissions: Set<String>) {
        managerScope.launch {
            mutableState.value = PlaneDetectionState.PermissionDenied(deniedPermissions)
        }
    }

    fun releaseSpatialResources() {
        if (!isInitialized) return
        managerScope.launch {
            runCatching { PlaneTrackingManager.stop() }
            clearRuntimeState()
        }
    }

    private fun ensurePlaneSubscription() {
        if (planeSubscription != null) return
        planeSubscription =
            PlaneTrackingManager.subscribeAnchorUpdate { update ->
                // SDK callbacks are marshalled to Main by BaseManager before touching ECS/UI state.
                managerScope.launch {
                    when (update.event) {
                        AnchorUpdate.Event.ADDED,
                        AnchorUpdate.Event.LOADED,
                        AnchorUpdate.Event.UPDATED,
                        -> anchors[update.anchor.anchorUUID] = update.anchor
                        AnchorUpdate.Event.REMOVED -> anchors.remove(update.anchor.anchorUUID)
                        AnchorUpdate.Event.UNKNOWN -> Unit
                    }
                    refreshSpatialSnapshot()
                    mutableState.value = PlaneDetectionState.Running(anchors.size)
                }
            }
    }

    private fun refreshSpatialSnapshot() {
        val candidates = anchors.values.map(::toResolvedPlane)
        val ground =
            candidates
                .asSequence()
                .filter { it.orientation == PlaneOrientation.HORIZONTAL_UPWARD }
                .filter {
                    it.semantic == SemanticLabelType.FLOOR ||
                        it.semantic == SemanticLabelType.UNKNOWN
                }
                .filter { it.area >= MIN_GROUND_AREA_SQUARE_METERS }
                .maxByOrNull { it.area }

        val surface =
            ground?.let {
                GroundSurface(
                    anchorId = it.anchorId,
                    height = it.center.y,
                    bounds = it.bounds,
                    area = it.area,
                )
            }
        mutableGroundSurface.value = surface
        cachedGroundHeight = surface?.height ?: playerFeetHeight
        obstacleSnapshot =
            candidates
                .asSequence()
                .filterNot { it.anchorId == surface?.anchorId }
                .filterNot { it.semantic == SemanticLabelType.CEILING }
                .filterNot {
                    it.semantic == SemanticLabelType.FLOOR &&
                        it.orientation == PlaneOrientation.HORIZONTAL_UPWARD
                }
                .map { ObstacleBounds(it.anchorId, it.obstacleBounds) }
                .toList()
    }

    private fun toResolvedPlane(anchor: PlaneAnchor): ResolvedPlane {
        val center = sceneRoot?.convertPositionFrom(anchor.transform.position, null)
            ?: anchor.transform.position
        val width = anchor.boundingBoxSize.x.coerceAtLeast(MIN_OBSTACLE_THICKNESS_METERS)
        val secondDimension =
            anchor.boundingBoxSize.y.coerceAtLeast(MIN_OBSTACLE_THICKNESS_METERS)
        val horizontal = anchor.planeOrientation == PlaneOrientation.HORIZONTAL_UPWARD ||
            anchor.planeOrientation == PlaneOrientation.HORIZONTAL_DOWNWARD
        val depth = if (horizontal) secondDimension else MIN_OBSTACLE_THICKNESS_METERS
        val verticalExtent = if (horizontal) width else maxOf(width, secondDimension)
        val obstacleHalfX = if (horizontal) width / 2f else verticalExtent / 2f
        val obstacleHalfZ = if (horizontal) depth / 2f else verticalExtent / 2f
        return ResolvedPlane(
            anchorId = anchor.anchorUUID,
            center = center,
            orientation = anchor.planeOrientation,
            semantic = anchor.semantics,
            area = anchor.boundingBoxSize.x * anchor.boundingBoxSize.y,
            bounds =
                GroundBounds(
                    minX = center.x - width / 2f,
                    maxX = center.x + width / 2f,
                    minZ = center.z - secondDimension / 2f,
                    maxZ = center.z + secondDimension / 2f,
                ),
            obstacleBounds =
                GroundBounds(
                    minX = center.x - obstacleHalfX,
                    maxX = center.x + obstacleHalfX,
                    minZ = center.z - obstacleHalfZ,
                    maxZ = center.z + obstacleHalfZ,
                ),
        )
    }

    private fun clearRuntimeState() {
        planeSubscription?.cancel()
        planeSubscription = null
        anchors.clear()
        sceneRoot = null
        obstacleSnapshot = emptyList()
        mutableGroundSurface.value = null
        cachedGroundHeight = playerFeetHeight
        startRequested = false
        mutableState.value = PlaneDetectionState.Stopped
    }

    override fun onDestroy() {
        runCatching { PlaneTrackingManager.stop() }
        clearRuntimeState()
    }

    private data class ResolvedPlane(
        val anchorId: UUID,
        val center: Vector3,
        val orientation: PlaneOrientation,
        val semantic: SemanticLabelType,
        val area: Float,
        val bounds: GroundBounds,
        val obstacleBounds: GroundBounds,
    )
}
