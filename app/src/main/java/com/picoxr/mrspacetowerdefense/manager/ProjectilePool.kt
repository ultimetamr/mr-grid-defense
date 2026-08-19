package com.picoxr.mrspacetowerdefense.manager

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.picoxr.mrspacetowerdefense.model.TowerType

internal class ProjectilePool(
    parent: Entity,
    private val maximumActiveProjectiles: Int = DEFAULT_MAX_PROJECTILES,
) {
    private val root = Entity().apply { setName("ProjectilePool") }
    private val projectiles = mutableListOf<PooledProjectile>()
    private var sharedMesh: MeshResource? = null
    private val materials = linkedMapOf<TowerType, UnlitMaterial>()

    init {
        parent.addChild(root)
        PREWARM_PER_TYPE.forEach { (type, count) ->
            repeat(count) { projectiles += createProjectile(type) }
        }
    }

    fun acquire(type: TowerType): PooledProjectile? {
        projectiles.firstOrNull { !it.active && it.type == type }?.let { return it }
        if (projectiles.size >= maximumActiveProjectiles) return null
        return createProjectile(type).also(projectiles::add)
    }

    val size: Int get() = projectiles.size

    fun projectileAt(index: Int): PooledProjectile = projectiles[index]

    fun stats(): ProjectilePoolStats =
        ProjectilePoolStats(
            activeCount = projectiles.count(PooledProjectile::active),
            createdCount = projectiles.size,
            maximumCount = maximumActiveProjectiles,
        )

    fun release(projectile: PooledProjectile) {
        projectile.active = false
        projectile.entity.enabled = false
        projectile.targetId = null
        projectile.sourceTowerId = null
        projectile.traveledDistance = 0f
        projectile.towerLevel = 1
        projectile.remainingHits = 1
        projectile.hitCount = 0
        projectile.hitTargetIds.fill(null)
    }

    fun releaseAll() {
        projectiles.forEach(::release)
    }

    fun destroy() {
        root.destroy()
        projectiles.clear()
        if (sharedMesh != null) SharedGameplayMeshRegistry.release()
        materials.values.forEach(UnlitMaterial::close)
        sharedMesh = null
        materials.clear()
    }

    private fun createProjectile(type: TowerType): PooledProjectile {
        val entity = Entity().apply {
            setName("PooledProjectile_${type.name}_${projectiles.size}")
            enabled = false
        }
        val mesh = sharedMesh ?: SharedGameplayMeshRegistry.acquire().also { sharedMesh = it }
        val material = materials.getOrPut(type) {
            val color =
                when (type) {
                    TowerType.ARCHER -> Color4(0.76f, 0.55f, 0.24f, 1f)
                    TowerType.CROSSBOW -> Color4(0.72f, 0.82f, 0.92f, 1f)
                    TowerType.CANNON -> Color4(0.18f, 0.18f, 0.2f, 1f)
                    TowerType.FROST -> Color4(0.3f, 0.85f, 1f, 1f)
                    TowerType.BURN -> Color4(1f, 0.32f, 0.05f, 1f)
                    TowerType.TOTEM -> Color4(0.85f, 0.25f, 1f, 1f)
                }
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color)
                toGlobal()
            }
        }
        entity.addChild(
            ModelEntity(mesh, material).apply {
                components[TransformComponent::class.java]?.setScaleVector(projectileScale(type))
            },
        )
        root.addChild(entity)
        return PooledProjectile(type = type, entity = entity)
    }

    private fun projectileScale(type: TowerType): Vector3 =
        when (type) {
            TowerType.ARCHER -> Vector3(1.25f, 1.25f, 9f)
            TowerType.CROSSBOW -> Vector3(1.75f, 1.75f, 11f)
            TowerType.CANNON -> Vector3(9f, 9f, 9f)
            TowerType.FROST -> Vector3(18f, 18f, 18f)
            TowerType.BURN -> Vector3(16f, 16f, 16f)
            TowerType.TOTEM -> Vector3(4f, 4f, 4f)
        }

    companion object {
        const val DEFAULT_MAX_PROJECTILES = 48

        private val PREWARM_PER_TYPE =
            mapOf(
                TowerType.ARCHER to 12,
                TowerType.CROSSBOW to 8,
                TowerType.CANNON to 4,
                TowerType.FROST to 4,
                TowerType.BURN to 6,
            )
    }
}

internal data class PooledProjectile(
    val type: TowerType,
    val entity: Entity,
    var active: Boolean = false,
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var velocityX: Float = 0f,
    var velocityY: Float = 0f,
    var velocityZ: Float = 0f,
    var damage: Float = 0f,
    var splashRadius: Float = 0f,
    var maxRange: Float = 0f,
    var traveledDistance: Float = 0f,
    var targetId: String? = null,
    var sourceTowerId: String? = null,
    var towerLevel: Int = 1,
    var remainingHits: Int = 1,
    var hitCount: Int = 0,
    val hitTargetIds: Array<String?> = arrayOfNulls(4),
)

data class ProjectilePoolStats(
    val activeCount: Int,
    val createdCount: Int,
    val maximumCount: Int,
)
