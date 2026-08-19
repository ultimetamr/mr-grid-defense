package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.PermanentGrowthCatalog
import com.picoxr.mrspacetowerdefense.model.PermanentProgress
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeCategory
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermanentGrowthSystemTest {
    @Test
    fun `catalog contains three upgrades in every category`() {
        assertEquals(12, PermanentGrowthCatalog.all().size)
        PermanentUpgradeCategory.entries.forEach { category ->
            assertEquals(3, PermanentGrowthCatalog.all().count { it.type.category == category })
        }
        assertTrue(PermanentGrowthCatalog.all().all { it.maxLevel == 10 })
    }

    @Test
    fun `every upgrade has a strictly increasing crystal cost`() {
        PermanentUpgradeType.entries.forEach { type ->
            val costs = (0 until 10).map { level -> PermanentGrowthCatalog.upgradeCost(type, level) }
            costs.zipWithNext().forEach { (current, next) -> assertTrue(next > current) }
        }
    }

    @Test
    fun `bonuses are derived from independent saved levels`() {
        val progress =
            PermanentProgress(
                upgradeLevels =
                    mapOf(
                        PermanentUpgradeType.STARTING_GOLD to 4,
                        PermanentUpgradeType.WALL_INITIAL_HP to 3,
                        PermanentUpgradeType.TOWER_DAMAGE to 10,
                        PermanentUpgradeType.RAY_WARNING to 2,
                        PermanentUpgradeType.DEATH_SHIELD to 6,
                    ),
            )
        val bonuses = PermanentGrowthCatalog.bonuses(progress)

        assertEquals(0.20f, bonuses.startingGoldBonus, 0.0001f)
        assertEquals(0.30f, bonuses.wallInitialHpBonus, 0.0001f)
        assertEquals(0.30f, bonuses.towerDamageBonus, 0.0001f)
        assertEquals(0.10f, bonuses.warningDurationBonus, 0.0001f)
        assertEquals(2, bonuses.deathShieldCharges)
    }
}
