package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.TowerConfig
import com.picoxr.mrspacetowerdefense.model.TowerType
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2

object TowerMath {
    fun damageAtLevel(
        config: TowerConfig,
        level: Int,
        permanentBonus: Float = 0f,
        auraBonus: Float = 0f,
    ): Float {
        require(level in 1..config.maxLevel)
        return config.damage *
            (1f + config.damageBonusPerLevel * (level - 1)) *
            (1f + permanentBonus.coerceAtLeast(0f)) *
            (1f + auraBonus.coerceAtLeast(0f))
    }

    /** attackSpeed is configured as seconds per shot; a speed bonus shortens the interval. */
    fun attackIntervalAtLevel(
        config: TowerConfig,
        level: Int,
        permanentBonus: Float = 0f,
        auraBonus: Float = 0f,
    ): Float {
        require(level in 1..config.maxLevel)
        return config.attackSpeed /
            (1f + config.attackSpeedBonusPerLevel * (level - 1)) /
            (1f + permanentBonus.coerceAtLeast(0f)) /
            (1f + auraBonus.coerceAtLeast(0f))
    }

    fun upgradeCost(config: TowerConfig, currentLevel: Int): Int {
        require(currentLevel in 1 until config.maxLevel)
        return ceil(config.cost * 1.5f * currentLevel).toInt()
    }

    fun rangeAtLevel(config: TowerConfig, level: Int, permanentBonus: Float = 0f): Float {
        require(level in 1..config.maxLevel)
        val base = if (config.type == TowerType.TOTEM && level >= 5) 3.5f else config.attackRange
        val featureMultiplier = if (config.type == TowerType.ARCHER && level >= 5) 1.2f else 1f
        return base * featureMultiplier * (1f + permanentBonus.coerceAtLeast(0f))
    }

    fun projectileCount(config: TowerConfig, level: Int): Int =
        if (config.type == TowerType.ARCHER) when {
            level >= 5 -> 3
            level >= 3 -> 2
            else -> 1
        } else 1

    fun penetrationCount(config: TowerConfig, level: Int): Int =
        if (config.type == TowerType.CROSSBOW) when {
            level >= 5 -> 4
            level >= 3 -> 2
            else -> 1
        } else 1

    fun splashRadius(config: TowerConfig, level: Int): Float =
        if (config.type != TowerType.CANNON) config.splashRadius else when {
            level >= 5 -> config.splashRadius * 2f
            level >= 3 -> config.splashRadius * 1.5f
            else -> config.splashRadius
        }

    fun distanceSquared(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return dx * dx + dy * dy + dz * dz
    }

    fun segmentPointDistanceSquared(
        startX: Float,
        startY: Float,
        startZ: Float,
        endX: Float,
        endY: Float,
        endZ: Float,
        pointX: Float,
        pointY: Float,
        pointZ: Float,
    ): Float {
        val segmentX = endX - startX
        val segmentY = endY - startY
        val segmentZ = endZ - startZ
        val lengthSquared =
            segmentX * segmentX + segmentY * segmentY + segmentZ * segmentZ
        if (lengthSquared <= 0.000001f) {
            return distanceSquared(startX, startY, startZ, pointX, pointY, pointZ)
        }
        val projection =
            ((pointX - startX) * segmentX +
                (pointY - startY) * segmentY +
                (pointZ - startZ) * segmentZ) / lengthSquared
        val t = projection.coerceIn(0f, 1f)
        return distanceSquared(
            startX + segmentX * t,
            startY + segmentY * t,
            startZ + segmentZ * t,
            pointX,
            pointY,
            pointZ,
        )
    }

    fun normalizedDirection(
        fromX: Float,
        fromY: Float,
        fromZ: Float,
        toX: Float,
        toY: Float,
        toZ: Float,
    ): Triple<Float, Float, Float> {
        val dx = toX - fromX
        val dy = toY - fromY
        val dz = toZ - fromZ
        val length = sqrt(max(dx * dx + dy * dy + dz * dz, 0.000001f))
        return Triple(dx / length, dy / length, dz / length)
    }

    /** Horizontal targeting range plus enough 3D travel for a wall-top launch arc. */
    fun projectileTravelLimit(horizontalRange: Float, verticalAllowance: Float = 0.6f): Float {
        require(horizontalRange > 0f && horizontalRange.isFinite())
        require(verticalAllowance >= 0f && verticalAllowance.isFinite())
        return sqrt(horizontalRange * horizontalRange + verticalAllowance * verticalAllowance)
    }

    /** Converts a scene-space direction into a wall-mounted tower's local X axis. */
    fun mountLocalX(directionX: Float, directionZ: Float, mountYawRadians: Float): Float =
        directionX * cos(mountYawRadians) + directionZ * sin(mountYawRadians)

    /** Converts a scene-space direction into a wall-mounted tower's local Z axis. */
    fun mountLocalZ(directionX: Float, directionZ: Float, mountYawRadians: Float): Float =
        -directionX * sin(mountYawRadians) + directionZ * cos(mountYawRadians)

    /** Pure local-Y rotation: tower roots stay square to the wall with no pitch or roll. */
    fun turretLocalYawRadians(directionX: Float, directionZ: Float, mountYawRadians: Float): Float =
        atan2(
            mountLocalX(directionX, directionZ, mountYawRadians),
            -mountLocalZ(directionX, directionZ, mountYawRadians),
        )
}
