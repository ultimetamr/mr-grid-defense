package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.TowerCatalog
import com.picoxr.mrspacetowerdefense.manager.TowerActionRules
import com.picoxr.mrspacetowerdefense.manager.TowerMath
import com.picoxr.mrspacetowerdefense.manager.TowerEffectRules
import com.picoxr.mrspacetowerdefense.manager.WallWeaponSlotRules
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.TowerPlacementPreviewState
import com.picoxr.mrspacetowerdefense.model.TowerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class TowerSystemTest {
    @Test
    fun `wall weapon allocator fills exactly nine slots in order`() {
        var occupiedMask = 0
        repeat(WallWeaponSlotRules.MAX_SLOTS) { expectedIndex ->
            val slot = WallWeaponSlotRules.nextFreeSlot(occupiedMask)
            assertEquals(expectedIndex, slot)
            occupiedMask = occupiedMask or (1 shl checkNotNull(slot))
        }
        assertEquals(null, WallWeaponSlotRules.nextFreeSlot(occupiedMask))

        val offsets =
            (0 until WallWeaponSlotRules.MAX_SLOTS)
                .map { WallWeaponSlotRules.lateralOffset(it, 0.4f) }
                .sorted()
        assertEquals((-4..4).map { it * 0.4f }, offsets)
    }

    @Test
    fun `prepare allows placement but only fighting enables automatic combat`() {
        assertTrue(TowerActionRules.canPlace(GameState.PREPARE))
        assertTrue(TowerActionRules.canPlace(GameState.WAVE_PAUSE))
        assertFalse(TowerActionRules.canPlace(GameState.FIGHTING))
        assertFalse(TowerActionRules.canUpgrade(GameState.PREPARE))
        assertTrue(TowerActionRules.canUpgrade(GameState.WAVE_PAUSE))
        assertTrue(TowerActionRules.isAutomaticCombatActive(GameState.FIGHTING))
        assertFalse(TowerActionRules.isAutomaticCombatActive(GameState.WAVE_PAUSE))
    }

    @Test
    fun `all five active towers launch projectiles while totem remains passive`() {
        TowerType.entries.filter { it != TowerType.TOTEM }.forEach { type ->
            assertTrue("$type must launch a visible projectile", TowerActionRules.attacksWithProjectile(type))
        }
        assertFalse(TowerActionRules.attacksWithProjectile(TowerType.TOTEM))
    }

    @Test
    fun `catalog contains requested tower values`() {
        val archer = TowerCatalog.get(TowerType.ARCHER)
        val crossbow = TowerCatalog.get(TowerType.CROSSBOW)
        val cannon = TowerCatalog.get(TowerType.CANNON)

        assertEquals(50, archer.cost)
        assertEquals(0.3f, archer.attackSpeed, 0.0001f)
        assertEquals(5f, archer.damage, 0.0001f)
        assertEquals(2f, archer.attackRange, 0.0001f)
        assertEquals(100, crossbow.cost)
        assertEquals(15f, crossbow.damage, 0.0001f)
        assertEquals(3f, crossbow.attackRange, 0.0001f)
        assertEquals(200, cannon.cost)
        assertEquals(50f, cannon.damage, 0.0001f)
        assertEquals(0.5f, cannon.splashRadius, 0.0001f)
        assertEquals(4f, cannon.attackRange, 0.0001f)
        assertTrue(TowerCatalog.all().all { it.height == 1f && it.maxLevel == 5 })
    }

    @Test
    fun `upgrade math increases damage and fire rate immediately`() {
        val archer = TowerCatalog.get(TowerType.ARCHER)

        assertEquals(75, TowerMath.upgradeCost(archer, 1))
        assertEquals(150, TowerMath.upgradeCost(archer, 2))
        assertEquals(11f, TowerMath.damageAtLevel(archer, 5), 0.0001f)
        assertEquals(0.3f / 1.4f, TowerMath.attackIntervalAtLevel(archer, 5), 0.0001f)
    }

    @Test
    fun `catalog contains six towers and level features`() {
        assertEquals(6, TowerCatalog.all().size)
        assertEquals(150, TowerCatalog.get(TowerType.FROST).cost)
        assertEquals(180, TowerCatalog.get(TowerType.BURN).cost)
        assertEquals(120, TowerCatalog.get(TowerType.TOTEM).cost)
        assertEquals(2, TowerMath.projectileCount(TowerCatalog.get(TowerType.ARCHER), 3))
        assertEquals(3, TowerMath.projectileCount(TowerCatalog.get(TowerType.ARCHER), 5))
        assertEquals(2, TowerMath.penetrationCount(TowerCatalog.get(TowerType.CROSSBOW), 3))
        assertEquals(4, TowerMath.penetrationCount(TowerCatalog.get(TowerType.CROSSBOW), 5))
        assertEquals(1f, TowerMath.splashRadius(TowerCatalog.get(TowerType.CANNON), 5), 0.0001f)
    }

    @Test
    fun `all six tower combat profiles match their unlocked behavior`() {
        val archer = TowerCatalog.get(TowerType.ARCHER)
        val crossbow = TowerCatalog.get(TowerType.CROSSBOW)
        val cannon = TowerCatalog.get(TowerType.CANNON)

        assertEquals(3, TowerMath.projectileCount(archer, 5))
        assertEquals(4, TowerMath.penetrationCount(crossbow, 5))
        assertEquals(1f, TowerMath.splashRadius(cannon, 5), 0.0001f)
        assertEquals(1f, TowerEffectRules.cannonStunSeconds(5), 0.0001f)

        val frost = TowerEffectRules.frost(5)
        assertEquals(0.5f, frost.slow.speedMultiplier, 0.0001f)
        assertEquals(0.25f, frost.freezeChance, 0.0001f)
        assertEquals(1.5f, frost.freezeDurationSeconds, 0.0001f)

        val burn = TowerEffectRules.burn(5)
        assertEquals(16f, burn.damagePerSecond, 0.0001f)
        assertEquals(3f, burn.durationSeconds, 0.0001f)
        assertEquals(0.8f, burn.speedMultiplier, 0.0001f)
        assertTrue(burn.igniteOnKill)

        val totem = TowerEffectRules.totem(5)
        assertEquals(3.5f, totem.rangeMeters, 0.0001f)
        assertEquals(0.35f, totem.damageBonus, 0.0001f)
        assertEquals(0.25f, totem.attackSpeedBonus, 0.0001f)
    }

    @Test
    fun `wall yaw converts target direction into level local turret yaw`() {
        val yaw = (PI / 2.0).toFloat()

        assertEquals(0f, TowerMath.mountLocalX(1f, 0f, yaw), 0.0001f)
        assertEquals(-1f, TowerMath.mountLocalZ(1f, 0f, yaw), 0.0001f)
        assertEquals(0f, TowerMath.turretLocalYawRadians(1f, 0f, yaw), 0.0001f)
        assertEquals(
            90f,
            Math.toDegrees(TowerMath.turretLocalYawRadians(1f, 0f, 0f).toDouble()).toFloat(),
            0.0001f,
        )
    }

    @Test
    fun `swept projectile segment catches targets between frames`() {
        val distanceSquared =
            TowerMath.segmentPointDistanceSquared(
                startX = 0f,
                startY = 0f,
                startZ = 0f,
                endX = 0f,
                endY = 0f,
                endZ = -1f,
                pointX = 0.05f,
                pointY = 0f,
                pointZ = -0.5f,
            )

        assertTrue(distanceSquared <= 0.1f * 0.1f)
    }

    @Test
    fun `projectile travel includes wall top vertical displacement`() {
        assertTrue(TowerMath.projectileTravelLimit(2.5f) > 2.5f)
        assertTrue(TowerMath.projectileTravelLimit(3f) > 3f)
    }

    @Test
    fun `preview requires every placement condition`() {
        val valid =
            TowerPlacementPreviewState(
                selectedType = TowerType.ARCHER,
                isVisible = true,
                isInsidePlacementZone = true,
                isAffordable = true,
                isBlocked = false,
            )

        assertTrue(valid.canPlace)
        assertFalse(valid.copy(isAffordable = false).canPlace)
        assertFalse(valid.copy(isBlocked = true).canPlace)
    }
}
