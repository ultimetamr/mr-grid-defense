package com.picoxr.mrspacetowerdefense.manager

import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System

/** Runs HMD-relative panel transforms on every Spatial ECS frame. */
class UIFollowSystem : System() {
    override fun update(context: SceneUpdateContext) {
        UIManager.update(context.deltaTime)
    }
}
