package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.GoldLedger
import com.picoxr.mrspacetowerdefense.manager.WallEconomyRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EconomyWallSystemTest {
    @Test
    fun `gold ledger starts non-negative and never overspends`() {
        val ledger = GoldLedger(300)
        assertEquals(300, ledger.current)
        assertTrue(ledger.cost(200).succeeded)
        assertEquals(100, ledger.current)
        assertFalse(ledger.cost(101).succeeded)
        assertEquals(100, ledger.current)
        assertFalse(ledger.cost(-1).succeeded)
        assertEquals(100, ledger.current)
    }

    @Test
    fun `invalid additions are ignored and overflow is clamped`() {
        val ledger = GoldLedger(-99)
        assertEquals(0, ledger.current)
        ledger.add(-10)
        assertEquals(0, ledger.current)
        ledger.add(Int.MAX_VALUE)
        ledger.add(100)
        assertEquals(Int.MAX_VALUE, ledger.current)
        ledger.set(-1)
        assertEquals(0, ledger.current)
    }

    @Test
    fun `wall upgrade costs use the current level`() {
        assertEquals(100, WallEconomyRules.upgradeCost(1))
        assertEquals(200, WallEconomyRules.upgradeCost(2))
        assertEquals(300, WallEconomyRules.upgradeCost(3))
        assertEquals(400, WallEconomyRules.upgradeCost(4))
        assertEquals(5, WallEconomyRules.MAX_LEVEL)
        assertEquals(50, WallEconomyRules.HP_PER_LEVEL)
    }

    @Test
    fun `repair restores half current hp without exceeding maximum`() {
        assertEquals(60, WallEconomyRules.repairedHp(40, 100))
        assertEquals(100, WallEconomyRules.repairedHp(80, 100))
        assertEquals(2, WallEconomyRules.repairedHp(1, 100))
        assertEquals(80, WallEconomyRules.REPAIR_COST)
    }

    @Test
    fun `secondary wall upgrades have independent costs and effects`() {
        assertEquals(150, WallEconomyRules.damageReductionUpgradeCost(0))
        assertEquals(300, WallEconomyRules.damageReductionUpgradeCost(1))
        assertEquals(120, WallEconomyRules.reflectionUpgradeCost(0))
        assertEquals(200, WallEconomyRules.regenerationUpgradeCost(0))
        assertEquals(0.5f, WallEconomyRules.sessionDamageReduction(5), 0.0001f)
        assertEquals(0.25f, WallEconomyRules.reflectionRatio(5), 0.0001f)
    }
}
