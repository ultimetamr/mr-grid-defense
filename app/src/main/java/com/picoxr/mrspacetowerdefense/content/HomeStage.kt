package com.picoxr.mrspacetowerdefense.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.AttachmentPanelComponent
import com.pico.spatial.core.ecs.SortAsUIElementComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.picoxr.mrspacetowerdefense.manager.GridManager
import com.picoxr.mrspacetowerdefense.manager.GameManager
import com.picoxr.mrspacetowerdefense.manager.GameStateManager
import com.picoxr.mrspacetowerdefense.manager.FrameRateGovernor
import com.picoxr.mrspacetowerdefense.manager.GridSystem
import com.picoxr.mrspacetowerdefense.manager.MonsterCombatSystem
import com.picoxr.mrspacetowerdefense.manager.MonsterManager
import com.picoxr.mrspacetowerdefense.manager.MonsterModelLibrary
import com.picoxr.mrspacetowerdefense.manager.SceneLayoutManager
import com.picoxr.mrspacetowerdefense.manager.SpatialManager
import com.picoxr.mrspacetowerdefense.manager.TowerCombatSystem
import com.picoxr.mrspacetowerdefense.manager.TowerManager
import com.picoxr.mrspacetowerdefense.manager.UIManager
import com.picoxr.mrspacetowerdefense.manager.UIFollowSystem
import com.picoxr.mrspacetowerdefense.manager.WallManager
import com.picoxr.mrspacetowerdefense.manager.WallSystem
import com.picoxr.mrspacetowerdefense.ui.spatial.ActiveGamePanel
import com.picoxr.mrspacetowerdefense.ui.spatial.PersistentHud
import com.picoxr.mrspacetowerdefense.model.GameState

@Composable
fun HomeStage() {
    val context = LocalContext.current
    val sceneRoot =
        remember {
            Entity().apply {
                setName("MrTowerDefenseSceneRoot")
                components[TransformComponent::class.java]
                    ?: run { components[TransformComponent::class.java] = TransformComponent() }
            }
        }
    val towerPlacementInput = remember(sceneRoot) { TowerPlacementInput(sceneRoot) }
    val frameRateGovernor = remember { FrameRateGovernor() }

    DisposableEffect(sceneRoot) {
        TowerManager.attach(sceneRoot)
        WallManager.attach(sceneRoot)
        GridManager.attach(sceneRoot)
        registerSystem<TowerCombatSystem>()
        registerSystem<MonsterCombatSystem>()
        registerSystem<GridSystem>()
        registerSystem<WallSystem>()
        // SpatialView's Compose update callback is not a render-frame callback.
        // UI following must run in ECS so walking/turning continuously updates panels.
        registerSystem<UIFollowSystem>()
        towerPlacementInput.start()
        onDispose {
            towerPlacementInput.stop()
            UIManager.detach()
            unregisterSystem<UIFollowSystem>()
            unregisterSystem<WallSystem>()
            unregisterSystem<GridSystem>()
            unregisterSystem<MonsterCombatSystem>()
            unregisterSystem<TowerCombatSystem>()
            GridManager.detach()
            MonsterManager.detach()
            WallManager.detach()
            TowerManager.detach()
            SceneLayoutManager.detach()
            sceneRoot.destroy()
        }
    }

    SpatialView(
        modifier =
            Modifier.pointerInput(Unit) {
                detectSpatialTapGesture(context, targetedToEntity = TargetEntity.any()) { tap ->
                    val towerId = tap.targetEntity?.let(TowerManager::towerIdForEntity)
                    if (towerId != null) UIManager.openTowerUpgrade(towerId)
                }
            },
        initial = { content, attachments ->
            content.addEntity(sceneRoot)
            SpatialManager.updatePlayerFeetHeight(0f)
            SceneLayoutManager.attach(sceneRoot)

            val mainPanel = checkNotNull(attachments.entity(id = UIManager.MAIN_PANEL_ATTACHMENT_ID))
            val hudPanel = checkNotNull(attachments.entity(id = UIManager.HUD_ATTACHMENT_ID))
            // Both attachments start hidden. UIManager resolves their visibility as one
            // atomic modal/HUD decision before the first live frame can render.
            mainPanel.enabled = false
            hudPanel.enabled = false
            mainPanel.components.set(SortAsUIElementComponent(distanceBias = MODAL_UI_DISTANCE_BIAS_METERS))
            hudPanel.components.set(SortAsUIElementComponent(distanceBias = HUD_UI_DISTANCE_BIAS_METERS))
            content.addEntity(mainPanel)
            content.addEntity(hudPanel)
            UIManager.attach(sceneRoot, mainPanel, hudPanel)
            // Direct GLB loading belongs to this one-shot suspend initialization path.
            // The manager owns the loaded template library until Stage teardown.
            MonsterManager.attach(sceneRoot, MonsterModelLibrary.load())
            GameManager.refreshSceneVisibility()
        },
        update = { _, _ ->
            if (frameRateGovernor.shouldRun(
                    combatState = GameStateManager.state.value == GameState.FIGHTING,
                    applicationActive = GameManager.isApplicationUpdateEnabled(),
                )
            ) {
                towerPlacementInput.update()
            }
        },
        attachments = {
            AttachmentPanel(
                id = UIManager.MAIN_PANEL_ATTACHMENT_ID,
                alignment = AttachmentPanelComponent.Alignment.CENTER,
            ) {
                ActiveGamePanel()
            }
            AttachmentPanel(
                id = UIManager.HUD_ATTACHMENT_ID,
                alignment = AttachmentPanelComponent.Alignment.CENTER,
            ) {
                PersistentHud()
            }
        },
    )
}

private const val MODAL_UI_DISTANCE_BIAS_METERS = 1.25f
private const val HUD_UI_DISTANCE_BIAS_METERS = 0.18f
