package com.picoxr.mrspacetowerdefense.manager

import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System

class WallSystem : System() {
    override fun update(context: SceneUpdateContext) {
        if (!GameManager.isApplicationUpdateEnabled()) return
        WallManager.onFrame(context.deltaTime)
    }
}
