package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.MonsterType

data class MonsterModelAsset(
    val type: MonsterType,
    val assetUri: String,
    val rootScale: Float,
)

/** Stable APK-asset mapping for all pooled monster visuals. */
object MonsterModelCatalog {
    val entries: List<MonsterModelAsset> =
        MonsterType.entries.map { type ->
            MonsterModelAsset(
                type = type,
                assetUri =
                    "asset://models/monsters/${MonsterCatalog.get(type).modelResourceName}.glb",
                rootScale = GameplayTuning.IMPORTED_MONSTER_MODEL_SCALE,
            )
        }

    fun get(type: MonsterType): MonsterModelAsset =
        checkNotNull(entries.firstOrNull { it.type == type })
}
