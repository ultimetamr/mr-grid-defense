package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.MonsterBehavior
import com.picoxr.mrspacetowerdefense.model.MonsterConfig
import com.picoxr.mrspacetowerdefense.model.MonsterType

object MonsterCatalog {
    private val configs =
        mapOf(
            MonsterType.NORMAL to MonsterConfig(MonsterType.NORMAL, "普通虫", 30f, 1f, 1, 5, "monster_normal"),
            MonsterType.FAST to MonsterConfig(MonsterType.FAST, "疾行虫", 20f, 2f, 1, 8, "monster_fast"),
            MonsterType.ARMORED to MonsterConfig(MonsterType.ARMORED, "重甲甲虫", 150f, 0.5f, 4, 15, "monster_armored"),
            MonsterType.SELF_DESTRUCT to
                MonsterConfig(
                    MonsterType.SELF_DESTRUCT, "自爆蠕虫", 60f, 1.1f, 0, 12, "monster_self_destruct",
                    MonsterBehavior.SELF_DESTRUCT, selfDestructDamage = 35,
                ),
            MonsterType.ACID to
                MonsterConfig(
                    MonsterType.ACID, "远程吐酸怪", 80f, 0.75f, 3, 18, "monster_acid",
                    MonsterBehavior.RANGED, attackRange = 2f, attackIntervalSeconds = 1.5f,
                ),
            MonsterType.ELITE to MonsterConfig(MonsterType.ELITE, "精英守卫", 300f, 0.8f, 8, 25, "monster_elite"),
            MonsterType.BOSS to
                MonsterConfig(
                    MonsterType.BOSS, "巨型甲壳Boss", 2_500f, 0.4f, 25, 150, "monster_boss",
                    MonsterBehavior.BOSS,
                ),
        )

    fun get(type: MonsterType): MonsterConfig = checkNotNull(configs[type])

    fun all(): List<MonsterConfig> = MonsterType.entries.map(::get)
}
