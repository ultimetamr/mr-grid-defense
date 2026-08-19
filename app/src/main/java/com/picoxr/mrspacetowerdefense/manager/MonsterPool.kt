package com.picoxr.mrspacetowerdefense.manager

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.picoxr.mrspacetowerdefense.model.MonsterPoolStats
import com.picoxr.mrspacetowerdefense.model.MonsterType

internal class MonsterPool(
    parent: Entity,
    private val modelLibrary: MonsterModelLibrary,
    private val maximumMonsters: Int = DEFAULT_MAX_MONSTERS,
) {
    private val root = Entity().apply { setName("MonsterPool") }
    private val monsters = mutableListOf<BaseMonster>()
    private var sharedMesh: MeshResource? = null
    private val materials = linkedMapOf<MonsterType, UnlitMaterial>()

    init {
        parent.addChild(root)
        PREWARM_PER_TYPE.forEach { (type, count) -> repeat(count) { monsters += create(type) } }
    }

    fun acquire(type: MonsterType): BaseMonster? {
        monsters.firstOrNull { !it.active && it.type == type }?.let { return it }
        if (monsters.size >= maximumMonsters) return null
        return create(type).also(monsters::add)
    }

    fun release(monster: BaseMonster) = monster.recycle()

    fun releaseAll() {
        monsters.forEach { if (it.active) release(it) }
    }

    fun stats(): MonsterPoolStats =
        MonsterPoolStats(
            activeCount = monsters.count(BaseMonster::active),
            createdCount = monsters.size,
            maximumCount = maximumMonsters,
        )

    fun destroy() {
        root.destroy()
        monsters.clear()
        if (sharedMesh != null) SharedGameplayMeshRegistry.release()
        materials.values.forEach(UnlitMaterial::close)
        sharedMesh = null
        materials.clear()
    }

    private fun create(type: MonsterType): BaseMonster {
        val dimensions = dimensions(type)
        val anchor = Entity().apply {
            setName("PooledMonster_${type.name}_${monsters.size}")
            enabled = false
        }
        val visual = modelLibrary.instantiate(type) ?: createProceduralVisual(type, dimensions)
        anchor.addChild(visual)
        root.addChild(anchor)
        return BaseMonster(MonsterCatalog.get(type), anchor, dimensions.radius, dimensions.height)
    }

    private fun createProceduralVisual(type: MonsterType, dimensions: Dimensions): Entity {
        val baseRadius = SharedGameplayMeshRegistry.BASE_SIZE_METERS / 2f
        val mesh = sharedMesh ?: SharedGameplayMeshRegistry.acquire().also { sharedMesh = it }
        val material = materials.getOrPut(type) {
            UnlitMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(color(type))
                toGlobal()
            }
        }
        return ModelEntity(mesh, material).apply {
            setName("${type.name}_ProceduralFallback")
            components[TransformComponent::class.java]?.apply {
                setPosition(Vector3(0f, dimensions.height / 2f, 0f))
                setScaleVector(
                    Vector3(
                        dimensions.radius / baseRadius,
                        dimensions.height / (baseRadius * 2f),
                        dimensions.radius / baseRadius,
                    ),
                )
            }
        }
    }

    private fun dimensions(type: MonsterType): Dimensions =
        when (type) {
            MonsterType.NORMAL -> Dimensions(GameplayTuning.modelSize(0.18f), GameplayTuning.modelSize(0.36f))
            MonsterType.FAST -> Dimensions(GameplayTuning.modelSize(0.14f), GameplayTuning.modelSize(0.28f))
            MonsterType.ARMORED -> Dimensions(GameplayTuning.modelSize(0.24f), GameplayTuning.modelSize(0.48f))
            MonsterType.SELF_DESTRUCT -> Dimensions(GameplayTuning.modelSize(0.20f), GameplayTuning.modelSize(0.32f))
            MonsterType.ACID -> Dimensions(GameplayTuning.modelSize(0.21f), GameplayTuning.modelSize(0.44f))
            MonsterType.ELITE -> Dimensions(GameplayTuning.modelSize(0.28f), GameplayTuning.modelSize(0.56f))
            MonsterType.BOSS -> Dimensions(GameplayTuning.modelSize(0.45f), GameplayTuning.modelSize(0.90f))
        }

    private fun color(type: MonsterType): Color4 =
        when (type) {
            MonsterType.NORMAL -> Color4(0.56f, 0.82f, 0.26f, 1f)
            MonsterType.FAST -> Color4(0.92f, 0.74f, 0.16f, 1f)
            MonsterType.ARMORED -> Color4(0.32f, 0.38f, 0.46f, 1f)
            MonsterType.SELF_DESTRUCT -> Color4(1f, 0.36f, 0.08f, 1f)
            MonsterType.ACID -> Color4(0.25f, 0.88f, 0.56f, 1f)
            MonsterType.ELITE -> Color4(0.64f, 0.24f, 0.82f, 1f)
            MonsterType.BOSS -> Color4(0.92f, 0.12f, 0.12f, 1f)
        }

    private data class Dimensions(val radius: Float, val height: Float)

    companion object {
        const val DEFAULT_MAX_MONSTERS = 40
        private val PREWARM_PER_TYPE =
            mapOf(
                MonsterType.NORMAL to 8,
                MonsterType.FAST to 4,
                MonsterType.ARMORED to 4,
                MonsterType.SELF_DESTRUCT to 3,
                MonsterType.ACID to 3,
                MonsterType.ELITE to 4,
                MonsterType.BOSS to 1,
            )
    }
}
