package com.picoxr.mrspacetowerdefense.content

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider.DataListener
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerPose
import com.pico.spatial.tracking.controller.ControllerTrackingData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.picoxr.mrspacetowerdefense.manager.SpatialManager
import com.picoxr.mrspacetowerdefense.manager.TowerManager
import com.picoxr.mrspacetowerdefense.manager.UIManager
import com.picoxr.mrspacetowerdefense.model.TowerType
import com.picoxr.mrspacetowerdefense.model.TowerPlacementResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Right-controller placement ray. Trigger confirms; A cycles tower types. */
class TowerPlacementInput(
    private val sceneRoot: Entity,
) {
    private val provider = ControllerTrackingProvider()
    private val latestRightPose = AtomicReference<ControllerPose?>(null)
    private val confirmRequested = AtomicBoolean(false)
    private val cycleRequested = AtomicBoolean(false)
    private val triggerWasPressed = AtomicBoolean(false)
    private val aWasPressed = AtomicBoolean(false)
    private var selectedIndex = 0

    private val trackingListener =
        object : DataListener<ControllerTrackingData> {
            override fun onProvideData(data: ControllerTrackingData) {
                latestRightPose.set(data.right)
            }
        }

    private val actionListener =
        ControllerTrackingProvider.ControllerActionListener { data -> onAction(data) }

    fun start() {
        TowerManager.selectTowerType(TowerType.entries[selectedIndex])
        provider.start()
        provider.addListener(trackingListener)
        provider.addControllerActionListener(actionListener)
    }

    fun stop() {
        provider.removeControllerActionListener(actionListener)
        provider.removeListener(trackingListener)
        provider.stop()
        latestRightPose.set(null)
        TowerManager.updatePlacementRay(null)
    }

    /** Must be called from SpatialView.update so coordinate conversion stays on the ECS/UI thread. */
    fun update() {
        if (cycleRequested.getAndSet(false)) {
            selectedIndex = (selectedIndex + 1) % TowerType.entries.size
            TowerManager.selectTowerType(TowerType.entries[selectedIndex])
        }
        val pose = latestRightPose.get()
        var rayOrigin: Vector3? = null
        var rayDirection: Vector3? = null
        if (pose == null || UIManager.isWorldInputBlocked()) {
            TowerManager.updatePlacementRay(null)
        } else {
            val origin = sceneRoot.convertPositionFrom(pose.position, null)
            val rotation = sceneRoot.convertRotationFrom(pose.rotation, null)
            val direction = rotation.rotateVector(Vector3.BACK)
            rayOrigin = origin
            rayDirection = direction
            TowerManager.updatePlacementRay(intersectGround(origin, direction))
        }
        if (confirmRequested.getAndSet(false) && !UIManager.isWorldInputBlocked()) {
            val towerId =
                if (rayOrigin != null && rayDirection != null) {
                    TowerManager.findTowerAlongRay(rayOrigin, rayDirection)
                } else {
                    null
                }
            if (towerId != null) {
                UIManager.openTowerUpgrade(towerId)
            } else if (TowerManager.confirmPlacement() is TowerPlacementResult.Success) {
                UIManager.onTowerPlaced()
            }
        }
    }

    private fun onAction(data: ControllerActionData) {
        val triggerPressed = data.right.triggerPressed
        if (triggerPressed && !triggerWasPressed.getAndSet(true)) confirmRequested.set(true)
        if (!triggerPressed) triggerWasPressed.set(false)

        val aPressed = data.right.aButtonPressed
        if (aPressed && !aWasPressed.getAndSet(true)) cycleRequested.set(true)
        if (!aPressed) aWasPressed.set(false)
    }

    private fun intersectGround(origin: Vector3, direction: Vector3): Vector3? {
        if (kotlin.math.abs(direction.y) < 0.0001f) return null
        val distance = (SpatialManager.getGroundHeight() - origin.y) / direction.y
        if (distance <= 0f || distance > MAX_RAY_DISTANCE) return null
        return Vector3(
            origin.x + direction.x * distance,
            SpatialManager.getGroundHeight(),
            origin.z + direction.z * distance,
        )
    }

    companion object {
        private const val MAX_RAY_DISTANCE = 10f
    }
}
