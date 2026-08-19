package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.TowerConfig
import com.picoxr.mrspacetowerdefense.model.TowerRole
import com.picoxr.mrspacetowerdefense.model.TowerType

object TowerCatalog {
    private const val MAX_LEVEL = 5
    private const val DAMAGE_BONUS = 0.30f
    private const val ATTACK_SPEED_BONUS = 0.10f
    private const val TOWER_HEIGHT = 1f

    private val configs =
        mapOf(
            TowerType.ARCHER to
                TowerConfig(
                    TowerType.ARCHER, "弓箭塔", 50, 5f, 0.3f, 2f, 0f,
                    MAX_LEVEL, DAMAGE_BONUS, ATTACK_SPEED_BONUS, TOWER_HEIGHT,
                    description = "高速单体输出；3级双箭，5级三箭且射程+20%",
                ),
            TowerType.CROSSBOW to
                TowerConfig(
                    TowerType.CROSSBOW, "弩箭塔", 100, 15f, 0.8f, 3f, 0f,
                    MAX_LEVEL, DAMAGE_BONUS, ATTACK_SPEED_BONUS, TOWER_HEIGHT,
                    description = "高伤穿透；3级穿透2个，5级穿透4个并减速",
                ),
            TowerType.CANNON to
                TowerConfig(
                    TowerType.CANNON, "炮台", 200, 50f, 2f, 4f, 0.5f,
                    MAX_LEVEL, DAMAGE_BONUS, ATTACK_SPEED_BONUS, TOWER_HEIGHT,
                    description = "范围爆破；3级溅射+50%，5级溅射翻倍并眩晕",
                ),
            TowerType.FROST to
                TowerConfig(
                    TowerType.FROST, "冰霜塔", 150, 0f, 1f, 2.5f, 0f,
                    MAX_LEVEL, 0f, ATTACK_SPEED_BONUS, TOWER_HEIGHT,
                    TowerRole.SUPPORT,
                    "范围减速30%，10%概率冻结1秒；5级减速50%，25%概率冻结1.5秒",
                ),
            TowerType.BURN to
                TowerConfig(
                    TowerType.BURN, "灼烧炮塔", 180, 0f, 1.5f, 3f, 0f,
                    MAX_LEVEL, 0f, ATTACK_SPEED_BONUS, TOWER_HEIGHT,
                    TowerRole.SUPPORT,
                    "灼烧8/秒持续3秒并减速10%，可叠加；5级效果翻倍并引燃",
                ),
            TowerType.TOTEM to
                TowerConfig(
                    TowerType.TOTEM, "力量图腾", 120, 0f, 1f, 2f, 0f,
                    MAX_LEVEL, 0f, 0f, TOWER_HEIGHT,
                    TowerRole.SUPPORT,
                    "范围内友塔伤害+20%、攻速+15%，不可叠加；5级强化至35%/25%",
                ),
        )

    fun get(type: TowerType): TowerConfig = checkNotNull(configs[type])

    fun all(): List<TowerConfig> = TowerType.entries.map(::get)

    fun featureDescription(type: TowerType, level: Int): String =
        when (type) {
            TowerType.ARCHER -> when {
                level >= 5 -> "三箭 · 射程+20%"
                level >= 3 -> "双箭"
                else -> "高速单体"
            }
            TowerType.CROSSBOW -> when {
                level >= 5 -> "穿透4个 · 命中减速"
                level >= 3 -> "穿透2个"
                else -> "重型单体"
            }
            TowerType.CANNON -> when {
                level >= 5 -> "双倍溅射 · 眩晕1秒"
                level >= 3 -> "溅射+50%"
                else -> "范围爆破"
            }
            TowerType.FROST -> if (level >= 5) "减速50% · 25%冻结1.5秒" else "减速30% · 10%冻结1秒"
            TowerType.BURN -> if (level >= 5) "灼烧16/秒 · 减速20% · 击杀引燃" else "灼烧8/秒×3秒 · 减速10%"
            TowerType.TOTEM -> if (level >= 5) "3.5m · 伤害+35% · 攻速+25%" else "2m · 伤害+20% · 攻速+15%"
        }
}
