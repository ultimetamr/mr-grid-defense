package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.MonsterActionState
import com.picoxr.mrspacetowerdefense.model.MonsterBehavior
import com.picoxr.mrspacetowerdefense.model.MonsterPoolStats
import com.picoxr.mrspacetowerdefense.model.MonsterRuntimeState
import com.picoxr.mrspacetowerdefense.model.MonsterType
import com.picoxr.mrspacetowerdefense.model.ScenePoint
import com.picoxr.mrspacetowerdefense.model.WaveConfig
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MonsterManager : BaseManager() {
    private const val TAG = "MonsterManager"
    private const val STATE_PUBLISH_INTERVAL_SECONDS = 0.1f
    private const val MOVEMENT_EPSILON_METERS = 0.0001f
    const val MAX_ACTIVE_MONSTERS = 25

    private val stateLock = Any()
    private val activeMonsters = linkedMapOf<String, BaseMonster>()
    private val mutableMonsters = MutableStateFlow<List<MonsterRuntimeState>>(emptyList())

    private var hostRoot: Entity? = null
    private var modelLibrary: MonsterModelLibrary? = null
    private var pool: MonsterPool? = null
    private var acidProjectilePool: AcidProjectilePool? = null
    private var listener: MonsterLifecycleListener? = null
    private var publishElapsedSeconds = 0f
    private var combatWasActive = false
    private var frameIndex = 0L
    private var spawnOrdinal = 0
    private val navigationOutput = FloatArray(2)
    private val cleanupGate = MonsterCleanupGate()
    private val acidImpactHandler: (PooledAcidProjectile) -> Unit = ::resolveAcidImpactLocked

    val monsters: StateFlow<List<MonsterRuntimeState>> = mutableMonsters.asStateFlow()

    override fun onInitialize(applicationContext: Context) = Unit

    internal fun attach(root: Entity, models: MonsterModelLibrary) = synchronized(stateLock) {
        if (hostRoot === root && pool != null && acidProjectilePool != null) {
            models.destroy()
            return@synchronized
        }
        detachLocked()
        hostRoot = root
        modelLibrary = models
        models.attach(root)
        pool = MonsterPool(root, models)
        acidProjectilePool = AcidProjectilePool(root)
        Log.i(TAG, "Monster pool attached with ${models.loadedCount()} GLB templates")
    }

    fun detach() = synchronized(stateLock) { detachLocked() }

    fun getPoolStats(): MonsterPoolStats = synchronized(stateLock) {
        pool?.stats() ?: MonsterPoolStats(0, 0, MonsterPool.DEFAULT_MAX_MONSTERS)
    }

    internal fun setLifecycleListener(value: MonsterLifecycleListener?) = synchronized(stateLock) {
        listener = value
    }

    internal fun spawn(type: MonsterType, wave: WaveConfig): Boolean = synchronized(stateLock) {
        if (!GameManager.isCombatSimulationActive()) return@synchronized false
        if (activeMonsters.size >= MAX_ACTIVE_MONSTERS) return@synchronized false
        val layout = SceneLayoutManager.layout.value ?: return@synchronized false
        val monster = pool?.acquire(type) ?: return@synchronized false
        val boundary = layout.monsterSpawnBoundary
        val boundaryToWallX = boundary.center.x - layout.wall.center.x
        val boundaryToWallZ = boundary.center.z - layout.wall.center.z
        val boundaryToWallDistance =
            sqrt(boundaryToWallX * boundaryToWallX + boundaryToWallZ * boundaryToWallZ)
        val maxDepthRetreat =
            (boundaryToWallDistance - layout.wall.depth / 2f - 0.35f)
                .coerceIn(0f, MonsterSpawnRules.MAX_DEPTH_RETREAT_METERS)
        var spawnX: Float? = null
        var spawnZ: Float? = null
        var collisionFallbackX: Float? = null
        var collisionFallbackZ: Float? = null
        for (attempt in 0 until MonsterSpawnRules.CANDIDATE_COUNT) {
            val candidate =
                MonsterSpawnRules.candidate(
                    boundary = boundary,
                    spawnOrdinal = wave.waveIndex * 1_000 + spawnOrdinal,
                    attemptIndex = attempt,
                    maxDepthRetreatMeters = maxDepthRetreat,
                )
            if (!hasCollisionClearance(monster, candidate.x, candidate.z)) continue
            if (collisionFallbackX == null) {
                collisionFallbackX = candidate.x
                collisionFallbackZ = candidate.z
            }
            if (!hasPreferredSpawnClearance(monster, candidate.x, candidate.z)) continue
            spawnX = candidate.x
            spawnZ = candidate.z
            break
        }
        if (spawnX == null && collisionFallbackX != null && collisionFallbackZ != null) {
            // Preserve physical non-overlap but relax the preferred 0.30 m presentation
            // spacing so a crowded spawn band cannot starve the rest of the wave.
            spawnX = collisionFallbackX
            spawnZ = collisionFallbackZ
            Log.w(TAG, "Spawn band crowded; using collision-safe fallback position")
        }
        if (spawnX == null || spawnZ == null) {
            pool?.release(monster)
            return@synchronized false
        }
        val wallTarget =
            MonsterSpawnRules.wallTargetForSpawn(
                wall = layout.wall,
                spawnBoundary = boundary,
                spawnPoint = ScenePoint(spawnX, boundary.center.y, spawnZ),
                edgePaddingMeters = monster.hitRadius + 0.04f,
            )
        monster.activate(
            hpMultiplier = wave.hpMultiplier,
            moveSpeedMultiplier = wave.moveSpeedMultiplier,
            spawnX = spawnX,
            spawnZ = spawnZ,
            targetWallX = wallTarget.x,
            targetWallZ = wallTarget.z,
            groundHeight = SceneLayoutManager.getCommittedGroundHeight(),
        )
        spawnOrdinal += 1
        activeMonsters[monster.id] = monster
        TowerManager.registerMonster(monster)
        Log.d(
            TAG,
            "Spawned wave=${wave.waveIndex} ordinal=$spawnOrdinal type=$type " +
                "at=($spawnX,$spawnZ) target=(${wallTarget.x},${wallTarget.z})",
        )
        publishLocked()
        true
    }

    /** Invoked once per Spatial ECS frame by [MonsterCombatSystem]. */
    fun onFrame(deltaTimeSeconds: Float) = synchronized(stateLock) {
        if (GameStateManager.state.value != GameState.FIGHTING) {
            if (combatWasActive && activeMonsters.isNotEmpty()) releaseAllLocked()
            combatWasActive = false
            return
        }
        if (!GameManager.isCombatSimulationActive()) return
        combatWasActive = true
        val delta = deltaTimeSeconds.coerceIn(0f, 0.1f)
        if (delta <= 0f) return
        frameIndex++
        cleanupGate.beginFrame()
        try {
            updateAcidProjectilesLocked(delta)
            if (GameStateManager.state.value != GameState.FIGHTING) return
            recycleKilledLocked()
            if (activeMonsters.isEmpty()) {
                publishElapsedSeconds += delta
                return
            }

            val layout = SceneLayoutManager.layout.value ?: return
            val groundHeight = SceneLayoutManager.getCommittedGroundHeight()
            val activeCount = activeMonsters.size
            for (monster in activeMonsters.values) {
                // A wall-break/wave-complete event may transition state re-entrantly.
                // Cleanup is deferred by cleanupGate; stop consuming this frame immediately.
                if (GameStateManager.state.value != GameState.FIGHTING) break
                if (!monster.isAlive()) continue
                if (monster.advanceStatus(delta)) {
                    TowerManager.onExternalMonsterKilled(monster)
                    continue
                }
                when (monster.actionState) {
                    MonsterActionState.MOVING -> {
                        val wallX = monster.wallTargetX
                        val wallZ = monster.wallTargetZ
                        monster.lodAccumulatedSeconds += delta
                        if (!PerformanceTuning.shouldUpdateMonster(
                                activeCount = activeCount,
                                distanceSquaredToPlayer = GameManager.playerDistanceSquared(monster.x, monster.z),
                                frameIndex = frameIndex,
                                bucket = monster.lodBucket,
                            )
                        ) continue
                        val movementDelta = monster.lodAccumulatedSeconds.coerceAtMost(0.15f)
                        monster.lodAccumulatedSeconds = 0f
                        val dx = wallX - monster.x
                        val dz = wallZ - monster.z
                        val distanceToWall = sqrt(dx * dx + dz * dz)
                        val stopDistance =
                            MonsterAttackRules.stopDistance(
                                config = monster.config,
                                hitRadius = monster.hitRadius,
                                wallDepth = layout.wall.depth,
                            )
                        if (distanceToWall <= stopDistance) {
                            if (monster.config.behavior == MonsterBehavior.SELF_DESTRUCT) {
                                val result = WallManager.takeDamage(monster.config.selfDestructDamage.toFloat())
                                if (result.reflectedDamage > 0f) monster.applyDamage(result.reflectedDamage)
                                monster.applyDamage(monster.maxHp)
                            } else {
                                monster.beginSiege(groundHeight)
                            }
                        } else {
                            val moveDistance =
                                minOf(
                                    monster.currentMoveSpeed() * movementDelta,
                                    distanceToWall - stopDistance,
                                )
                            if (moveDistance <= MOVEMENT_EPSILON_METERS) {
                                monster.lockToGround(groundHeight)
                                continue
                            }
                            // Gameplay intentionally ignores detected furniture and other
                            // monster bodies: every attacker advances directly to its wall lane.
                            MonsterNavigation.forceProgressInto(
                                x = monster.x,
                                z = monster.z,
                                targetX = wallX,
                                targetZ = wallZ,
                                distance = moveDistance,
                                output = navigationOutput,
                            )
                            monster.moveTo(navigationOutput[0], navigationOutput[1], wallX, wallZ, groundHeight)
                        }
                    }

                    MonsterActionState.SIEGING -> {
                        monster.lockToGround(groundHeight)
                        if (!monster.canAttack()) continue
                        monster.siegeElapsedSeconds += delta
                        while (
                            monster.siegeElapsedSeconds >= monster.config.attackIntervalSeconds &&
                                WallManager.wallState.value.currentHp > 0
                        ) {
                            if (MonsterAttackRules.attacksWithProjectile(monster.config)) {
                                if (!launchAcidProjectileLocked(monster, layout.wall.center.y, layout.wallHeight)) {
                                    monster.siegeElapsedSeconds = monster.config.attackIntervalSeconds
                                    break
                                }
                                monster.siegeElapsedSeconds -= monster.config.attackIntervalSeconds
                                continue
                            }
                            monster.siegeElapsedSeconds -= monster.config.attackIntervalSeconds
                            val result = WallManager.takeDamage(monster.config.siegeDamage.toFloat())
                            if (result.reflectedDamage > 0f) {
                                val killed = monster.applyDamage(result.reflectedDamage).wasKilled
                                if (killed) TowerManager.onExternalMonsterKilled(monster)
                            }
                        }
                    }

                    MonsterActionState.DEAD -> Unit
                }
            }
            publishElapsedSeconds += delta
            if (publishElapsedSeconds >= STATE_PUBLISH_INTERVAL_SECONDS) publishLocked()
        } finally {
            applyDeferredCleanupLocked(cleanupGate.endFrame())
        }
    }

    private fun launchAcidProjectileLocked(
        monster: BaseMonster,
        wallGroundY: Float,
        wallHeight: Float,
    ): Boolean {
        val launchY = wallGroundY + maxOf(monster.visualHeight * 0.65f, 0.08f)
        val launched = acidProjectilePool?.launch(
            sourceMonsterId = monster.id,
            startX = monster.x,
            startY = launchY,
            startZ = monster.z,
            targetX = monster.wallTargetX,
            targetY = wallGroundY + wallHeight * 0.5f,
            targetZ = monster.wallTargetZ,
            damage = monster.config.siegeDamage.toFloat(),
        ) == true
        if (launched && !monster.hasLoggedFirstAcidProjectile) {
            monster.hasLoggedFirstAcidProjectile = true
            Log.i(
                TAG,
                "First acid shell launched id=${monster.id} speed=" +
                    AcidProjectilePool.PROJECTILE_SPEED_METERS_PER_SECOND,
            )
        }
        return launched
    }

    private fun updateAcidProjectilesLocked(deltaTime: Float) {
        acidProjectilePool?.update(deltaTime, acidImpactHandler)
    }

    private fun resolveAcidImpactLocked(projectile: PooledAcidProjectile) {
        if (WallManager.wallState.value.currentHp <= 0) return
        val result = WallManager.takeDamage(projectile.damage)
        if (result.reflectedDamage <= 0f) return
        val source = projectile.sourceId?.let(activeMonsters::get) ?: return
        if (source.applyDamage(result.reflectedDamage).wasKilled) {
            TowerManager.onExternalMonsterKilled(source)
        }
    }

    private fun recycleKilledLocked() {
        var changed = false
        val iterator = activeMonsters.entries.iterator()
        while (iterator.hasNext()) {
            val monster = iterator.next().value
            if (monster.isAlive()) continue
            iterator.remove()
            TowerManager.unregisterMonster(monster.id)
            pool?.release(monster)
            listener?.onMonsterKilled(monster.id, monster.type)
            changed = true
        }
        if (changed) publishLocked()
    }

    private fun hasPreferredSpawnClearance(monster: BaseMonster, x: Float, z: Float): Boolean {
        return activeMonsters.values.none { other ->
            if (other === monster || !other.active) return@none false
            val dx = x - other.x
            val dz = z - other.z
            val collisionDistance =
                monster.hitRadius + other.hitRadius + GameplayTuning.MIN_MONSTER_GAP_METERS
            val requiredDistance =
                maxOf(MonsterSpawnRules.MIN_SPAWN_CENTER_DISTANCE_METERS, collisionDistance)
            dx * dx + dz * dz < requiredDistance * requiredDistance
        }
    }

    private fun hasCollisionClearance(monster: BaseMonster, x: Float, z: Float): Boolean {
        return activeMonsters.values.none { other ->
            other !== monster &&
                other.active &&
                GameplayTuning.circlesOverlap(
                    firstX = x,
                    firstZ = z,
                    firstRadius = monster.hitRadius,
                    secondX = other.x,
                    secondZ = other.z,
                    secondRadius = other.hitRadius,
                )
        }
    }

    private fun publishLocked() {
        val groundHeight = SceneLayoutManager.getCommittedGroundHeight()
        mutableMonsters.value = activeMonsters.values.map { it.toRuntimeState(groundHeight) }
        publishElapsedSeconds = 0f
    }

    private fun releaseAllLocked() {
        acidProjectilePool?.releaseAll()
        activeMonsters.values.forEach { monster ->
            TowerManager.unregisterMonster(monster.id)
            pool?.release(monster)
        }
        activeMonsters.clear()
        publishLocked()
    }

    internal fun pauseAndRecycleWave() = synchronized(stateLock) {
        if (cleanupGate.deferIfFrameActive(MonsterCleanupAction.RECYCLE_WAVE)) return@synchronized
        releaseAllLocked()
        combatWasActive = false
    }

    internal fun resetSession() = synchronized(stateLock) {
        if (pool == null) {
            val root = hostRoot
            val models = modelLibrary
            if (root != null && models != null) {
                pool = MonsterPool(root, models)
                acidProjectilePool = AcidProjectilePool(root)
            }
        }
        releaseAllLocked()
        combatWasActive = false
        publishElapsedSeconds = 0f
        frameIndex = 0L
        spawnOrdinal = 0
    }

    internal fun releaseBattleResources() = synchronized(stateLock) {
        if (cleanupGate.deferIfFrameActive(MonsterCleanupAction.RELEASE_BATTLE_RESOURCES)) {
            return@synchronized
        }
        releaseBattleResourcesNowLocked()
    }

    private fun releaseBattleResourcesNowLocked() {
        releaseAllLocked()
        pool?.destroy()
        pool = null
        acidProjectilePool?.destroy()
        acidProjectilePool = null
        combatWasActive = false
    }

    private fun applyDeferredCleanupLocked(action: MonsterCleanupAction) {
        when (action) {
            MonsterCleanupAction.NONE -> Unit
            MonsterCleanupAction.RECYCLE_WAVE -> {
                releaseAllLocked()
                combatWasActive = false
            }
            MonsterCleanupAction.RELEASE_BATTLE_RESOURCES -> releaseBattleResourcesNowLocked()
        }
    }

    private fun detachLocked() {
        cleanupGate.reset()
        releaseAllLocked()
        pool?.destroy()
        pool = null
        acidProjectilePool?.destroy()
        acidProjectilePool = null
        modelLibrary?.destroy()
        modelLibrary = null
        hostRoot = null
        combatWasActive = false
        spawnOrdinal = 0
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            detachLocked()
        }
    }
}

internal interface MonsterLifecycleListener {
    fun onMonsterKilled(monsterId: String, type: MonsterType)
}
