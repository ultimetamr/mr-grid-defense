package com.picoxr.mrspacetowerdefense.manager

import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System

class MonsterCombatSystem : System() {
    override fun update(context: SceneUpdateContext) {
        if (!GameManager.isApplicationUpdateEnabled()) return
        MonsterManager.onFrame(context.deltaTime)
    }
}
