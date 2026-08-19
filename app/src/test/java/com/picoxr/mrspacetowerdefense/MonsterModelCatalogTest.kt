package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.GameplayTuning
import com.picoxr.mrspacetowerdefense.manager.MonsterModelCatalog
import com.picoxr.mrspacetowerdefense.model.MonsterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterModelCatalogTest {
    @Test
    fun `catalog maps every monster type to one unique GLB`() {
        val entries = MonsterModelCatalog.entries

        assertEquals(MonsterType.entries.toSet(), entries.map { it.type }.toSet())
        assertEquals(entries.size, entries.map { it.assetUri }.toSet().size)
        assertTrue(entries.all { it.assetUri.startsWith("asset://models/monsters/") })
        assertTrue(entries.all { it.assetUri.endsWith(".glb") })
    }

    @Test
    fun `imported visuals apply the requested additional one hundredth scale`() {
        assertEquals(0.001f, GameplayTuning.IMPORTED_MONSTER_MODEL_SCALE, 0.0000001f)
        assertTrue(
            MonsterModelCatalog.entries.all {
                it.rootScale == GameplayTuning.IMPORTED_MONSTER_MODEL_SCALE
            },
        )
    }
}
