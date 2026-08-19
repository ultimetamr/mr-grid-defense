package com.picoxr.mrspacetowerdefense.manager

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Matrix4
import com.pico.spatial.core.math.Vector3
import com.picoxr.mrspacetowerdefense.model.MonsterActionState
import com.picoxr.mrspacetowerdefense.model.MonsterCombatTarget
import com.picoxr.mrspacetowerdefense.model.MonsterConfig
import com.picoxr.mrspacetowerdefense.model.MonsterDamageResult
import com.picoxr.mrspacetowerdefense.model.MonsterRuntimeState
import com.picoxr.mrspacetowerdefense.model.MonsterType
import java.util.UUID

/** Shared runtime implementation reused by every monster type in the catalog. */
open class BaseMonster internal constructor(
    val config: MonsterConfig,
    internal val entity: Entity,
    override val hitRadius: Float,
    internal val visualHeight: Float,
) : MonsterCombatTarget {
    final override var id: String = "pooled-${UUID.randomUUID()}"
        private set
    override val type: MonsterType get() = config.type
    override val killGoldReward: Int get() = config.killGoldReward

    internal var active = false
        private set
    internal var currentHp = 0f
        private set
    internal var maxHp = 0f
        private set
    internal var effectiveMoveSpeed = 0f
        private set
    internal var x = 0f
        private set
    internal var z = 0f
        private set
    internal var wallTargetX = 0f
        private set
    internal var wallTargetZ = 0f
        private set
    internal var actionState = MonsterActionState.DEAD
        private set
    internal var siegeElapsedSeconds = 0f
    internal var lodAccumulatedSeconds = 0f
    internal var lodBucket = 0
    internal var hasLoggedFirstAcidProjectile = false
    private var slowMultiplier = 1f
    private var slowRemainingSeconds = 0f
    private var stunRemainingSeconds = 0f
    private var burnDamagePerSecond = 0f
    private var burnRemainingSeconds = 0f
    private var killRewardClaimed = false
    private var igniteOnDeath = false

    internal fun activate(
        hpMultiplier: Float,
        moveSpeedMultiplier: Float,
        spawnX: Float,
        spawnZ: Float,
        targetWallX: Float,
        targetWallZ: Float,
        groundHeight: Float,
    ) {
        id = UUID.randomUUID().toString()
        maxHp = config.baseHp * hpMultiplier
        currentHp = maxHp
        effectiveMoveSpeed = GameplayTuning.monsterMoveSpeed(config.moveSpeed, moveSpeedMultiplier)
        x = spawnX
        z = spawnZ
        wallTargetX = targetWallX
        wallTargetZ = targetWallZ
        actionState = MonsterActionState.MOVING
        siegeElapsedSeconds = 0f
        lodAccumulatedSeconds = 0f
        lodBucket = (id.hashCode() and Int.MAX_VALUE) % PerformanceTuning.FAR_MONSTER_UPDATE_STRIDE
        hasLoggedFirstAcidProjectile = false
        active = true
        slowMultiplier = 1f
        slowRemainingSeconds = 0f
        stunRemainingSeconds = 0f
        burnDamagePerSecond = 0f
        burnRemainingSeconds = 0f
        killRewardClaimed = false
        igniteOnDeath = false
        entity.enabled = true
        setGroundedPosition(groundHeight)
    }

    internal fun moveTo(nextX: Float, nextZ: Float, targetX: Float, targetZ: Float, groundHeight: Float) {
        x = nextX
        z = nextZ
        setGroundedPosition(groundHeight)
        val from = Vector3(x, groundHeight, z)
        val target = Vector3(targetX, groundHeight, targetZ)
        if (kotlin.math.abs(targetX - x) + kotlin.math.abs(targetZ - z) > 0.0001f) {
            entity.components[TransformComponent::class.java]?.setQuaternion(
                (Matrix4.lookAt(from, target, Vector3(0f, 1f, 0f)).inverse() *
                    Matrix4.rotateYByDegrees(180f)).rotation,
            )
        }
    }

    internal fun beginSiege(groundHeight: Float) {
        actionState = MonsterActionState.SIEGING
        setGroundedPosition(groundHeight)
    }

    internal fun lockToGround(groundHeight: Float) {
        setGroundedPosition(groundHeight)
    }

    internal fun recycle() {
        active = false
        actionState = MonsterActionState.DEAD
        siegeElapsedSeconds = 0f
        lodAccumulatedSeconds = 0f
        hasLoggedFirstAcidProjectile = false
        wallTargetX = 0f
        wallTargetZ = 0f
        entity.enabled = false
        slowMultiplier = 1f
        slowRemainingSeconds = 0f
        stunRemainingSeconds = 0f
        burnDamagePerSecond = 0f
        burnRemainingSeconds = 0f
        killRewardClaimed = false
        igniteOnDeath = false
    }

    override fun worldPosition(): Vector3 =
        Vector3(
            x,
            GameplayTuning.groundedBaseY(SceneLayoutManager.getCommittedGroundHeight()) +
                visualHeight * 0.5f,
            z,
        )

    override fun isAlive(): Boolean = active && actionState != MonsterActionState.DEAD && currentHp > 0f

    internal fun currentMoveSpeed(): Float = if (stunRemainingSeconds > 0f) 0f else effectiveMoveSpeed * slowMultiplier

    internal fun canAttack(): Boolean = stunRemainingSeconds <= 0f

    internal fun advanceStatus(deltaTimeSeconds: Float): Boolean {
        if (!isAlive() || deltaTimeSeconds <= 0f) return false
        slowRemainingSeconds = (slowRemainingSeconds - deltaTimeSeconds).coerceAtLeast(0f)
        if (slowRemainingSeconds == 0f) slowMultiplier = 1f
        stunRemainingSeconds = (stunRemainingSeconds - deltaTimeSeconds).coerceAtLeast(0f)
        if (burnRemainingSeconds <= 0f || burnDamagePerSecond <= 0f) return false
        val appliedTime = minOf(deltaTimeSeconds, burnRemainingSeconds)
        burnRemainingSeconds = (burnRemainingSeconds - deltaTimeSeconds).coerceAtLeast(0f)
        val killed = applyDamage(burnDamagePerSecond * appliedTime).wasKilled
        if (burnRemainingSeconds == 0f) burnDamagePerSecond = 0f
        return killed
    }

    @Synchronized
    override fun applySlow(speedMultiplier: Float, durationSeconds: Float) {
        if (!isAlive() || speedMultiplier !in 0f..1f || durationSeconds <= 0f) return
        slowMultiplier = minOf(slowMultiplier, speedMultiplier)
        slowRemainingSeconds = maxOf(slowRemainingSeconds, durationSeconds)
    }

    @Synchronized
    override fun applyStun(durationSeconds: Float) {
        if (!isAlive() || durationSeconds <= 0f) return
        stunRemainingSeconds = maxOf(stunRemainingSeconds, durationSeconds)
    }

    @Synchronized
    override fun applyBurn(
        damagePerSecond: Float,
        durationSeconds: Float,
        speedMultiplier: Float,
        igniteOnKill: Boolean,
    ) {
        if (!isAlive() || damagePerSecond <= 0f || durationSeconds <= 0f) return
        burnDamagePerSecond += damagePerSecond
        burnRemainingSeconds = maxOf(burnRemainingSeconds, durationSeconds)
        applySlow(speedMultiplier, durationSeconds)
        igniteOnDeath = igniteOnDeath || igniteOnKill
    }

    @Synchronized
    override fun claimKillReward(): Boolean {
        if (killRewardClaimed) return false
        killRewardClaimed = true
        return true
    }

    @Synchronized
    override fun consumeIgniteOnDeath(): Boolean {
        val result = igniteOnDeath
        igniteOnDeath = false
        return result
    }

    @Synchronized
    override fun applyDamage(damage: Float): MonsterDamageResult {
        if (damage <= 0f || !isAlive()) return MonsterDamageResult(0f, currentHp, false)
        val previousHp = currentHp
        currentHp = (currentHp - damage).coerceAtLeast(0f)
        val killed = previousHp > 0f && currentHp <= 0f
        if (killed) actionState = MonsterActionState.DEAD
        return MonsterDamageResult(
            appliedDamage = previousHp - currentHp,
            remainingHp = currentHp,
            wasKilled = killed,
        )
    }

    internal fun toRuntimeState(groundHeight: Float): MonsterRuntimeState =
        MonsterRuntimeState(
            id = id,
            type = type,
            currentHp = currentHp,
            maxHp = maxHp,
            moveSpeed = effectiveMoveSpeed,
            x = x,
            y = groundHeight,
            z = z,
            actionState = actionState,
        )

    private fun setGroundedPosition(groundHeight: Float) {
        // The logical/root origin remains at the monster's feet for its entire lifetime.
        entity.components[TransformComponent::class.java]?.setPosition(
            Vector3(x, GameplayTuning.groundedBaseY(groundHeight), z),
        )
    }
}
