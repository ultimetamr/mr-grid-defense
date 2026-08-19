package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.TowerEntityNaming
import com.picoxr.mrspacetowerdefense.model.TowerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TowerEntityNamingTest {
    @Test
    fun `uuid hyphens are replaced for PICO entity names`() {
        val name = TowerEntityNaming.create(TowerType.ARCHER, "123e4567-e89b-12d3-a456-426614174000")

        assertEquals("Tower_ARCHER_123e4567_e89b_12d3_a456_426614174000", name)
        assertFalse(name.contains('-'))
    }
}
