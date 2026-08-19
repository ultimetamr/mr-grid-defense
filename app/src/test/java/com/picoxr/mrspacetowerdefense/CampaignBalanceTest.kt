package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.CampaignBalanceRules
import com.picoxr.mrspacetowerdefense.manager.GameplayTuning
import com.picoxr.mrspacetowerdefense.manager.MonsterCatalog
import com.picoxr.mrspacetowerdefense.manager.TowerCatalog
import com.picoxr.mrspacetowerdefense.manager.TowerMath
import com.picoxr.mrspacetowerdefense.manager.WallEconomyRules
import com.picoxr.mrspacetowerdefense.manager.WaveCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignBalanceTest {
    @Test
    fun `original economy does not flood the player with maxed endgame equipment`() {
        val rosterCost =
            CampaignBalanceRules.maxedLoadoutCost(CampaignBalanceRules.RECOMMENDED_FINAL_LOADOUT)
        val wallCost = CampaignBalanceRules.maxedWallUpgradeCost()
        val available = CampaignBalanceRules.availableGoldBeforeWave(WaveCatalog.TOTAL_WAVES)

        assertEquals(16_800, rosterCost)
        assertEquals(6_250, wallCost)
        assertEquals(2_365, available)
        assertTrue(available < rosterCost + wallCost)
    }

    @Test
    fun `recommended max roster clears final wave without counting cannon splash`() {
        val outcome = simulateFinalWaveWithoutSplash()

        assertTrue("final wave must be cleared in the conservative model", outcome.cleared)
        assertTrue("wall must survive the conservative model", outcome.wallHp > 0)
    }

    private fun simulateFinalWaveWithoutSplash(): BalanceOutcome {
        val wave = WaveCatalog.get(WaveCatalog.TOTAL_WAVES)
        val monsters =
            WaveCatalog.spawnSequence(wave).mapIndexed { index, type ->
                val config = MonsterCatalog.get(type)
                SimMonster(
                    hp = config.baseHp * wave.hpMultiplier,
                    speed = GameplayTuning.monsterMoveSpeed(config.moveSpeed, wave.moveSpeedMultiplier),
                    siegeDamage = config.siegeDamage,
                    spawnSeconds = index * WaveCatalog.SPAWN_INTERVAL_MILLIS / 1_000f,
                )
            }
        val towers =
            CampaignBalanceRules.RECOMMENDED_FINAL_LOADOUT.map { type ->
                val config = TowerCatalog.get(type)
                SimTower(
                    damage = TowerMath.damageAtLevel(config, config.maxLevel),
                    interval = TowerMath.attackIntervalAtLevel(config, config.maxLevel),
                    range = config.attackRange,
                )
            }
        var wallHp = WallEconomyRules.INITIAL_MAX_HP +
            WallEconomyRules.HP_PER_LEVEL * (WallEconomyRules.MAX_LEVEL - 1)
        var elapsed = 0f
        val step = 0.01f
        while (elapsed < 120f && wallHp > 0 && monsters.any(SimMonster::alive)) {
            monsters.forEach { monster ->
                if (!monster.alive || elapsed < monster.spawnSeconds) return@forEach
                if (monster.distanceToWall > 0f) {
                    monster.distanceToWall =
                        (monster.distanceToWall - monster.speed * step).coerceAtLeast(0f)
                } else {
                    monster.siegeElapsed += step
                    while (monster.siegeElapsed >= 1f) {
                        monster.siegeElapsed -= 1f
                        wallHp -= monster.siegeDamage
                    }
                }
            }
            towers.forEach { tower ->
                tower.cooldown -= step
                if (tower.cooldown > 0f) return@forEach
                val target =
                    monsters
                        .asSequence()
                        .filter { it.alive && elapsed >= it.spawnSeconds && it.distanceToWall <= tower.range }
                        .minByOrNull(SimMonster::distanceToWall)
                        ?: return@forEach
                // Deliberately direct-hit only: runtime cannon splash can only improve this result.
                target.hp -= tower.damage
                tower.cooldown = tower.interval
            }
            elapsed += step
        }
        return BalanceOutcome(monsters.none(SimMonster::alive), wallHp)
    }

    private data class SimMonster(
        var hp: Float,
        val speed: Float,
        val siegeDamage: Int,
        val spawnSeconds: Float,
        var distanceToWall: Float = 4f,
        var siegeElapsed: Float = 0f,
    ) {
        val alive: Boolean get() = hp > 0f
    }

    private data class SimTower(
        val damage: Float,
        val interval: Float,
        val range: Float,
        var cooldown: Float = 0f,
    )

    private data class BalanceOutcome(val cleared: Boolean, val wallHp: Int)
}
