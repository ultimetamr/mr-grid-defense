package com.picoxr.mrspacetowerdefense

import com.pico.spatial.core.math.Matrix4
import com.pico.spatial.core.math.Vector3
import com.picoxr.mrspacetowerdefense.manager.PanelPlacementRules
import com.picoxr.mrspacetowerdefense.model.GamePanel
import com.picoxr.mrspacetowerdefense.model.GameState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PanelPlacementRulesTest {
    @Test
    fun `prepare always shows shop and hides it only during placement`() {
        assertEquals(
            GamePanel.SHOP,
            PanelPlacementRules.preparationPanel(
                shopSuppressedForPlacement = false,
            ),
        )
        assertEquals(
            GamePanel.NONE,
            PanelPlacementRules.preparationPanel(
                shopSuppressedForPlacement = true,
            ),
        )
    }

    @Test
    fun `all panels use their specified placement distance`() {
        val oneMetrePanels =
            listOf(
                GamePanel.MAIN_MENU,
                GamePanel.SHOP,
                GamePanel.SAFETY_PAUSE,
                GamePanel.SETTLEMENT,
                GamePanel.PERMANENT_GROWTH,
            )
        oneMetrePanels.forEach { panel ->
            assertEquals(1f, PanelPlacementRules.targetDistance(panel), 0f)
        }
        assertEquals(1.2f, PanelPlacementRules.targetDistance(GamePanel.TOWER_UPGRADE), 0f)
        assertEquals(1.5f, PanelPlacementRules.targetDistance(GamePanel.CALIBRATION), 0f)
        assertEquals(1.2f, PanelPlacementRules.HUD_DISTANCE_METERS, 0f)
    }

    @Test
    fun `timing and angle constants match the two motion contracts`() {
        assertEquals(0.1f, PanelPlacementRules.HUD_FOLLOW_TIME_CONSTANT_SECONDS, 0f)
        assertEquals(15f, PanelPlacementRules.HUD_DOWN_ANGLE_DEGREES, 0f)
        assertEquals(0.4f, PanelPlacementRules.MODAL_RECENTER_DURATION_SECONDS, 0f)
        assertEquals(45f, PanelPlacementRules.MODAL_RECENTER_ANGLE_DEGREES, 0f)
        assertEquals(0.8f, PanelPlacementRules.MODAL_DISTANCE_DRIFT_METERS, 0f)
    }

    @Test
    fun `modal flight runs for four tenths then remains complete`() {
        assertEquals(0f, PanelPlacementRules.recenterProgress(0f), 0f)
        assertEquals(0.5f, PanelPlacementRules.recenterProgress(0.2f), 0.0001f)
        assertEquals(1f, PanelPlacementRules.recenterProgress(0.4f), 0f)
        assertEquals(1f, PanelPlacementRules.recenterProgress(1f), 0f)
        assertEquals(
            PanelPlacementRules.smoothingAlpha(0.1f),
            PanelPlacementRules.modalFlightEasedProgress(0.1f),
            0f,
        )
        assertEquals(1f, PanelPlacementRules.modalFlightEasedProgress(0.4f), 0f)
    }

    @Test
    fun `main menu and combat HUD are mutually exclusive`() {
        GameState.entries.forEach { state ->
            val visibility = PanelPlacementRules.visibilityFor(state, GamePanel.MAIN_MENU)
            assertTrue(visibility.showModal)
            assertFalse(visibility.showHud)
        }
        val fightingVisibility = PanelPlacementRules.visibilityFor(GameState.FIGHTING, GamePanel.NONE)
        assertFalse(fightingVisibility.showModal)
        assertTrue(fightingVisibility.showHud)
    }

    @Test
    fun `requested action panels share the world locked modal policy`() {
        listOf(
            GamePanel.MAIN_MENU,
            GamePanel.TOWER_UPGRADE,
            GamePanel.SETTLEMENT,
            GamePanel.PERMANENT_GROWTH,
        ).forEach { panel ->
            assertTrue(PanelPlacementRules.isWorldLockedModal(panel))
        }
        assertFalse(PanelPlacementRules.isWorldLockedModal(GamePanel.NONE))
    }

    @Test
    fun `HUD remains visible behind upgrade and settlement modals`() {
        assertTrue(PanelPlacementRules.shouldShowHud(GameState.WAVE_PAUSE, GamePanel.TOWER_UPGRADE))
        assertTrue(PanelPlacementRules.shouldShowHud(GameState.SETTLE, GamePanel.SETTLEMENT))
    }

    @Test
    fun `modal recenter triggers only beyond strict angle or distance thresholds`() {
        assertFalse(
            PanelPlacementRules.shouldRecenterModal(0f, 0f, 0f, -1f, 0f, -1f, 1f),
        )
        assertFalse(
            PanelPlacementRules.shouldRecenterModal(
                0f, 0f, 0f, -1f,
                sin(Math.toRadians(44.9)).toFloat(),
                -cos(Math.toRadians(44.9)).toFloat(),
                1f,
            ),
        )
        assertTrue(
            PanelPlacementRules.shouldRecenterModal(
                0f, 0f, 0f, -1f,
                sin(Math.toRadians(45.1)).toFloat(),
                -cos(Math.toRadians(45.1)).toFloat(),
                1f,
            ),
        )
        assertFalse(
            PanelPlacementRules.shouldRecenterModal(0f, 0f, 0f, -1f, 0f, -1.8f, 1f),
        )
        assertTrue(
            PanelPlacementRules.shouldRecenterModal(0f, 0f, 0f, 1f, 0f, -1f, 1f),
        )
        assertTrue(
            PanelPlacementRules.shouldRecenterModal(2f, 0f, 0f, -1f, 0f, -1f, 1f),
        )
    }

    @Test
    fun `modal safety override recenters before the panel can remain face close`() {
        assertTrue(
            PanelPlacementRules.shouldRecenterModal(0f, 0f, 0f, -1f, 0f, -0.74f, 1f),
        )
        assertFalse(
            PanelPlacementRules.shouldRecenterModal(0f, 0f, 0f, -1f, 0f, -0.75f, 1f),
        )
    }

    @Test
    fun `modal obstacle solver only retreats on the centered view ray`() {
        val resolved =
            PanelPlacementRules.resolveCenteredModalDistance(1f) { distance -> distance > 0.81f }
        assertEquals(0.8f, resolved, 0.0001f)
    }

    @Test
    fun `modal recenter radius never enters the face-close zone`() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
            val radius =
                PanelPlacementRules.modalRecenterRadiusMeters(
                    measuredRadiusMeters = 0.3f,
                    targetRadiusMeters = 1f,
                    easedProgress = progress,
                )
            assertTrue(radius >= PanelPlacementRules.MODAL_MIN_COMFORT_DISTANCE_METERS)
        }
        assertEquals(
            1f,
            PanelPlacementRules.modalRecenterRadiusMeters(0.3f, 1f, 1f),
            0f,
        )
    }

    @Test
    fun `HUD smoothing uses one frame-rate-independent coefficient`() {
        val oneTimeConstant = PanelPlacementRules.smoothingAlpha(0.1f)
        assertEquals(0.6321f, oneTimeConstant, 0.001f)
        assertEquals(oneTimeConstant, PanelPlacementRules.smoothingAlpha(0.1f), 0f)
    }

    @Test
    fun `HUD fixed offset is 1 point 2 metres at exactly 15 degrees down`() {
        val horizontal = PanelPlacementRules.HUD_HORIZONTAL_DISTANCE_METERS
        val vertical = PanelPlacementRules.HUD_VERTICAL_OFFSET_METERS
        val radialDistance = sqrt(horizontal * horizontal + vertical * vertical)
        val downAngleDegrees = Math.toDegrees(atan2(-vertical, horizontal).toDouble()).toFloat()

        assertEquals(1.2f, radialDistance, 0.0001f)
        assertEquals(15f, downAngleDegrees, 0.0001f)
        assertTrue(vertical < 0f)
    }

    @Test
    fun `HUD radial distance remains 1 point 2 metres through head pitch`() {
        listOf(-70f, -45f, -15f, 0f, 35f, 70f).forEach { degrees ->
            val pitch = degrees * (PI.toFloat() / 180f)
            val horizontal = PanelPlacementRules.hudHorizontalDistanceAtPitch(pitch)
            val vertical = PanelPlacementRules.hudVerticalOffsetAtPitch(pitch)
            assertEquals(1.2f, sqrt(horizontal * horizontal + vertical * vertical), 0.0001f)
        }
    }

    @Test
    fun `HUD yaw smoothing crosses the shortest angle without cutting radial distance`() {
        val current = 170f * (PI.toFloat() / 180f)
        val target = -170f * (PI.toFloat() / 180f)
        val halfway = PanelPlacementRules.smoothAngleRadians(current, target, 0.5f)

        assertEquals(180f, abs(Math.toDegrees(halfway.toDouble()).toFloat()), 0.001f)
    }

    @Test
    fun `HUD ground-safe pitch keeps its center half a metre above detected floor`() {
        val headY = 1.65f
        val groundY = 0.544f
        val pitch = PanelPlacementRules.minimumHudPitchRadians(headY, groundY)
        val panelY = headY + PanelPlacementRules.HUD_DISTANCE_METERS * sin(pitch)

        assertEquals(groundY + 0.5f, panelY, 0.0001f)
    }

    @Test
    fun `attachment visible plus Z face points back toward HMD`() {
        val head = Vector3(0f, 1.6f, 0f)
        val panel = Vector3(0f, 1.6f, -1f)
        val rotation = Matrix4.lookAt(head, panel, Vector3.UP).inverse().rotation
        val visibleNormal = rotation.rotateVector(Vector3(0f, 0f, 1f))

        assertTrue(visibleNormal.z > 0.999f)
    }

    @Test
    fun `valid HMD height is used as panel geometric center`() {
        assertEquals(1.72f, PanelPlacementRules.resolvePanelCenterY(1.72f, 0f), 0f)
        assertEquals(2.5f, PanelPlacementRules.resolvePanelCenterY(2.5f, 2f), 0f)
    }

    @Test
    fun `low or invalid HMD height falls back to ground plus adult eye height`() {
        assertEquals(1.6f, PanelPlacementRules.resolvePanelCenterY(0.49f, 0f), 0f)
        assertEquals(3.6f, PanelPlacementRules.resolvePanelCenterY(2.49f, 2f), 0f)
        assertEquals(1.6f, PanelPlacementRules.resolvePanelCenterY(Float.NaN, 0f), 0f)
    }

    @Test
    fun `centered obstacle solver preserves requested distance when open`() {
        assertEquals(1.5f, PanelPlacementRules.resolveCenteredModalDistance(1.5f) { false }, 0f)
    }

    @Test
    fun `centered obstacle solver limits retreat to twenty centimetres`() {
        val resolved =
            PanelPlacementRules.resolveCenteredModalDistance(1.5f) { distance -> distance > 1.31f }
        assertEquals(1.3f, resolved, 0.001f)
    }

    @Test
    fun `centered obstacle solver keeps requested distance if comfort band is blocked`() {
        val resolved = PanelPlacementRules.resolveCenteredModalDistance(1f) { true }
        assertEquals(1f, resolved, 0f)
    }
}
