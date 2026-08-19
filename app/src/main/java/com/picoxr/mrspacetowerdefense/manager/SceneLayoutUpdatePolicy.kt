package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.GameState

/**
 * Keeps a calibrated battlefield stable while plane tracking continues refining its
 * spatial-query snapshot. Rebuilding native ECS trees for every anchor refinement can
 * monopolize the Android main thread and trigger an input ANR.
 */
internal object SceneLayoutUpdatePolicy {
    /**
     * The HMD pose may define the battlefield exactly once, during explicit calibration.
     * After that commit, walking or turning must never rewrite the world-space board pose.
     */
    fun shouldApplyHeadDrivenPose(
        gameState: GameState,
        calibrationRequested: Boolean,
        hasCommittedLayout: Boolean,
    ): Boolean =
        gameState == GameState.CALIBRATING &&
            calibrationRequested &&
            !hasCommittedLayout

    fun shouldGenerate(
        hasGroundSurface: Boolean,
        hasCommittedLayout: Boolean,
        forceRegeneration: Boolean = false,
    ): Boolean =
        hasGroundSurface && (!hasCommittedLayout || forceRegeneration)

    fun shouldKeepCommittedLayout(
        hasGroundSurface: Boolean,
        hasCommittedLayout: Boolean,
    ): Boolean = hasCommittedLayout && !hasGroundSurface
}
