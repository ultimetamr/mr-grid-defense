package com.picoxr.mrspacetowerdefense.manager

import android.content.Context
import android.util.Log
import com.picoxr.mrspacetowerdefense.BuildConfig
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Matrix4
import com.pico.spatial.core.math.Vector3
import com.picoxr.mrspacetowerdefense.event.EventBus
import com.picoxr.mrspacetowerdefense.event.MonsterKilledEvent
import com.picoxr.mrspacetowerdefense.event.TowerPlacedEvent
import com.picoxr.mrspacetowerdefense.event.TowerUpgradedEvent
import com.picoxr.mrspacetowerdefense.model.GameState
import com.picoxr.mrspacetowerdefense.model.MonsterCombatTarget
import com.picoxr.mrspacetowerdefense.model.PlayerState
import com.picoxr.mrspacetowerdefense.model.SceneLayout
import com.picoxr.mrspacetowerdefense.model.TowerPlacementPreviewState
import com.picoxr.mrspacetowerdefense.model.TowerPlacementRejectReason
import com.picoxr.mrspacetowerdefense.model.TowerPlacementResult
import com.picoxr.mrspacetowerdefense.model.TowerRuntimeState
import com.picoxr.mrspacetowerdefense.model.TowerType
import com.picoxr.mrspacetowerdefense.model.TowerUpgradeRejectReason
import com.picoxr.mrspacetowerdefense.model.TowerUpgradeResult
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Owns tower placement/economy and the per-frame tower/projectile combat simulation. */
object TowerManager : BaseManager() {
    private const val TAG = "TowerManager"
    const val MAX_WALL_WEAPONS = SceneLayoutCalculator.WALL_WEAPON_SLOT_COUNT
    private const val MIN_TOWER_SPACING = 0.45f
    private const val PROJECTILE_HIT_PADDING = 0.035f

    private val stateLock = Any()
    private val towerInstances = linkedMapOf<String, TowerInstance>()
    private val monsterTargets = linkedMapOf<String, MonsterCombatTarget>()
    private val mutableTowers = MutableStateFlow<List<TowerRuntimeState>>(emptyList())
    private val mutablePreview = MutableStateFlow(TowerPlacementPreviewState())
    private val mutablePlayerState =
        MutableStateFlow(PlayerState(goldCoin = GoldManager.INITIAL_GOLD, currentSafeGridIndex = 4, isAlive = true))

    private var hostRoot: Entity? = null
    private var towerRoot: Entity? = null
    private var projectilePool: ProjectilePool? = null
    private var previewEntity: Entity? = null
    private var previewMaterial: UnlitMaterial? = null
    private var selectedTowerType: TowerType? = null
    private var combatWasActive = false
    private var goldSyncJob: Job? = null
    private var layoutSyncJob: Job? = null
    private var towerPublishElapsedSeconds = 0f
    private var towerPublishDirty = false
    private var sharedPrimitiveMesh: MeshResource? = null
    private val bodyMaterials = linkedMapOf<TowerType, UnlitMaterial>()

    val towers: StateFlow<List<TowerRuntimeState>> = mutableTowers.asStateFlow()
    val preview: StateFlow<TowerPlacementPreviewState> = mutablePreview.asStateFlow()
    val playerState: StateFlow<PlayerState> = mutablePlayerState.asStateFlow()

    fun getProjectilePoolStats(): ProjectilePoolStats = synchronized(stateLock) {
        projectilePool?.stats() ?: ProjectilePoolStats(0, 0, ProjectilePool.DEFAULT_MAX_PROJECTILES)
    }

    override fun onInitialize(applicationContext: Context) {
        goldSyncJob =
            managerScope.launch {
                GoldManager.gold.collectLatest { gold ->
                    synchronized(stateLock) {
                        mutablePlayerState.value = mutablePlayerState.value.copy(goldCoin = gold)
                        refreshPreviewAffordabilityLocked()
                    }
                }
            }
    }

    fun attach(root: Entity) = synchronized(stateLock) {
        if (hostRoot === root && towerRoot?.valid == true) return@synchronized
        layoutSyncJob?.cancel()
        layoutSyncJob = null
        detachLocked()
        hostRoot = root
        towerRoot = Entity().apply {
            setName("TowerRuntimeRoot")
            enabled = GameStateManager.state.value != GameState.IDLE
        }.also(root::addChild)
        projectilePool = ProjectilePool(checkNotNull(towerRoot))
        prewarmSharedTowerResourcesLocked()
        selectedTowerType?.let(::rebuildPreviewLocked)
        layoutSyncJob =
            managerScope.launch {
                SceneLayoutManager.layout.collectLatest { layout ->
                    if (layout != null) synchronized(stateLock) { relocateToLayoutLocked(layout) }
                }
            }
    }

    fun detach() = synchronized(stateLock) {
        layoutSyncJob?.cancel()
        layoutSyncJob = null
        detachLocked()
    }

    internal fun setSceneVisible(visible: Boolean) = synchronized(stateLock) {
        towerRoot?.enabled = visible
    }

    fun configurePlayerState(state: PlayerState) {
        synchronized(stateLock) {
            mutablePlayerState.value = state.copy(goldCoin = state.goldCoin.coerceAtLeast(0))
        }
        GoldManager.setCurrentGold(state.goldCoin, "player_configured")
    }

    fun addGold(amount: Int, reason: String) {
        GoldManager.addGold(amount, reason)
    }

    internal fun updateSafeGridIndex(index: Int) = synchronized(stateLock) {
        require(index in 0..8)
        mutablePlayerState.value = mutablePlayerState.value.copy(currentSafeGridIndex = index)
    }

    internal fun markPlayerDead(): Boolean = synchronized(stateLock) {
        val previous = mutablePlayerState.value
        if (!previous.isAlive) return@synchronized false
        mutablePlayerState.value = previous.copy(isAlive = false)
        true
    }

    fun selectTowerType(type: TowerType?) = synchronized(stateLock) {
        selectedTowerType = type
        if (type == null) {
            previewEntity?.enabled = false
            mutablePreview.value = TowerPlacementPreviewState()
        } else {
            rebuildPreviewLocked(type)
            mutablePreview.value = TowerPlacementPreviewState(selectedType = type)
        }
    }

    /** Called by the controller/hand ray layer with its current hit point in scene-root meters. */
    fun updatePlacementRay(hitPosition: Vector3?) = synchronized(stateLock) {
        val type = selectedTowerType
        val layout = SceneLayoutManager.layout.value
        if (!TowerActionRules.canPlace(GameStateManager.state.value) ||
            type == null || layout == null || hitPosition == null
        ) {
            previewEntity?.enabled = false
            mutablePreview.value = TowerPlacementPreviewState(selectedType = type)
            return@synchronized
        }

        val mountIndex = nextFreeWallMountIndexLocked()
        val mount = mountIndex?.let(layout.wallWeaponMounts::getOrNull)
        val affordable = GoldManager.getCurrentGold() >= TowerCatalog.get(type).cost
        val blocked = mount == null
        val visible = mount != null
        mutablePreview.value =
            TowerPlacementPreviewState(
                selectedType = type,
                x = mount?.x ?: 0f,
                z = mount?.z ?: 0f,
                isVisible = visible,
                isInsidePlacementZone = visible,
                isAffordable = affordable,
                isBlocked = blocked,
            )
        previewEntity?.apply {
            enabled = visible
            if (mount != null) {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(mount.x, GameplayTuning.groundedBaseY(mount.y), mount.z))
                    setQuaternion(
                        Matrix4.rotateYByDegrees(
                            Math.toDegrees(layout.wall.rotationYRadians.toDouble()).toFloat(),
                        ).rotation,
                    )
                }
            }
        }
        val validColor = affordable && !blocked
        previewMaterial?.setBaseColor(
            if (validColor) Color4(0.15f, 0.95f, 0.35f, 1f)
            else Color4(1f, 0.08f, 0.08f, 1f),
        )
    }

    /** Compatibility entry point for controller confirmation; placement is now slot-based. */
    fun confirmPlacement(): TowerPlacementResult = synchronized(stateLock) {
        val type = selectedTowerType
            ?: return@synchronized TowerPlacementResult.Rejected(
                TowerPlacementRejectReason.NO_TOWER_SELECTED,
            )
        autoPlaceTowerLocked(type)
    }

    /** One shop click atomically buys and mounts the weapon on the next free wall slot. */
    fun purchaseAndAutoPlace(type: TowerType): TowerPlacementResult = synchronized(stateLock) {
        autoPlaceTowerLocked(type)
    }

    private fun autoPlaceTowerLocked(type: TowerType): TowerPlacementResult {
        if (!TowerActionRules.canPlace(GameStateManager.state.value)) {
            return TowerPlacementResult.Rejected(
                TowerPlacementRejectReason.NOT_PLACEMENT_PHASE,
            )
        }
        val config = TowerCatalog.get(type)
        val layout = SceneLayoutManager.layout.value
            ?: return TowerPlacementResult.Rejected(TowerPlacementRejectReason.SCENE_NOT_READY)
        if (towerRoot == null) {
            return TowerPlacementResult.Rejected(
                TowerPlacementRejectReason.SCENE_NOT_READY,
            )
        }
        val mountIndex = nextFreeWallMountIndexLocked()
        if (mountIndex == null || mountIndex !in layout.wallWeaponMounts.indices) {
            return TowerPlacementResult.Rejected(TowerPlacementRejectReason.WALL_SLOTS_FULL)
        }
        if (GoldManager.getCurrentGold() < config.cost) {
            return TowerPlacementResult.Rejected(
                TowerPlacementRejectReason.INSUFFICIENT_GOLD,
            )
        }
        val mount = layout.wallWeaponMounts[mountIndex]
        val id = UUID.randomUUID().toString()
        val entity =
            createTowerEntityLocked(
                id,
                type,
                mount.x,
                GameplayTuning.groundedBaseY(mount.y),
                mount.z,
                layout.wall.rotationYRadians,
            )
        if (!GoldManager.costGold(config.cost, "wall_mount_${type.name.lowercase()}")) {
            entity.root.destroy()
            entity.glowMaterial.close()
            refreshPreviewAffordabilityLocked()
            return TowerPlacementResult.Rejected(
                TowerPlacementRejectReason.INSUFFICIENT_GOLD,
            )
        }
        val instance =
            TowerInstance(
                id = id,
                type = type,
                x = mount.x,
                baseY = GameplayTuning.groundedBaseY(mount.y),
                z = mount.z,
                wallYawRadians = layout.wall.rotationYRadians,
                wallMountIndex = mountIndex,
                entity = entity.root,
                turret = entity.turret,
                glow = entity.glow,
                glowMaterial = entity.glowMaterial,
            )
        towerInstances[id] = instance
        check(layout.wall.containsHorizontal(instance.x, instance.z)) {
            "Tower $id was not created on the calibrated wall footprint"
        }
        publishTowersLocked()
        previewEntity?.enabled = false
        mutablePreview.value = TowerPlacementPreviewState(selectedType = type)
        val placed = instance.toState()
        EventBus.post(
            TowerPlacedEvent(
                towerId = id,
                towerType = type,
                towerLevel = 1,
                x = mount.x,
                z = mount.z,
            ),
        )
        return TowerPlacementResult.Success(placed)
    }

    fun upgradeTower(towerId: String): TowerUpgradeResult = synchronized(stateLock) {
        if (!TowerActionRules.canUpgrade(GameStateManager.state.value)) {
            return@synchronized TowerUpgradeResult.Rejected(
                TowerUpgradeRejectReason.NOT_WAVE_PAUSE,
            )
        }
        val tower = towerInstances[towerId]
            ?: return@synchronized TowerUpgradeResult.Rejected(
                TowerUpgradeRejectReason.TOWER_NOT_FOUND,
            )
        val config = TowerCatalog.get(tower.type)
        if (tower.level >= config.maxLevel) {
            return@synchronized TowerUpgradeResult.Rejected(TowerUpgradeRejectReason.MAX_LEVEL)
        }
        val cost = TowerMath.upgradeCost(config, tower.level)
        if (GoldManager.getCurrentGold() < cost) {
            return@synchronized TowerUpgradeResult.Rejected(
                TowerUpgradeRejectReason.INSUFFICIENT_GOLD,
            )
        }
        if (!GoldManager.costGold(cost, "upgrade_${tower.type.name.lowercase()}_${tower.level + 1}")) {
            return@synchronized TowerUpgradeResult.Rejected(
                TowerUpgradeRejectReason.INSUFFICIENT_GOLD,
            )
        }
        val previousLevel = tower.level
        tower.level++
        tower.cooldownSeconds = minOf(tower.cooldownSeconds, effectiveAttackIntervalLocked(tower))
        tower.glow.enabled = true
        val glowStrength = 0.25f + tower.level * 0.1f
        tower.glowMaterial.apply {
            setBaseColor(Color4(1f, 0.72f, 0.12f, 1f))
            setEmissiveColor(Color4(glowStrength, glowStrength * 0.65f, 0.04f, 1f))
            setOpacity((0.12f + tower.level * 0.04f).coerceAtMost(0.34f))
        }
        publishTowersLocked()
        EventBus.post(
            TowerUpgradedEvent(
                towerId = tower.id,
                towerType = tower.type,
                previousLevel = previousLevel,
                currentLevel = tower.level,
            ),
        )
        TowerUpgradeResult.Success(tower.toState())
    }

    fun registerMonster(target: MonsterCombatTarget) = synchronized(stateLock) {
        require(target.id.isNotBlank()) { "Monster id cannot be blank" }
        require(target.hitRadius > 0f) { "Monster hit radius must be positive" }
        monsterTargets[target.id] = target
    }

    fun unregisterMonster(monsterId: String) = synchronized(stateLock) {
        monsterTargets.remove(monsterId)
    }

    /** Returns the closest placed tower intersected by a world-space ray. */
    fun findTowerAlongRay(origin: Vector3, direction: Vector3, maxDistance: Float = 10f): String? =
        synchronized(stateLock) {
            if (maxDistance <= 0f || direction.length() <= 0.0001f) return@synchronized null
            val ray = direction.normalize()
            towerInstances.values
                .mapNotNull { tower ->
                    val config = TowerCatalog.get(tower.type)
                    val center =
                        Vector3(
                            tower.x,
                            tower.baseY + GameplayTuning.modelSize(config.height * 0.5f),
                            tower.z,
                        )
                    val toCenter = center - origin
                    val distanceAlongRay = Vector3.dot(toCenter, ray)
                    if (distanceAlongRay !in 0f..maxDistance) return@mapNotNull null
                    val closest = origin + ray * distanceAlongRay
                    val perpendicularDistance = Vector3.distance(closest, center)
                    if (perpendicularDistance <= GameplayTuning.TOWER_INTERACTION_RADIUS_METERS) {
                        tower.id to distanceAlongRay
                    } else {
                        null
                    }
                }
                .minByOrNull { it.second }
                ?.first
        }

    fun towerIdForEntity(entity: Entity): String? = synchronized(stateLock) {
        towerInstances.values.firstOrNull { it.entity == entity }?.id
    }

    /** Invoked by [TowerCombatSystem] once per Spatial ECS frame. */
    fun onFrame(deltaTimeSeconds: Float) = synchronized(stateLock) {
        if (!TowerActionRules.isAutomaticCombatActive(GameStateManager.state.value)) {
            if (combatWasActive) {
                projectilePool?.releaseAll()
                combatWasActive = false
            }
            return
        }
        if (!GameManager.isCombatSimulationActive()) return
        combatWasActive = true
        if (deltaTimeSeconds <= 0f) return
        updateTowersLocked(deltaTimeSeconds.coerceAtMost(0.1f))
        updateProjectilesLocked(deltaTimeSeconds.coerceAtMost(0.1f))
        towerPublishElapsedSeconds += deltaTimeSeconds
        if (towerPublishDirty && towerPublishElapsedSeconds >= 0.1f) {
            publishTowersLocked()
            towerPublishDirty = false
            towerPublishElapsedSeconds = 0f
        }
    }

    private fun updateTowersLocked(deltaTime: Float) {
        var targetChanged = false
        towerInstances.values.forEach { tower ->
            tower.cooldownSeconds -= deltaTime
            if (!TowerActionRules.attacksWithProjectile(tower.type)) {
                tower.lockedTargetId = null
                return@forEach
            }
            val target = findNearestTargetLocked(tower)
            if (tower.lockedTargetId != target?.id) {
                tower.lockedTargetId = target?.id
                targetChanged = true
            }
            if (target == null) return@forEach
            val targetPosition = target.worldPosition()
            orientTurretLocked(tower, targetPosition)
            if (tower.cooldownSeconds <= 0f) {
                var fired = false
                repeat(TowerMath.projectileCount(TowerCatalog.get(tower.type), tower.level)) {
                    fired = launchProjectileLocked(tower, target, targetPosition) || fired
                }
                if (fired) {
                    tower.cooldownSeconds = effectiveAttackIntervalLocked(tower)
                }
            }
        }
        if (targetChanged) towerPublishDirty = true
    }

    private fun updateProjectilesLocked(deltaTime: Float) {
        val pool = projectilePool ?: return
        for (index in 0 until pool.size) {
            val projectile = pool.projectileAt(index)
            if (!projectile.active) continue
            val startX = projectile.x
            val startY = projectile.y
            val startZ = projectile.z
            val endX = startX + projectile.velocityX * deltaTime
            val endY = startY + projectile.velocityY * deltaTime
            val endZ = startZ + projectile.velocityZ * deltaTime
            val hit =
                monsterTargets.values.firstOrNull { target ->
                    if (!target.isAlive()) return@firstOrNull false
                    var alreadyHit = false
                    for (hitIndex in 0 until projectile.hitCount) {
                        if (projectile.hitTargetIds[hitIndex] == target.id) {
                            alreadyHit = true
                            break
                        }
                    }
                    if (alreadyHit) return@firstOrNull false
                    val targetPosition = target.worldPosition()
                    val hitRadius = target.hitRadius + PROJECTILE_HIT_PADDING
                    TowerMath.segmentPointDistanceSquared(
                        startX,
                        startY,
                        startZ,
                        endX,
                        endY,
                        endZ,
                        targetPosition.x,
                        targetPosition.y,
                        targetPosition.z,
                    ) <= hitRadius * hitRadius
                }
            if (hit != null) {
                resolveProjectileHitLocked(projectile, hit, hit.worldPosition())
                if (projectile.hitCount < projectile.hitTargetIds.size) {
                    projectile.hitTargetIds[projectile.hitCount++] = hit.id
                }
                projectile.remainingHits--
                if (projectile.remainingHits <= 0) {
                    pool.release(projectile)
                    continue
                }
            }

            projectile.x = endX
            projectile.y = endY
            projectile.z = endZ
            val traveledThisFrame = kotlin.math.sqrt(
                projectile.velocityX * projectile.velocityX +
                    projectile.velocityY * projectile.velocityY +
                    projectile.velocityZ * projectile.velocityZ,
            ) * deltaTime
            projectile.traveledDistance += traveledThisFrame
            projectile.entity.components[TransformComponent::class.java]?.setPosition(
                Vector3(endX, endY, endZ),
            )
            if (projectile.traveledDistance >= projectile.maxRange) pool.release(projectile)
        }
    }

    private fun launchProjectileLocked(
        tower: TowerInstance,
        target: MonsterCombatTarget,
        targetPosition: Vector3,
    ): Boolean {
        val projectile = projectilePool?.acquire(tower.type) ?: return false
        val config = TowerCatalog.get(tower.type)
        val launchY = tower.baseY + GameplayTuning.modelSize(config.height * 0.82f)
        val direction =
            TowerMath.normalizedDirection(
                tower.x,
                launchY,
                tower.z,
                targetPosition.x,
                targetPosition.y,
                targetPosition.z,
            )
        val speed =
            when (tower.type) {
                TowerType.ARCHER -> 6f
                TowerType.CROSSBOW -> 8f
                TowerType.CANNON -> 4f
                TowerType.BURN -> 5f
                TowerType.FROST -> 4f
                TowerType.TOTEM -> return false
            }
        projectile.apply {
            active = true
            x = tower.x
            y = launchY
            z = tower.z
            velocityX = direction.first * speed
            velocityY = direction.second * speed
            velocityZ = direction.third * speed
            damage = effectiveDamageLocked(tower)
            splashRadius = TowerMath.splashRadius(config, tower.level)
            maxRange = TowerMath.projectileTravelLimit(effectiveRangeLocked(tower))
            traveledDistance = 0f
            targetId = target.id
            sourceTowerId = tower.id
            towerLevel = tower.level
            remainingHits = TowerMath.penetrationCount(config, tower.level)
            hitCount = 0
            hitTargetIds.fill(null)
            entity.enabled = true
            entity.components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(x, y, z))
                setQuaternion(lookAtRotation(Vector3(x, y, z), targetPosition))
            }
        }
        if (BuildConfig.DEBUG && !tower.hasLoggedFirstProjectile) {
            tower.hasLoggedFirstProjectile = true
            Log.i(
                TAG,
                "First projectile launched tower=${tower.id} type=${tower.type} " +
                    "target=${target.id} speed=$speed maxTravel=${projectile.maxRange}",
            )
        }
        return true
    }

    private fun resolveProjectileHitLocked(
        projectile: PooledProjectile,
        directTarget: MonsterCombatTarget,
        impact: Vector3,
    ) {
        if (projectile.type != TowerType.CANNON) {
            val killed = damageTargetLocked(directTarget, projectile.damage)
            when (projectile.type) {
                TowerType.CROSSBOW -> TowerEffectRules.crossbowSlow(projectile.towerLevel)?.let {
                    directTarget.applySlow(it.speedMultiplier, it.durationSeconds)
                }
                TowerType.BURN -> {
                    val effect = TowerEffectRules.burn(projectile.towerLevel)
                    directTarget.applyBurn(
                        damagePerSecond = effect.damagePerSecond,
                        durationSeconds = effect.durationSeconds,
                        speedMultiplier = effect.speedMultiplier,
                        igniteOnKill = effect.igniteOnKill,
                    )
                }
                TowerType.FROST -> projectile.sourceTowerId
                    ?.let(towerInstances::get)
                    ?.let(::applyFrostPulseLocked)
                else -> Unit
            }
            if (killed && projectile.type == TowerType.BURN && projectile.towerLevel >= 5) {
                igniteNearbyLocked(impact, directTarget.id)
            }
            return
        }
        val radiusSquared = projectile.splashRadius * projectile.splashRadius
        monsterTargets.values.forEach { target ->
            if (!target.isAlive()) return@forEach
            val position = target.worldPosition()
            if (TowerMath.distanceSquared(
                    impact.x,
                    impact.y,
                    impact.z,
                    position.x,
                    position.y,
                    position.z,
                ) <= radiusSquared
            ) {
                damageTargetLocked(target, projectile.damage)
                val stunSeconds = TowerEffectRules.cannonStunSeconds(projectile.towerLevel)
                if (stunSeconds > 0f) target.applyStun(stunSeconds)
            }
        }
    }

    private fun damageTargetLocked(target: MonsterCombatTarget, damage: Float): Boolean {
        if (!target.isAlive()) return false
        val result = target.applyDamage(damage)
        if (result.wasKilled) {
            rewardKilledTargetLocked(target)
        }
        return result.wasKilled
    }

    internal fun onExternalMonsterKilled(target: MonsterCombatTarget) = synchronized(stateLock) {
        if (target.consumeIgniteOnDeath()) igniteNearbyLocked(target.worldPosition(), target.id)
        rewardKilledTargetLocked(target)
    }

    private fun rewardKilledTargetLocked(target: MonsterCombatTarget) {
        if (!target.claimKillReward()) return
        if (target.killGoldReward > 0) {
            GoldManager.addGold(target.killGoldReward, "kill_${target.type.name.lowercase()}")
        }
        EventBus.post(MonsterKilledEvent(target.id, target.type, target.killGoldReward))
    }

    private fun igniteNearbyLocked(impact: Vector3, excludedId: String) {
        val radiusSquared = 1.25f * 1.25f
        val effect = TowerEffectRules.burn(5)
        monsterTargets.values.forEach { target ->
            if (!target.isAlive() || target.id == excludedId) return@forEach
            val position = target.worldPosition()
            if (TowerMath.distanceSquared(
                    impact.x, impact.y, impact.z,
                    position.x, position.y, position.z,
                ) <= radiusSquared
            ) {
                target.applyBurn(
                    effect.damagePerSecond,
                    effect.durationSeconds,
                    effect.speedMultiplier,
                    effect.igniteOnKill,
                )
            }
        }
    }

    private fun applyFrostPulseLocked(tower: TowerInstance) {
        val range = effectiveRangeLocked(tower)
        val rangeSquared = range * range
        val effect = TowerEffectRules.frost(tower.level)
        monsterTargets.values.forEach { target ->
            if (!target.isAlive()) return@forEach
            val position = target.worldPosition()
            val dx = position.x - tower.x
            val dz = position.z - tower.z
            if (dx * dx + dz * dz > rangeSquared) return@forEach
            target.applySlow(effect.slow.speedMultiplier, effect.slow.durationSeconds)
            if (Random.nextFloat() < effect.freezeChance) {
                target.applyStun(effect.freezeDurationSeconds)
            }
        }
    }

    private fun effectiveDamageLocked(tower: TowerInstance): Float =
        TowerMath.damageAtLevel(
            TowerCatalog.get(tower.type),
            tower.level,
            SaveManager.bonuses.value.towerDamageBonus,
            totemDamageBonusLocked(tower),
        )

    private fun effectiveAttackIntervalLocked(tower: TowerInstance): Float =
        TowerMath.attackIntervalAtLevel(
            TowerCatalog.get(tower.type),
            tower.level,
            SaveManager.bonuses.value.towerAttackSpeedBonus,
            totemAttackSpeedBonusLocked(tower),
        )

    private fun effectiveRangeLocked(tower: TowerInstance): Float =
        TowerMath.rangeAtLevel(
            TowerCatalog.get(tower.type),
            tower.level,
            SaveManager.bonuses.value.towerRangeBonus,
        )

    private fun totemDamageBonusLocked(tower: TowerInstance): Float {
        if (tower.type == TowerType.TOTEM) return 0f
        var bonus = 0f
        towerInstances.values.forEach { totem ->
            if (totem.type != TowerType.TOTEM) return@forEach
            val effect = TowerEffectRules.totem(totem.level)
            val range = effect.rangeMeters * (1f + SaveManager.bonuses.value.towerRangeBonus)
            val dx = tower.x - totem.x
            val dz = tower.z - totem.z
            if (dx * dx + dz * dz <= range * range) {
                bonus = maxOf(bonus, effect.damageBonus)
            }
        }
        return bonus
    }

    private fun totemAttackSpeedBonusLocked(tower: TowerInstance): Float {
        if (tower.type == TowerType.TOTEM) return 0f
        var bonus = 0f
        towerInstances.values.forEach { totem ->
            if (totem.type != TowerType.TOTEM) return@forEach
            val effect = TowerEffectRules.totem(totem.level)
            val range = effect.rangeMeters * (1f + SaveManager.bonuses.value.towerRangeBonus)
            val dx = tower.x - totem.x
            val dz = tower.z - totem.z
            if (dx * dx + dz * dz <= range * range) {
                bonus = maxOf(bonus, effect.attackSpeedBonus)
            }
        }
        return bonus
    }

    private fun findNearestTargetLocked(tower: TowerInstance): MonsterCombatTarget? {
        val rangeSquared = effectiveRangeLocked(tower).let { it * it }
        var nearest: MonsterCombatTarget? = null
        var nearestDistance = Float.MAX_VALUE
        monsterTargets.values.forEach { target ->
            if (!target.isAlive()) return@forEach
            val position = target.worldPosition()
            val dx = position.x - tower.x
            val dz = position.z - tower.z
            val distance = dx * dx + dz * dz
            if (distance <= rangeSquared && distance < nearestDistance) {
                nearest = target
                nearestDistance = distance
            }
        }
        return nearest
    }

    private fun createTowerEntityLocked(
        id: String,
        type: TowerType,
        x: Float,
        baseY: Float,
        z: Float,
        wallYawRadians: Float,
    ): TowerVisual {
        val root = Entity().apply {
            setName(TowerEntityNaming.create(type, id))
            components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(x, baseY, z))
                setQuaternion(
                    Matrix4.rotateYByDegrees(
                        Math.toDegrees(wallYawRadians.toDouble()).toFloat(),
                    ).rotation,
                )
            }
            components[InteractableComponent::class.java] = InteractableComponent()
            components[CollisionComponent::class.java] =
                CollisionComponent(
                    collisionShape =
                        listOf(
                            ShapeResource.createSphere(GameplayTuning.TOWER_INTERACTION_RADIUS_METERS)
                                .offsetByTranslation(
                                    Vector3(0f, GameplayTuning.modelSize(0.5f), 0f),
                                ),
                        ),
                    physicsMaterial = PhysicsMaterialResource(),
                )
        }
        val bodyMaterial = bodyMaterialLocked(type)
        root.addChild(
            ModelEntity(
                bodyMeshLocked(type),
                bodyMaterial,
            ).apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0f, GameplayTuning.modelSize(0.39f), 0f))
                    setScaleVector(towerBodyScale(type))
                }
            },
        )

        val turret = Entity().apply {
            setName("TowerTurret")
            components[TransformComponent::class.java]?.setPosition(
                Vector3(0f, GameplayTuning.modelSize(0.82f), 0f),
            )
        }
        val headMesh = headMeshLocked(type)
        turret.addChild(
            ModelEntity(headMesh, bodyMaterial).apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(
                        0f,
                        GameplayTuning.modelSize(0.08f),
                        GameplayTuning.modelSize(-0.12f),
                    ))
                    setScaleVector(towerHeadScale(type))
                }
            },
        )
        root.addChild(turret)

        val glowMaterial =
            PhysicallyBasedMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(Color4(1f, 0.72f, 0.12f, 1f))
                setEmissiveColor(Color4(0.45f, 0.24f, 0.02f, 1f))
                setOpacity(0.18f)
            }
        val glow =
            ModelEntity(
                bodyMeshLocked(type),
                glowMaterial,
            ).apply {
                setName("TowerUpgradeGlow")
                enabled = false
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0f, GameplayTuning.modelSize(0.51f), 0f))
                    setScaleVector(Vector3(29f, 51f, 29f))
                }
            }
        root.addChild(glow)
        checkNotNull(towerRoot).addChild(root)
        return TowerVisual(root, turret, glow, glowMaterial)
    }

    private fun rebuildPreviewLocked(type: TowerType) {
        previewEntity?.destroy()
        previewMaterial?.close()
        previewEntity = null
        previewMaterial = null
        val parent = towerRoot ?: return
        val material =
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(Color4(0.15f, 0.95f, 0.35f, 1f))
                setOpacity(0.36f)
            }
        val root = Entity().apply {
            setName("TowerPlacementPreview_${type.name}")
            enabled = false
        }
        root.addChild(
            ModelEntity(
                bodyMeshLocked(type),
                material,
            ).apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0f, GameplayTuning.modelSize(0.39f), 0f))
                    setScaleVector(towerBodyScale(type))
                }
            },
        )
        root.addChild(
            ModelEntity(
                headMeshLocked(type),
                material,
            ).apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(
                        0f,
                        GameplayTuning.modelSize(0.9f),
                        GameplayTuning.modelSize(-0.12f),
                    ))
                    setScaleVector(towerHeadScale(type))
                }
            },
        )
        parent.addChild(root)
        previewEntity = root
        previewMaterial = material
    }

    private fun orientTurretLocked(tower: TowerInstance, target: Vector3) {
        val directionX = target.x - tower.x
        val directionZ = target.z - tower.z
        val localYawRadians =
            TowerMath.turretLocalYawRadians(directionX, directionZ, tower.wallYawRadians)
        tower.turret.components[TransformComponent::class.java]?.setQuaternion(
            Matrix4.rotateYByDegrees(
                Math.toDegrees(localYawRadians.toDouble()).toFloat(),
            ).rotation,
        )
    }

    private fun lookAtRotation(from: Vector3, target: Vector3) =
        (Matrix4.lookAt(from, target, Vector3(0f, 1f, 0f)).inverse() *
            Matrix4.rotateYByDegrees(180f)).rotation

    private fun isOccupiedLocked(x: Float, z: Float): Boolean {
        val minimumDistanceSquared = MIN_TOWER_SPACING * MIN_TOWER_SPACING
        return towerInstances.values.any { tower ->
            val dx = tower.x - x
            val dz = tower.z - z
            dx * dx + dz * dz < minimumDistanceSquared
        }
    }

    private fun nextFreeWallMountIndexLocked(): Int? {
        var occupiedMountMask = 0
        towerInstances.values.forEach { tower ->
            if (tower.wallMountIndex in 0 until MAX_WALL_WEAPONS) {
                occupiedMountMask = occupiedMountMask or (1 shl tower.wallMountIndex)
            }
        }
        return WallWeaponSlotRules.nextFreeSlot(occupiedMountMask)
    }

    private fun prewarmSharedTowerResourcesLocked() {
        TowerType.entries.forEach(::bodyMaterialLocked)
        bodyMeshLocked(TowerType.ARCHER)
        headMeshLocked(TowerType.ARCHER)
    }

    private fun bodyMaterialLocked(type: TowerType): UnlitMaterial =
        bodyMaterials.getOrPut(type) {
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(towerColor(type))
                toGlobal()
            }
        }

    private fun bodyMeshLocked(type: TowerType): MeshResource =
        sharedPrimitiveMesh ?: SharedGameplayMeshRegistry.acquire().also { sharedPrimitiveMesh = it }

    private fun headMeshLocked(type: TowerType): MeshResource =
        bodyMeshLocked(type)

    private fun towerBodyScale(type: TowerType): Vector3 =
        if (type == TowerType.CANNON || type == TowerType.TOTEM) Vector3(24f, 39f, 24f)
        else Vector3(18f, 39f, 18f)

    private fun towerHeadScale(type: TowerType): Vector3 =
        when (type) {
            TowerType.ARCHER -> Vector3(11f, 11f, 12f)
            TowerType.CROSSBOW -> Vector3(27.5f, 8f, 9f)
            TowerType.CANNON -> Vector3(11f, 9f, 27.5f)
            TowerType.FROST -> Vector3(12f, 12f, 12f)
            TowerType.BURN -> Vector3(11f, 14f, 11f)
            TowerType.TOTEM -> Vector3(6f, 27.5f, 6f)
        }

    private fun refreshPreviewAffordabilityLocked() {
        val state = mutablePreview.value
        val type = state.selectedType ?: return
        val affordable = GoldManager.getCurrentGold() >= TowerCatalog.get(type).cost
        mutablePreview.value = state.copy(isAffordable = affordable)
        if (state.isVisible) {
            previewMaterial?.setBaseColor(
                if (affordable && !state.isBlocked) Color4(0.15f, 0.95f, 0.35f, 1f)
                else Color4(1f, 0.08f, 0.08f, 1f),
            )
        }
    }

    private fun publishTowersLocked() {
        mutableTowers.value = towerInstances.values.map(TowerInstance::toState)
    }

    private fun relocateToLayoutLocked(layout: SceneLayout) {
        if (towerInstances.isEmpty()) return
        projectilePool?.releaseAll()
        towerInstances.values.forEach { tower ->
            val mount = layout.wallWeaponMounts.getOrNull(tower.wallMountIndex) ?: return@forEach
            tower.x = mount.x
            tower.baseY = GameplayTuning.groundedBaseY(mount.y)
            tower.z = mount.z
            tower.wallYawRadians = layout.wall.rotationYRadians
            tower.lockedTargetId = null
            tower.entity.components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(tower.x, tower.baseY, tower.z))
                setQuaternion(
                    Matrix4.rotateYByDegrees(
                        Math.toDegrees(tower.wallYawRadians.toDouble()).toFloat(),
                    ).rotation,
                )
            }
        }
        publishTowersLocked()
        Log.i(TAG, "Aligned ${towerInstances.size} weapons to calibrated wall slots")
    }

    internal fun stopCombat() = synchronized(stateLock) {
        projectilePool?.releaseAll()
        combatWasActive = false
        towerInstances.values.forEach {
            it.cooldownSeconds = 0f
            it.lockedTargetId = null
        }
        publishTowersLocked()
    }

    internal fun resetSession() = synchronized(stateLock) {
        ensureRuntimeResourcesLocked()
        stopCombat()
        towerInstances.values.forEach { it.entity.destroy() }
        towerInstances.values.forEach { it.glowMaterial.close() }
        towerInstances.clear()
        monsterTargets.clear()
        selectedTowerType = null
        previewEntity?.destroy()
        previewMaterial?.close()
        previewEntity = null
        previewMaterial = null
        mutablePreview.value = TowerPlacementPreviewState()
        mutablePlayerState.value =
            PlayerState(GoldManager.getCurrentGold(), currentSafeGridIndex = 4, isAlive = true)
        publishTowersLocked()
        towerPublishDirty = false
        towerPublishElapsedSeconds = 0f
    }

    internal fun releaseBattleResources() = synchronized(stateLock) {
        val retainedHost = hostRoot
        detachLocked()
        hostRoot = retainedHost
    }

    private fun ensureRuntimeResourcesLocked() {
        if (towerRoot?.valid == true && projectilePool != null) return
        val parent = hostRoot ?: return
        towerRoot = Entity().apply {
            setName("TowerRuntimeRoot")
            enabled = GameStateManager.state.value != GameState.IDLE
        }.also(parent::addChild)
        projectilePool = ProjectilePool(checkNotNull(towerRoot))
        prewarmSharedTowerResourcesLocked()
    }

    private fun towerColor(type: TowerType): Color4 =
        when (type) {
            TowerType.ARCHER -> Color4(0.34f, 0.62f, 0.26f, 1f)
            TowerType.CROSSBOW -> Color4(0.24f, 0.42f, 0.68f, 1f)
            TowerType.CANNON -> Color4(0.38f, 0.34f, 0.32f, 1f)
            TowerType.FROST -> Color4(0.22f, 0.72f, 0.94f, 1f)
            TowerType.BURN -> Color4(0.88f, 0.26f, 0.08f, 1f)
            TowerType.TOTEM -> Color4(0.62f, 0.22f, 0.78f, 1f)
        }

    private fun detachLocked() {
        towerInstances.values.forEach { it.glowMaterial.close() }
        previewMaterial?.close()
        previewEntity = null
        previewMaterial = null
        projectilePool?.destroy()
        projectilePool = null
        combatWasActive = false
        towerPublishDirty = false
        towerPublishElapsedSeconds = 0f
        towerRoot?.destroy()
        towerRoot = null
        if (sharedPrimitiveMesh != null) SharedGameplayMeshRegistry.release()
        bodyMaterials.values.forEach(UnlitMaterial::close)
        sharedPrimitiveMesh = null
        bodyMaterials.clear()
        hostRoot = null
        towerInstances.clear()
        monsterTargets.clear()
        mutableTowers.value = emptyList()
        mutablePreview.value = TowerPlacementPreviewState(selectedType = selectedTowerType)
    }

    override fun onDestroy() {
        goldSyncJob?.cancel()
        goldSyncJob = null
        synchronized(stateLock) {
            layoutSyncJob?.cancel()
            layoutSyncJob = null
            detachLocked()
        }
    }

    private data class TowerVisual(
        val root: Entity,
        val turret: Entity,
        val glow: Entity,
        val glowMaterial: PhysicallyBasedMaterial,
    )

    private data class TowerInstance(
        val id: String,
        val type: TowerType,
        var x: Float,
        var baseY: Float,
        var z: Float,
        var wallYawRadians: Float,
        val wallMountIndex: Int,
        val entity: Entity,
        val turret: Entity,
        val glow: Entity,
        val glowMaterial: PhysicallyBasedMaterial,
        var level: Int = 1,
        var cooldownSeconds: Float = 0f,
        var lockedTargetId: String? = null,
        var hasLoggedFirstProjectile: Boolean = false,
    ) {
        fun toState(): TowerRuntimeState {
            val config = TowerCatalog.get(type)
            return TowerRuntimeState(
                id = id,
                type = type,
                level = level,
                x = x,
                y = baseY,
                z = z,
                wallMountIndex = wallMountIndex,
                damage = TowerManager.effectiveDamageLocked(this),
                attackIntervalSeconds = TowerManager.effectiveAttackIntervalLocked(this),
                attackRange = TowerManager.effectiveRangeLocked(this),
                upgradeCost =
                    if (level >= config.maxLevel) 0
                    else TowerMath.upgradeCost(config, level),
                featureDescription = TowerCatalog.featureDescription(type, level),
                lockedTargetId = lockedTargetId,
            )
        }
    }
}
