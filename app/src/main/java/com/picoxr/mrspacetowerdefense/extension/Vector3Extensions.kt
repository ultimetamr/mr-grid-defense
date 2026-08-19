package com.picoxr.mrspacetowerdefense.extension

import com.pico.spatial.core.math.Vector3
import com.picoxr.mrspacetowerdefense.manager.GameplayTuning
import com.picoxr.mrspacetowerdefense.manager.SceneLayoutManager

/** Returns a copy whose bottom sits on the calibrated battlefield without depth clipping. */
fun Vector3.alignToGround(): Vector3 =
    Vector3(
        x,
        GameplayTuning.groundedBaseY(SceneLayoutManager.getCommittedGroundHeight()),
        z,
    )
