package com.picoxr.mrspacetowerdefense.manager

import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector3

/**
 * One process-local rounded primitive shared by towers, monsters and projectiles.
 * Native procedural mesh creation is expensive on device; consumers express their
 * silhouettes through per-entity scale instead of blocking startup for each archetype.
 */
internal object SharedGameplayMeshRegistry {
    private var mesh: MeshResource? = null
    private var leaseCount = 0

    @Synchronized
    fun acquire(): MeshResource {
        leaseCount++
        return mesh ?: MeshResource.createBox(
            Vector3(BASE_SIZE_METERS, BASE_SIZE_METERS, BASE_SIZE_METERS),
            CORNER_RADIUS_METERS,
        ).also {
            it.toGlobal()
            mesh = it
        }
    }

    @Synchronized
    fun release() {
        if (leaseCount <= 0) return
        leaseCount--
        if (leaseCount == 0) {
            mesh?.close()
            mesh = null
        }
    }

    const val BASE_SIZE_METERS = 0.002f
    private const val CORNER_RADIUS_METERS = 0.00035f
}
