package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.GamePanel
import com.picoxr.mrspacetowerdefense.model.GameState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

object PanelPlacementRules {
    data class PanelVisibility(
        val showModal: Boolean,
        val showHud: Boolean,
    )

    const val TARGET_DISTANCE_METERS = 1f
    const val TOWER_UPGRADE_DISTANCE_METERS = 1.2f
    const val CALIBRATION_DISTANCE_METERS = 1.5f
    const val HUD_DISTANCE_METERS = 1.2f
    const val HUD_DOWN_ANGLE_DEGREES = 15f
    const val HUD_MAX_PITCH_DEGREES = 70f
    const val HUD_FOLLOW_TIME_CONSTANT_SECONDS = 0.1f
    val HUD_DOWN_ANGLE_RADIANS = HUD_DOWN_ANGLE_DEGREES * (PI.toFloat() / 180f)
    val HUD_MAX_PITCH_RADIANS = HUD_MAX_PITCH_DEGREES * (PI.toFloat() / 180f)
    val HUD_HORIZONTAL_DISTANCE_METERS = HUD_DISTANCE_METERS * cos(HUD_DOWN_ANGLE_RADIANS)
    val HUD_VERTICAL_OFFSET_METERS = -HUD_DISTANCE_METERS * sin(HUD_DOWN_ANGLE_RADIANS)
    const val MODAL_RECENTER_DURATION_SECONDS = 0.4f
    const val MODAL_RECENTER_ANGLE_DEGREES = 45f
    const val MODAL_DISTANCE_DRIFT_METERS = 0.8f
    const val MODAL_MIN_COMFORT_DISTANCE_METERS = 0.75f
    const val MODAL_MAX_OBSTACLE_RETREAT_METERS = 0.2f
    const val OBSTACLE_RETREAT_STEP_METERS = 0.1f
    const val MIN_HEIGHT_ABOVE_GROUND_METERS = 0.5f
    const val FALLBACK_EYE_HEIGHT_METERS = 1.6f

    fun targetDistance(panel: GamePanel): Float =
        when (panel) {
            GamePanel.CALIBRATION -> CALIBRATION_DISTANCE_METERS
            GamePanel.TOWER_UPGRADE -> TOWER_UPGRADE_DISTANCE_METERS
            else -> TARGET_DISTANCE_METERS
        }

    fun panelHalfWidth(panel: GamePanel): Float =
        when (panel) {
            GamePanel.CALIBRATION -> 0.5f
            GamePanel.TOWER_UPGRADE -> 0.25f
            GamePanel.SETTLEMENT -> 0.6f
            GamePanel.SHOP,
            GamePanel.PERMANENT_GROWTH,
            GamePanel.MAIN_MENU,
            GamePanel.SAFETY_PAUSE,
            -> 0.75f
            GamePanel.NONE -> 0f
        }

    fun isWorldLockedModal(panel: GamePanel): Boolean =
        when (panel) {
            GamePanel.MAIN_MENU,
            GamePanel.TOWER_UPGRADE,
            GamePanel.SETTLEMENT,
            GamePanel.PERMANENT_GROWTH,
            GamePanel.CALIBRATION,
            GamePanel.SHOP,
            GamePanel.SAFETY_PAUSE,
            -> true
            GamePanel.NONE -> false
        }

    fun shouldShowHud(gameState: GameState, activePanel: GamePanel): Boolean =
        activePanel != GamePanel.MAIN_MENU &&
            gameState in setOf(GameState.FIGHTING, GameState.WAVE_PAUSE, GameState.SETTLE)

    fun visibilityFor(gameState: GameState, activePanel: GamePanel): PanelVisibility =
        PanelVisibility(
            showModal = isWorldLockedModal(activePanel),
            // Explicitly resolve both surfaces together so MAIN_MENU can never
            // share a rendered frame with the combat-value HUD.
            showHud = shouldShowHud(gameState, activePanel),
        )

    /** PREPARE is reachable only after calibration; it must never show a disabled calibration panel. */
    fun preparationPanel(shopSuppressedForPlacement: Boolean): GamePanel =
        if (shopSuppressedForPlacement) GamePanel.NONE else GamePanel.SHOP

    fun shouldRecenterModal(
        headX: Float,
        headZ: Float,
        viewForwardX: Float,
        viewForwardZ: Float,
        panelX: Float,
        panelZ: Float,
        lockedDistanceMeters: Float,
    ): Boolean {
        val toPanelX = panelX - headX
        val toPanelZ = panelZ - headZ
        val panelDistance = sqrt(toPanelX * toPanelX + toPanelZ * toPanelZ)
        val viewLength = sqrt(viewForwardX * viewForwardX + viewForwardZ * viewForwardZ)
        val outsideComfortAngle =
            panelDistance > 0.0001f &&
                viewLength > 0.0001f &&
                (toPanelX * viewForwardX + toPanelZ * viewForwardZ) /
                    (panelDistance * viewLength) <
                cos(MODAL_RECENTER_ANGLE_DEGREES * (PI.toFloat() / 180f))
        val distanceDrift = abs(panelDistance - lockedDistanceMeters) > MODAL_DISTANCE_DRIFT_METERS
        val comfortDistanceViolated = panelDistance < MODAL_MIN_COMFORT_DISTANCE_METERS
        return outsideComfortAngle || distanceDrift || comfortDistanceViolated
    }

    fun modalRecenterRadiusMeters(
        measuredRadiusMeters: Float,
        targetRadiusMeters: Float,
        easedProgress: Float,
    ): Float {
        require(targetRadiusMeters.isFinite() && targetRadiusMeters > 0f) {
            "Target modal radius must be finite and positive"
        }
        val safeStartRadius =
            measuredRadiusMeters
                .takeIf { it.isFinite() }
                ?.coerceAtLeast(MODAL_MIN_COMFORT_DISTANCE_METERS)
                ?: targetRadiusMeters.coerceAtLeast(MODAL_MIN_COMFORT_DISTANCE_METERS)
        val safeTargetRadius = targetRadiusMeters.coerceAtLeast(MODAL_MIN_COMFORT_DISTANCE_METERS)
        return safeStartRadius +
            (safeTargetRadius - safeStartRadius) * easedProgress.coerceIn(0f, 1f)
    }

    fun smoothingAlpha(deltaTimeSeconds: Float): Float {
        if (!deltaTimeSeconds.isFinite() || deltaTimeSeconds <= 0f) return 0f
        return (1f - exp(-deltaTimeSeconds / HUD_FOLLOW_TIME_CONSTANT_SECONDS)).coerceIn(0f, 1f)
    }

    fun smoothAngleRadians(current: Float, target: Float, alpha: Float): Float {
        val fullTurn = (2.0 * PI).toFloat()
        var delta = (target - current) % fullTurn
        if (delta > PI.toFloat()) delta -= fullTurn
        if (delta < -PI.toFloat()) delta += fullTurn
        return current + delta * alpha.coerceIn(0f, 1f)
    }

    fun hudHorizontalDistanceAtPitch(pitchRadians: Float): Float =
        HUD_DISTANCE_METERS * cos(pitchRadians)

    fun hudVerticalOffsetAtPitch(pitchRadians: Float): Float =
        HUD_DISTANCE_METERS * sin(pitchRadians)

    fun minimumHudPitchRadians(headY: Float, groundY: Float): Float {
        val minimumPanelY = groundY + MIN_HEIGHT_ABOVE_GROUND_METERS
        val minimumVerticalRatio = ((minimumPanelY - headY) / HUD_DISTANCE_METERS).coerceIn(-1f, 1f)
        return asin(minimumVerticalRatio)
            .coerceIn(-HUD_MAX_PITCH_RADIANS, HUD_MAX_PITCH_RADIANS)
    }

    fun recenterProgress(elapsedSeconds: Float): Float =
        (elapsedSeconds / MODAL_RECENTER_DURATION_SECONDS).coerceIn(0f, 1f)

    /** Matches the HUD's 0.1 s damping response, then resolves exactly at flight end. */
    fun modalFlightEasedProgress(elapsedSeconds: Float): Float =
        if (recenterProgress(elapsedSeconds) >= 1f) 1f else smoothingAlpha(elapsedSeconds)

    fun smoothStep(progress: Float): Float {
        val value = progress.coerceIn(0f, 1f)
        return value * value * (3f - 2f * value)
    }

    fun isHeadHeightValid(headY: Float, groundY: Float): Boolean =
        headY.isFinite() && groundY.isFinite() && headY >= groundY + MIN_HEIGHT_ABOVE_GROUND_METERS

    fun resolvePanelCenterY(headY: Float, groundY: Float): Float {
        val safeGroundY = groundY.takeIf { it.isFinite() } ?: 0f
        return if (isHeadHeightValid(headY, safeGroundY)) headY
        else safeGroundY + FALLBACK_EYE_HEIGHT_METERS
    }

    /** Keeps a modal on the HMD centreline; obstacle avoidance may only retreat 0.2 m. */
    inline fun resolveCenteredModalDistance(
        requestedDistanceMeters: Float,
        isBlocked: (Float) -> Boolean,
    ): Float {
        require(requestedDistanceMeters.isFinite() && requestedDistanceMeters > 0f) {
            "Requested panel distance must be finite and positive"
        }
        val minimumDistance =
            maxOf(
                MODAL_MIN_COMFORT_DISTANCE_METERS,
                requestedDistanceMeters - MODAL_MAX_OBSTACLE_RETREAT_METERS,
            )
        val retreatSteps =
            (MODAL_MAX_OBSTACLE_RETREAT_METERS / OBSTACLE_RETREAT_STEP_METERS).toInt()
        for (step in 0..retreatSteps) {
            val distance =
                (requestedDistanceMeters - step * OBSTACLE_RETREAT_STEP_METERS)
                    .coerceAtLeast(minimumDistance)
            if (!isBlocked(distance)) return distance
        }
        // The panel has highest UI sort priority. If the whole comfort band is
        // blocked, keeping the intended distance is safer than moving it sideways.
        return requestedDistanceMeters
    }
}
