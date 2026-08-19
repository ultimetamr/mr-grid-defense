package com.picoxr.mrspacetowerdefense.viewmodel

import androidx.lifecycle.ViewModel
import com.picoxr.mrspacetowerdefense.manager.GameManager
import com.picoxr.mrspacetowerdefense.manager.MonsterManager
import com.picoxr.mrspacetowerdefense.manager.GridManager
import com.picoxr.mrspacetowerdefense.manager.GoldManager
import com.picoxr.mrspacetowerdefense.manager.SpatialManager
import com.picoxr.mrspacetowerdefense.manager.WaveManager
import com.picoxr.mrspacetowerdefense.manager.WallManager
import com.picoxr.mrspacetowerdefense.model.MonsterRuntimeState
import com.picoxr.mrspacetowerdefense.model.GridRuntimeState
import com.picoxr.mrspacetowerdefense.model.PlaneDetectionState
import com.picoxr.mrspacetowerdefense.model.WallState
import com.picoxr.mrspacetowerdefense.model.WallRepairResult
import com.picoxr.mrspacetowerdefense.model.WallUpgradeResult
import com.picoxr.mrspacetowerdefense.model.WaveRuntimeState
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {
    val planeDetectionState: StateFlow<PlaneDetectionState> = SpatialManager.state
    val monsters: StateFlow<List<MonsterRuntimeState>> = MonsterManager.monsters
    val wallState: StateFlow<WallState> = WallManager.wallState
    val gold: StateFlow<Int> = GoldManager.gold
    val waveState: StateFlow<WaveRuntimeState> = WaveManager.runtimeState
    val gridState: StateFlow<GridRuntimeState> = GridManager.state

    fun startPlaneDetection() = SpatialManager.startPlaneDetection()

    fun stopPlaneDetection() = SpatialManager.stopPlaneDetection()

    fun onPermissionDenied(deniedPermissions: Set<String>) =
        SpatialManager.onPermissionDenied(deniedPermissions)

    fun startFirstWave() = GameManager.startFirstWave()

    fun startNextWave() = GameManager.startNextWave()

    fun upgradeWallMaxHp(): WallUpgradeResult = WallManager.upgradeMaxHp()

    fun upgradeWallDamageReduction(): WallUpgradeResult = WallManager.upgradeDamageReduction()

    fun upgradeWallReflection(): WallUpgradeResult = WallManager.upgradeReflection()

    fun upgradeWallRegeneration(): WallUpgradeResult = WallManager.upgradeRegeneration()

    fun repairWall(): WallRepairResult = WallManager.repair()
}
