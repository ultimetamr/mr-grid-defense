package com.picoxr.mrspacetowerdefense.model

/** A trigger is emitted only after its documented business condition is satisfied. */
enum class GameStateTrigger {
    /** The player starts a new session from the idle screen. */
    START_CALIBRATION,

    /** A valid surface and a stable 3x3 board pose have been acquired. */
    CALIBRATION_COMPLETED,

    /** Calibration was cancelled or no valid surface could be confirmed. */
    CALIBRATION_CANCELLED,

    /** Initial towers, wall state, player state, and the first wave are ready. */
    START_FIGHT,

    /** The player leaves before combat starts. */
    CANCEL_PREPARATION,

    /** Every monster in the current non-final wave has been resolved. */
    WAVE_COMPLETED,

    /** The next wave configuration is loaded and its countdown has ended. */
    START_NEXT_WAVE,

    /** The wall/player failed, or the final configured wave was cleared. */
    GAME_FINISHED,

    /** The settlement result has been presented and persisted. */
    SETTLEMENT_COMPLETED,

    /** The player restarts with fresh run-local state while keeping permanent progress. */
    RESTART_GAME,
}
