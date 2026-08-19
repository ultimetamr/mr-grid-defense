package com.picoxr.mrspacetowerdefense.manager

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import kotlin.math.sqrt

/** Reusable visible shells fired by ranged acid monsters toward the wall. */
internal class AcidProjectilePool(
    parent: Entity,
    private val maximumProjectiles: Int = DEFAULT_MAX_PROJECTILES,
) {
    private val root = Entity().apply { setName("AcidProjectilePool") }
    private val projectiles = mutableListOf<PooledAcidProjectile>()
    private var sharedMesh: MeshResource? = null
    private var material: UnlitMaterial? = null

    init {
        parent.addChild(root)
        repeat(PREWARM_COUNT) { projectiles += createProjectile() }
    }

    fun launch(
        sourceMonsterId: String,
        startX: Float,
        startY: Float,
        startZ: Float,
        targetX: Float,
        targetY: Float,
        targetZ: Float,
        damage: Float,
    ): Boolean {
        val projectile =
            projectiles.firstOrNull { !it.active }
                ?: if (projectiles.size < maximumProjectiles) {
                    createProjectile().also(projectiles::add)
                } else {
                    return false
                }
        val dx = targetX - startX
        val dy = targetY - startY
        val dz = targetZ - startZ
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance <= 0.0001f) return false
        projectile.apply {
            active = true
            sourceId = sourceMonsterId
            x = startX
            y = startY
            z = startZ
            velocityX = dx / distance * PROJECTILE_SPEED_METERS_PER_SECOND
            velocityY = dy / distance * PROJECTILE_SPEED_METERS_PER_SECOND
            velocityZ = dz / distance * PROJECTILE_SPEED_METERS_PER_SECOND
            remainingDistance = distance
            this.damage = damage
            entity.enabled = true
            entity.components[TransformComponent::class.java]?.setPosition(Vector3(x, y, z))
        }
        return true
    }

    /** Calls [onImpact] exactly once when a shell reaches the wall. */
    fun update(deltaTimeSeconds: Float, onImpact: (PooledAcidProjectile) -> Unit) {
        if (deltaTimeSeconds <= 0f) return
        for (projectile in projectiles) {
            if (!projectile.active) continue
            val travel = PROJECTILE_SPEED_METERS_PER_SECOND * deltaTimeSeconds
            if (travel >= projectile.remainingDistance) {
                projectile.x += projectile.velocityX * (projectile.remainingDistance / PROJECTILE_SPEED_METERS_PER_SECOND)
                projectile.y += projectile.velocityY * (projectile.remainingDistance / PROJECTILE_SPEED_METERS_PER_SECOND)
                projectile.z += projectile.velocityZ * (projectile.remainingDistance / PROJECTILE_SPEED_METERS_PER_SECOND)
                projectile.entity.components[TransformComponent::class.java]?.setPosition(
                    Vector3(projectile.x, projectile.y, projectile.z),
                )
                onImpact(projectile)
                release(projectile)
                continue
            }
            projectile.x += projectile.velocityX * deltaTimeSeconds
            projectile.y += projectile.velocityY * deltaTimeSeconds
            projectile.z += projectile.velocityZ * deltaTimeSeconds
            projectile.remainingDistance -= travel
            projectile.entity.components[TransformComponent::class.java]?.setPosition(
                Vector3(projectile.x, projectile.y, projectile.z),
            )
        }
    }

    fun releaseAll() = projectiles.forEach(::release)

    fun destroy() {
        root.destroy()
        projectiles.clear()
        if (sharedMesh != null) SharedGameplayMeshRegistry.release()
        material?.close()
        sharedMesh = null
        material = null
    }

    private fun release(projectile: PooledAcidProjectile) {
        projectile.active = false
        projectile.entity.enabled = false
        projectile.sourceId = null
        projectile.remainingDistance = 0f
        projectile.damage = 0f
    }

    private fun createProjectile(): PooledAcidProjectile {
        val mesh = sharedMesh ?: SharedGameplayMeshRegistry.acquire().also { sharedMesh = it }
        val shellMaterial =
            material ?: UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(0.38f, 1f, 0.12f, 1f))
                toGlobal()
            }.also { material = it }
        val entity = Entity().apply {
            setName("PooledAcidShell_${projectiles.size}")
            enabled = false
        }
        entity.addChild(
            ModelEntity(mesh, shellMaterial).apply {
                components[TransformComponent::class.java]?.setScaleVector(Vector3(18f, 18f, 18f))
            },
        )
        root.addChild(entity)
        return PooledAcidProjectile(entity)
    }

    companion object {
        const val PROJECTILE_SPEED_METERS_PER_SECOND = 3f
        const val DEFAULT_MAX_PROJECTILES = 16
        private const val PREWARM_COUNT = 8
    }
}

internal data class PooledAcidProjectile(
    val entity: Entity,
    var active: Boolean = false,
    var sourceId: String? = null,
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var velocityX: Float = 0f,
    var velocityY: Float = 0f,
    var velocityZ: Float = 0f,
    var remainingDistance: Float = 0f,
    var damage: Float = 0f,
)

