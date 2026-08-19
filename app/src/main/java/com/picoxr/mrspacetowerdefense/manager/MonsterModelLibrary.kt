package com.picoxr.mrspacetowerdefense.manager

import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.picoxr.mrspacetowerdefense.model.MonsterType
import kotlinx.coroutines.CancellationException

/**
 * Owns one loaded GLB template per monster type. Pooled instances recursively clone
 * these templates and share immutable material instances instead of loading files again.
 */
internal class MonsterModelLibrary private constructor(
    private val templateRoot: Entity,
    private val templates: Map<MonsterType, Entity>,
) {
    fun attach(parent: Entity) {
        parent.addChild(templateRoot)
    }

    fun instantiate(type: MonsterType): Entity? =
        templates[type]
            ?.clone(
                Entity.CloneOptions(
                    recursive = true,
                    shouldShareMaterialInstance = true,
                ),
            )
            ?.apply {
                setName("MonsterModel_${type.name}")
                enabled = true
            }

    fun loadedCount(): Int = templates.size

    fun destroy() {
        templateRoot.destroy()
    }

    companion object {
        private const val TAG = "MonsterModelLibrary"

        /** Called once from SpatialView.initial; Entity.loadSuspend is SDK-managed async IO. */
        suspend fun load(): MonsterModelLibrary {
            val root =
                Entity().apply {
                    setName("MonsterModelTemplates")
                    enabled = false
                }
            val loaded = linkedMapOf<MonsterType, Entity>()
            try {
                MonsterModelCatalog.entries.forEach { asset ->
                    try {
                        val template = Entity.loadSuspend(asset.assetUri)
                        template.setName("MonsterTemplate_${asset.type.name}")
                        val transform =
                            template.components[TransformComponent::class.java]
                                ?: TransformComponent().also(template.components::set)
                        transform.setScaleVector(
                            Vector3(asset.rootScale, asset.rootScale, asset.rootScale),
                        )
                        root.addChild(template)
                        loaded[asset.type] = template
                        Log.i(
                            TAG,
                            "Loaded ${asset.type} from ${asset.assetUri}, rootScale=${asset.rootScale}",
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Log.e(
                            TAG,
                            "Failed to load ${asset.type} from ${asset.assetUri}; using procedural fallback",
                            error,
                        )
                    }
                }
                Log.i(TAG, "Monster GLB templates ready: ${loaded.size}/${MonsterType.entries.size}")
                return MonsterModelLibrary(root, loaded)
            } catch (cancelled: CancellationException) {
                root.destroy()
                throw cancelled
            }
        }
    }
}
