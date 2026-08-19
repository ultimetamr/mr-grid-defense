package com.picoxr.mrspacetowerdefense.manager

/** Cleanup requested from a re-entrant game event while the monster map is being iterated. */
internal enum class MonsterCleanupAction {
    NONE,
    RECYCLE_WAVE,
    RELEASE_BATTLE_RESOURCES,
}

/**
 * Prevents settlement/wave events dispatched on Main.immediate from mutating the
 * active-monster LinkedHashMap inside its ECS-frame iterator.
 */
internal class MonsterCleanupGate {
    private var frameActive = false
    private var pendingAction = MonsterCleanupAction.NONE

    fun beginFrame() {
        check(!frameActive) { "Monster frame cleanup gate is already active" }
        frameActive = true
    }

    /** Returns true when the caller must defer the requested cleanup. */
    fun deferIfFrameActive(action: MonsterCleanupAction): Boolean {
        if (!frameActive) return false
        if (action.ordinal > pendingAction.ordinal) pendingAction = action
        return true
    }

    fun endFrame(): MonsterCleanupAction {
        check(frameActive) { "Monster frame cleanup gate is not active" }
        frameActive = false
        return pendingAction.also { pendingAction = MonsterCleanupAction.NONE }
    }

    fun reset() {
        frameActive = false
        pendingAction = MonsterCleanupAction.NONE
    }
}
