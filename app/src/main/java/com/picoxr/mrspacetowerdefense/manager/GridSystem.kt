package com.picoxr.mrspacetowerdefense.manager

import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.math.Vector3

class GridSystem : System() {
    override fun update(context: SceneUpdateContext) {
        if (!GameManager.isApplicationUpdateEnabled()) return
        val pose = UIManager.latestFreshHeadWorldPose()
        GameManager.onTrackingChanged(pose != null)
        if (pose == null) {
            GridManager.clearPlayerWorldPosition()
        } else {
            val headForward = pose.rotation.rotateVector(Vector3.BACK)
            SceneLayoutManager.tryCommitCalibration(
                headWorldPosition = pose.position,
                headWorldForward = headForward,
            )
            // HMD collision must be sampled on every ECS frame. SpatialView's Compose
            // update callback does not run continuously when its UI state is unchanged.
            val scenePosition = SpatialManager.worldToScenePosition(pose.position)
            GridManager.updatePlayerWorldPosition(scenePosition.x, scenePosition.y, scenePosition.z)
            GameManager.updatePlayerPosition(scenePosition.x, scenePosition.z)
        }
        GridManager.onFrame(context.deltaTime)
    }
}
