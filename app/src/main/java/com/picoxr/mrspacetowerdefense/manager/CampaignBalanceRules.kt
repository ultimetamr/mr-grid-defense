package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.TowerType

/** Deterministic campaign-economy checks used by host-side balance regression tests. */
object CampaignBalanceRules {
    val RECOMMENDED_FINAL_LOADOUT: List<TowerType> =
        listOf(
            TowerType.ARCHER,
            TowerType.ARCHER,
            TowerType.ARCHER,
            TowerType.CROSSBOW,
            TowerType.CROSSBOW,
            TowerType.CROSSBOW,
            TowerType.CANNON,
            TowerType.CANNON,
            TowerType.CANNON,
        )

    fun maxedTowerCost(type: TowerType): Int {
        val config = TowerCatalog.get(type)
        return config.cost + (1 until config.maxLevel).sumOf { level -> TowerMath.upgradeCost(config, level) }
    }

    fun maxedLoadoutCost(types: List<TowerType>): Int = types.sumOf(::maxedTowerCost)

    fun maxedWallUpgradeCost(): Int =
        (1 until WallEconomyRules.MAX_LEVEL).sumOf(WallEconomyRules::upgradeCost) +
            (0 until WallEconomyRules.DAMAGE_REDUCTION_MAX_LEVEL).sumOf(WallEconomyRules::damageReductionUpgradeCost) +
            (0 until WallEconomyRules.REFLECTION_MAX_LEVEL).sumOf(WallEconomyRules::reflectionUpgradeCost) +
            (0 until WallEconomyRules.REGENERATION_MAX_LEVEL).sumOf(WallEconomyRules::regenerationUpgradeCost)

    fun killGoldBeforeWave(exclusiveWaveIndex: Int): Int {
        require(exclusiveWaveIndex in 1..WaveCatalog.TOTAL_WAVES)
        return (1 until exclusiveWaveIndex).sumOf { waveIndex ->
            WaveCatalog.spawnSequence(WaveCatalog.get(waveIndex)).sumOf { type ->
                MonsterCatalog.get(type).killGoldReward
            }
        }
    }

    fun availableGoldBeforeWave(waveIndex: Int): Int {
        require(waveIndex in 1..WaveCatalog.TOTAL_WAVES)
        return GoldManager.INITIAL_GOLD +
            killGoldBeforeWave(waveIndex) +
            (waveIndex - 1) * WaveCatalog.WAVE_REWARD_GOLD
    }
}
