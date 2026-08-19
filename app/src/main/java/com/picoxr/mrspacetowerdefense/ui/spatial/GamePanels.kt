package com.picoxr.mrspacetowerdefense.ui.spatial

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.foundation.layout.offset
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.Material
import com.picoxr.mrspacetowerdefense.manager.GameStateManager
import com.picoxr.mrspacetowerdefense.manager.GameManager
import com.picoxr.mrspacetowerdefense.manager.PermanentGrowthCatalog
import com.picoxr.mrspacetowerdefense.manager.SaveManager
import com.picoxr.mrspacetowerdefense.manager.GoldManager
import com.picoxr.mrspacetowerdefense.manager.SpatialManager
import com.picoxr.mrspacetowerdefense.manager.TowerCatalog
import com.picoxr.mrspacetowerdefense.manager.TowerManager
import com.picoxr.mrspacetowerdefense.manager.UIManager
import com.picoxr.mrspacetowerdefense.manager.WallEconomyRules
import com.picoxr.mrspacetowerdefense.manager.WallManager
import com.picoxr.mrspacetowerdefense.manager.WaveManager
import com.picoxr.mrspacetowerdefense.model.GamePanel
import com.picoxr.mrspacetowerdefense.model.GameResult
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.CombatPauseReason
import com.picoxr.mrspacetowerdefense.model.PlaneDetectionState
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeCategory
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeConfig
import com.picoxr.mrspacetowerdefense.model.TowerConfig
import com.picoxr.mrspacetowerdefense.model.TowerRuntimeState
import kotlinx.coroutines.delay

private object PanelDepth {
    // zIndex preserves draw order; physicalStep adds a real 1 mm gap between
    // translucent spatial layers so the compositor never receives coplanar surfaces.
    const val background = 10f
    const val decoration = 20f
    const val button = 30f
    const val text = 40f
    const val backgroundStep = 0
    const val decorationStep = 1
    const val buttonStep = 2
    const val textStep = 3
}

@Composable
private fun Modifier.panelLayer(order: Float, physicalStep: Int): Modifier {
    val oneMillimeter =
        LocalPhysicalLengthConverter.current.lengthToDp(0.1f, LengthUnit.Centimeters)
    return offset(z = oneMillimeter * physicalStep).zIndex(order)
}

@Composable
fun PersistentHud(modifier: Modifier = Modifier) {
    val gameState by GameStateManager.state.collectAsStateWithLifecycle()
    val wave by WaveManager.runtimeState.collectAsStateWithLifecycle()
    val gold by GoldManager.gold.collectAsStateWithLifecycle()
    val wall by WallManager.wallState.collectAsStateWithLifecycle()
    val uiState by UIManager.state.collectAsStateWithLifecycle()
    val hpProgress = wall.currentHp.toFloat() / wall.maxHp.coerceAtLeast(1)

    PanelShell(
        modifier = Modifier.size(820.dp, 154.dp).then(modifier),
        cornerRadius = 28,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudMetric("波次", if (wave.currentWaveIndex == 0) "准备" else "${wave.currentWaveIndex}/10")
            HudMetric("金币", gold.toString())
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "城墙 ${wall.currentHp}/${wall.maxHp}",
                    modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
                    color = PicoTheme.colorScheme.labelPrimary,
                    style = PicoTheme.typography.labelLarge,
                )
                LinearProgressIndicator(
                    progress = { hpProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().panelLayer(PanelDepth.decoration, PanelDepth.decorationStep),
                )
            }
            Column(modifier = Modifier.width(270.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = gameState.toStatusText(),
                    modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
                    color = PicoTheme.colorScheme.interaction,
                    style = PicoTheme.typography.labelLarge,
                )
                Text(
                    text = uiState.statusMessage,
                    modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
fun ActiveGamePanel(modifier: Modifier = Modifier) {
    val uiState by UIManager.state.collectAsStateWithLifecycle()
    when (uiState.activePanel) {
        GamePanel.MAIN_MENU -> MainMenuPanel(modifier)
        GamePanel.CALIBRATION -> CalibrationPanel(modifier)
        GamePanel.SHOP -> ShopPanel(modifier)
        GamePanel.TOWER_UPGRADE -> TowerUpgradePanel(uiState.selectedTowerId, modifier)
        GamePanel.SAFETY_PAUSE -> SafetyPausePanel(modifier)
        GamePanel.SETTLEMENT -> SettlementPanel(uiState.settlement, modifier)
        GamePanel.PERMANENT_GROWTH -> PermanentGrowthPanel(modifier)
        GamePanel.NONE -> Unit
    }
}

@Composable
private fun MainMenuPanel(modifier: Modifier) {
    val progress by SaveManager.progress.collectAsStateWithLifecycle()
    PanelShell(modifier = Modifier.size(760.dp, 560.dp).then(modifier)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PanelTitle("MR空间塔防：九宫格坚守")
            Text("守住城墙，并在能量射线升起前进入唯一安全格。", color = PicoTheme.colorScheme.labelSecondary)
            InfoCard(modifier = Modifier.fillMaxWidth()) {
                ResultRow("永久晶核", progress.totalCrystals.toString())
                ResultRow("养成总等级", "${progress.totalUpgradeLevels}/120")
                ResultRow("历史最高波数", progress.highestWave.toString())
                ResultRow("历史最多击杀", progress.highestKills.toString())
            }
            Spacer(Modifier.weight(1f))
            SpatialActionButton(
                label = "开始游戏",
                onClick = UIManager::startGame,
                modifier = Modifier.fillMaxWidth().height(72.dp),
            )
        }
    }
}

@Composable
private fun SafetyPausePanel(modifier: Modifier) {
    val runtime by GameManager.state.collectAsStateWithLifecycle()
    val message =
        when {
            CombatPauseReason.OUT_OF_BOUNDS in runtime.pauseReasons -> "已走出九宫格活动区域，请返回3×3格子内"
            CombatPauseReason.TRACKING_LOST in runtime.pauseReasons -> "空间跟踪暂时丢失，恢复后将自动继续"
            else -> "应用曾进入后台，战斗保持暂停"
        }
    PanelShell(modifier = Modifier.size(680.dp, 420.dp).then(modifier)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(38.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PanelTitle("战斗安全暂停")
            Text(message, color = PicoTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            if (CombatPauseReason.APP_BACKGROUND in runtime.pauseReasons) {
                SpatialActionButton(
                    label = "确认继续战斗",
                    onClick = UIManager::resumeAfterInterruption,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("条件恢复后自动继续", color = PicoTheme.colorScheme.labelSecondary)
            }
        }
    }
}

@Composable
private fun ShopPanel(modifier: Modifier) {
    val gameState by GameStateManager.state.collectAsStateWithLifecycle()
    val wave by WaveManager.runtimeState.collectAsStateWithLifecycle()
    val gold by GoldManager.gold.collectAsStateWithLifecycle()
    val wall by WallManager.wallState.collectAsStateWithLifecycle()
    val towers by TowerManager.towers.collectAsStateWithLifecycle()
    val wallUpgradeCost = if (wall.level < WallEconomyRules.MAX_LEVEL) WallEconomyRules.upgradeCost(wall.level) else 0
    val reductionCost =
        if (wall.damageReductionLevel < WallEconomyRules.DAMAGE_REDUCTION_MAX_LEVEL) {
            WallEconomyRules.damageReductionUpgradeCost(wall.damageReductionLevel)
        } else 0
    val reflectionCost =
        if (wall.reflectionLevel < WallEconomyRules.REFLECTION_MAX_LEVEL) {
            WallEconomyRules.reflectionUpgradeCost(wall.reflectionLevel)
        } else 0
    val regenerationCost =
        if (wall.regenerationLevel < WallEconomyRules.REGENERATION_MAX_LEVEL) {
            WallEconomyRules.regenerationUpgradeCost(wall.regenerationLevel)
        } else 0
    val placementPhase = gameState == GameState.PREPARE || gameState == GameState.WAVE_PAUSE
    val wallUpgradePhase = gameState == GameState.WAVE_PAUSE
    val towerColumns = TowerCatalog.all().chunked(3)
    PanelShell(modifier = Modifier.size(1280.dp, 820.dp).then(modifier)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PanelTitle(if (gameState == GameState.PREPARE) "开局布防" else "战术商店")
            Row(
                modifier = Modifier.fillMaxWidth().panelLayer(PanelDepth.decoration, PanelDepth.decorationStep),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (gameState == GameState.PREPARE) "第一波准备阶段" else "当前波次 ${wave.currentWaveIndex}/10",
                    color = PicoTheme.colorScheme.labelPrimary,
                )
                Text("金币 $gold", color = PicoTheme.colorScheme.interaction)
                Text("城墙 ${wall.currentHp}/${wall.maxHp}", color = PicoTheme.colorScheme.labelPrimary)
                Text("武器槽 ${towers.size}/${TowerManager.MAX_WALL_WEAPONS}", color = PicoTheme.colorScheme.labelPrimary)
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                towerColumns.forEachIndexed { index, configs ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (index == 0) "输出塔" else "辅助塔",
                            modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
                            color = PicoTheme.colorScheme.labelPrimary,
                            style = PicoTheme.typography.titleSmall,
                        )
                        configs.forEach { config ->
                            TowerPurchaseRow(
                                config = config,
                                currentGold = gold,
                                hasFreeWallSlot = placementPhase && towers.size < TowerManager.MAX_WALL_WEAPONS,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(0.92f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "城墙维护",
                        modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleSmall,
                    )
                    WallUpgradeButton("生命上限 ${wall.level}/5", wallUpgradeCost, wallUpgradePhase && wall.level < 5, gold, UIManager::upgradeWall)
                    WallUpgradeButton("减伤 ${wall.damageReductionLevel}/5", reductionCost, wallUpgradePhase && wall.damageReductionLevel < 5, gold, UIManager::upgradeWallDamageReduction)
                    WallUpgradeButton("反伤 ${wall.reflectionLevel}/5", reflectionCost, wallUpgradePhase && wall.reflectionLevel < 5, gold, UIManager::upgradeWallReflection)
                    WallUpgradeButton("回血 ${wall.regenerationLevel}/3", regenerationCost, wallUpgradePhase && wall.regenerationLevel < 3, gold, UIManager::upgradeWallRegeneration)
                    WallUpgradeButton("修复 50%+", WallEconomyRules.REPAIR_COST, wallUpgradePhase && wall.currentHp < wall.maxHp, gold, UIManager::repairWall)
                }
            }
            SpatialActionButton(
                label = if (gameState == GameState.PREPARE) "开始第一波" else "开始下一波",
                onClick =
                    if (gameState == GameState.PREPARE) UIManager::startFirstWave
                    else UIManager::startNextWave,
                modifier = Modifier.fillMaxWidth().height(72.dp),
            )
        }
    }
}

@Composable
private fun TowerPurchaseRow(
    config: TowerConfig,
    currentGold: Int,
    hasFreeWallSlot: Boolean,
) {
    InfoCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(config.name, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleSmall)
                Text(
                    config.description,
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(16.dp))
            SpatialActionButton(
                label = "购买 ${config.cost}",
                onClick = { UIManager.purchaseTower(config.type) },
                enabled = hasFreeWallSlot && currentGold >= config.cost,
                modifier = Modifier.width(130.dp),
            )
        }
    }
}

@Composable
private fun WallUpgradeButton(
    label: String,
    cost: Int,
    available: Boolean,
    gold: Int,
    onClick: () -> Unit,
) {
    SpatialActionButton(
        label = if (cost > 0) "$label · $cost" else "$label · 满级",
        onClick = onClick,
        enabled = available && cost > 0 && gold >= cost,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TowerUpgradePanel(towerId: String?, modifier: Modifier) {
    val towers by TowerManager.towers.collectAsStateWithLifecycle()
    val gold by GoldManager.gold.collectAsStateWithLifecycle()
    val tower = towers.firstOrNull { it.id == towerId }
    PanelShell(modifier = Modifier.size(680.dp, 520.dp).then(modifier)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            PanelTitle("防御塔升级")
            if (tower == null) {
                Text("防御塔已不存在", color = PicoTheme.colorScheme.error)
            } else {
                TowerStats(tower)
                InfoCard {
                    Text("升级后立即提升 30% 伤害与 10% 攻速")
                    Text("升级费用：${tower.upgradeCost} 金币", color = PicoTheme.colorScheme.interaction)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SpatialActionButton(
                    label = "关闭",
                    onClick = UIManager::closeTowerUpgrade,
                    modifier = Modifier.weight(1f),
                )
                SpatialActionButton(
                    label = if (tower?.level == 5) "已满级" else "确认升级",
                    onClick = UIManager::upgradeSelectedTower,
                    enabled = tower != null && tower.level < 5 && gold >= tower.upgradeCost,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TowerStats(tower: TowerRuntimeState) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.panelLayer(PanelDepth.decoration, PanelDepth.decorationStep),
    ) {
        Text(
            "${TowerCatalog.get(tower.type).name} · 等级 ${tower.level}/5",
            modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
            color = PicoTheme.colorScheme.labelPrimary,
            style = PicoTheme.typography.titleLarge,
        )
        Text("伤害 ${tower.damage.clean()}", color = PicoTheme.colorScheme.labelSecondary)
        Text("攻击间隔 ${tower.attackIntervalSeconds.clean()} 秒", color = PicoTheme.colorScheme.labelSecondary)
        Text("攻击范围 ${tower.attackRange.clean()} 米", color = PicoTheme.colorScheme.labelSecondary)
        Text(tower.featureDescription, color = PicoTheme.colorScheme.interaction)
    }
}

@Composable
private fun CalibrationPanel(modifier: Modifier) {
    val planeState by SpatialManager.state.collectAsStateWithLifecycle()
    val groundSurface by SpatialManager.groundSurface.collectAsStateWithLifecycle()
    val gameState by GameStateManager.state.collectAsStateWithLifecycle()
    val progress =
        if (groundSurface != null) {
            1f
        } else when (val state = planeState) {
            is PlaneDetectionState.Running -> (state.detectedPlaneCount / 3f).coerceIn(0.08f, 0.92f)
            PlaneDetectionState.Ready -> 0f
            PlaneDetectionState.Starting -> 0.05f
            else -> 0f
        }
    val ready = gameState == GameState.CALIBRATING && groundSurface != null
    PanelShell(modifier = Modifier.size(700.dp, 500.dp).then(modifier)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            PanelTitle("空间校准")
            Text(
                text =
                    if (ready) {
                        "主地面已识别，请面向布防方向后确认"
                    } else {
                        "请缓慢环视环境，保持地面区域清晰"
                    },
                modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
                color = if (ready) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.labelSecondary,
                style = PicoTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().panelLayer(PanelDepth.decoration, PanelDepth.decorationStep),
            )
            Text(
                text = "扫描进度 ${(progress * 100).toInt()}%",
                modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
                color = PicoTheme.colorScheme.labelPrimary,
            )
            Spacer(Modifier.weight(1f))
            SpatialActionButton(
                label = "确认校准并开始防守",
                onClick = UIManager::confirmCalibration,
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(68.dp),
            )
        }
    }
}

@Composable
private fun SettlementPanel(result: GameResult?, modifier: Modifier) {
    val safeResult = result ?: GameResult(false, 0, 0, 0)
    PanelShell(modifier = Modifier.size(760.dp, 620.dp).then(modifier)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(38.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                text = if (safeResult.isWin) "坚守成功" else "防线失守",
                modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
                color = if (safeResult.isWin) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.error,
                style = PicoTheme.typography.displaySmall,
            )
            InfoCard(modifier = Modifier.fillMaxWidth()) {
                ResultRow("坚守波数", safeResult.reachWave.toString())
                ResultRow("总击杀", safeResult.totalKill.toString())
                ResultRow("本局获得金币", safeResult.totalGold.toString())
                ResultRow("晶核奖励", safeResult.crystalReward.toString())
            }
            Spacer(Modifier.weight(1f))
            SpatialActionButton(
                label = "重新开始",
                onClick = UIManager::restartGame,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SpatialActionButton(
                    label = "永久养成",
                    onClick = UIManager::openPermanentGrowth,
                    modifier = Modifier.weight(1f),
                )
                SpatialActionButton(
                    label = "返回主界面",
                    onClick = UIManager::returnToMainMenu,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PermanentGrowthPanel(modifier: Modifier) {
    val progress by SaveManager.progress.collectAsStateWithLifecycle()
    PanelShell(modifier = Modifier.size(1320.dp, 820.dp).then(modifier)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PanelTitle("永久养成")
            Row(
                modifier = Modifier.fillMaxWidth().panelLayer(PanelDepth.decoration, PanelDepth.decorationStep),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("持有晶核 ${progress.totalCrystals}", color = PicoTheme.colorScheme.interaction)
                Text("养成总等级 ${progress.totalUpgradeLevels}/120", color = PicoTheme.colorScheme.labelPrimary)
                Text("最高波数 ${progress.highestWave}", color = PicoTheme.colorScheme.labelSecondary)
                Text("最多击杀 ${progress.highestKills}", color = PicoTheme.colorScheme.labelSecondary)
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PermanentUpgradeCategory.entries.forEach { category ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            category.displayName,
                            color = PicoTheme.colorScheme.interaction,
                            style = PicoTheme.typography.titleSmall,
                        )
                        PermanentGrowthCatalog.all()
                            .filter { it.type.category == category }
                            .forEach { config -> PermanentUpgradeCard(config, progress.levelOf(config.type), progress.totalCrystals) }
                    }
                }
            }
            SpatialActionButton(
                label = "返回结算",
                onClick = UIManager::closePermanentGrowth,
                modifier = Modifier.fillMaxWidth().height(58.dp),
            )
        }
    }
}

@Composable
private fun PermanentUpgradeCard(
    config: PermanentUpgradeConfig,
    level: Int,
    crystals: Int,
) {
    val cost = if (level < config.maxLevel) SaveManager.upgradeCost(config.type) else 0
    InfoCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(config.name, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleSmall)
            Text("$level/${config.maxLevel}", color = PicoTheme.colorScheme.interaction)
        }
        Text(config.description, color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodySmall)
        SpatialActionButton(
            label = if (level >= config.maxLevel) "已满级" else "升级 · $cost 晶核",
            onClick = { UIManager.upgradePermanentGrowth(config.type) },
            enabled = level < config.maxLevel && crystals >= cost,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PanelShell(
    modifier: Modifier,
    cornerRadius: Int = 32,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    Box(
        modifier =
            modifier
                .panelLayer(PanelDepth.background, PanelDepth.backgroundStep)
                .clip(shape)
                .border(2.dp, PicoTheme.colorScheme.interaction, shape)
                .backgroundMaterial(enable = true, style = Material.Thick)
                .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .padding(18.dp)
                .panelLayer(PanelDepth.decoration, PanelDepth.decorationStep)
                .then(modifier),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun SpatialActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    var lastActivationNanos by remember { mutableLongStateOf(0L) }
    val activate = {
        lastActivationNanos = System.nanoTime()
        onClick()
    }
    LaunchedEffect(focused, enabled) {
        if (focused && enabled) {
            delay(2_000L)
            if (focused && System.nanoTime() - lastActivationNanos >= 2_000_000_000L) activate()
        }
    }
    Button(
        onClick = activate,
        modifier =
            modifier
                .panelLayer(PanelDepth.button, PanelDepth.buttonStep)
                .onFocusChanged { focused = it.isFocused }
                .focusable(enabled),
        enabled = enabled,
    ) {
        Text(
            text = label,
            modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().panelLayer(PanelDepth.text, PanelDepth.textStep),
        color = PicoTheme.colorScheme.labelPrimary,
        style = PicoTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun HudMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
            color = PicoTheme.colorScheme.labelSecondary,
        )
        Text(
            value,
            modifier = Modifier.panelLayer(PanelDepth.text, PanelDepth.textStep),
            color = PicoTheme.colorScheme.labelPrimary,
            style = PicoTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = PicoTheme.colorScheme.labelSecondary)
        Text(value, color = PicoTheme.colorScheme.labelPrimary, style = PicoTheme.typography.titleMedium)
    }
}

private fun GameState.toStatusText(): String =
    when (this) {
        GameState.IDLE -> "等待开始"
        GameState.CALIBRATING -> "空间校准"
        GameState.PREPARE -> "准备战斗"
        GameState.FIGHTING -> "战斗中"
        GameState.WAVE_PAUSE -> "波次暂停"
        GameState.SETTLE -> "战斗结算"
    }

private fun Float.clean(): String = if (this % 1f == 0f) toInt().toString() else "%.1f".format(this)
