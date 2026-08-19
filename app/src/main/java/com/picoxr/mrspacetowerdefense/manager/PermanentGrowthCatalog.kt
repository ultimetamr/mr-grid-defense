package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.PermanentBonusSnapshot
import com.picoxr.mrspacetowerdefense.model.PermanentProgress
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeConfig
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeType
import kotlin.math.roundToInt

object PermanentGrowthCatalog {
    private val configs =
        listOf(
            config(PermanentUpgradeType.STARTING_GOLD, "开局金币", "开局金币 +5%/级", 0.05f, 20),
            config(PermanentUpgradeType.KILL_GOLD, "击杀收益", "怪物击杀金币 +5%/级", 0.05f, 25),
            config(PermanentUpgradeType.WAVE_REWARD, "波次收益", "波次结算金币 +5%/级", 0.05f, 25),
            config(PermanentUpgradeType.WALL_INITIAL_HP, "城墙根基", "城墙初始血量 +10%/级", 0.10f, 30),
            config(PermanentUpgradeType.WALL_DAMAGE_REDUCTION, "坚固壁垒", "城墙全局减伤 +2%/级", 0.02f, 35),
            config(PermanentUpgradeType.REPAIR_EFFICIENCY, "高效修复", "城墙修复量 +5%/级", 0.05f, 25),
            config(PermanentUpgradeType.TOWER_DAMAGE, "火力强化", "所有塔基础伤害 +3%/级", 0.03f, 35),
            config(PermanentUpgradeType.TOWER_ATTACK_SPEED, "装填强化", "所有塔攻速 +2%/级", 0.02f, 35),
            config(PermanentUpgradeType.TOWER_RANGE, "瞄准强化", "所有塔攻击范围 +2%/级", 0.02f, 35),
            config(PermanentUpgradeType.RAY_WARNING, "危险预知", "射线预警时间 +5%/级", 0.05f, 30),
            config(PermanentUpgradeType.DEATH_SHIELD, "免死护盾", "1级获得1次护盾，6级获得第2次", 0f, 45),
            config(PermanentUpgradeType.SAFE_GRID_WINDOW, "快速换位", "安全格换位窗口 +2%/级", 0.02f, 30),
        ).associateBy(PermanentUpgradeConfig::type)

    fun all(): List<PermanentUpgradeConfig> = PermanentUpgradeType.entries.map(::get)

    fun get(type: PermanentUpgradeType): PermanentUpgradeConfig = checkNotNull(configs[type])

    fun upgradeCost(type: PermanentUpgradeType, currentLevel: Int): Int {
        val config = get(type)
        require(currentLevel in 0..config.maxLevel)
        return config.baseCost + config.costStep * currentLevel
    }

    fun bonuses(progress: PermanentProgress): PermanentBonusSnapshot {
        fun bonus(type: PermanentUpgradeType): Float = get(type).bonusPerLevel * progress.levelOf(type)
        val shieldLevel = progress.levelOf(PermanentUpgradeType.DEATH_SHIELD)
        return PermanentBonusSnapshot(
            startingGoldBonus = bonus(PermanentUpgradeType.STARTING_GOLD),
            killGoldBonus = bonus(PermanentUpgradeType.KILL_GOLD),
            waveRewardBonus = bonus(PermanentUpgradeType.WAVE_REWARD),
            wallInitialHpBonus = bonus(PermanentUpgradeType.WALL_INITIAL_HP),
            wallDamageReduction = bonus(PermanentUpgradeType.WALL_DAMAGE_REDUCTION),
            repairEfficiencyBonus = bonus(PermanentUpgradeType.REPAIR_EFFICIENCY),
            towerDamageBonus = bonus(PermanentUpgradeType.TOWER_DAMAGE),
            towerAttackSpeedBonus = bonus(PermanentUpgradeType.TOWER_ATTACK_SPEED),
            towerRangeBonus = bonus(PermanentUpgradeType.TOWER_RANGE),
            warningDurationBonus = bonus(PermanentUpgradeType.RAY_WARNING),
            deathShieldCharges = when {
                shieldLevel >= 6 -> 2
                shieldLevel >= 1 -> 1
                else -> 0
            },
            safeGridWindowBonus = bonus(PermanentUpgradeType.SAFE_GRID_WINDOW),
        )
    }

    fun applyMultiplier(baseValue: Int, bonus: Float): Int =
        (baseValue * (1f + bonus)).roundToInt().coerceAtLeast(0)

    fun applyMultiplier(baseValue: Float, bonus: Float): Float = baseValue * (1f + bonus)

    private fun config(
        type: PermanentUpgradeType,
        name: String,
        description: String,
        bonusPerLevel: Float,
        baseCost: Int,
    ) = PermanentUpgradeConfig(
        type = type,
        name = name,
        description = description,
        bonusPerLevel = bonusPerLevel,
        baseCost = baseCost,
        costStep = (baseCost / 2f).roundToInt().coerceAtLeast(1),
    )
}
