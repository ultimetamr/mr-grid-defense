package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.GameplayTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameplayTuningTest {
    @Test
    fun `combat models are one tenth of authored procedural dimensions`() {
        assertEquals(0.1f, GameplayTuning.modelSize(1f), 0.0001f)
        assertEquals(0.045f, GameplayTuning.modelSize(0.45f), 0.0001f)
    }

    @Test
    fun `monster runtime speed is halved before wave acceleration`() {
        assertEquals(0.5f, GameplayTuning.monsterMoveSpeed(1f, 1f), 0.0001f)
        assertEquals(1.05f, GameplayTuning.monsterMoveSpeed(2f, 1.05f), 0.0001f)
    }

    @Test
    fun `five metre travel times preserve distinct monster roles`() {
        assertEquals(10f, GameplayTuning.monsterTravelSeconds(5f, 1f), 0.0001f)
        assertEquals(5f, GameplayTuning.monsterTravelSeconds(5f, 2f), 0.0001f)
        assertEquals(20f, GameplayTuning.monsterTravelSeconds(5f, 0.5f), 0.0001f)
        assertEquals(9.09f, GameplayTuning.monsterTravelSeconds(5f, 1.1f), 0.01f)
        assertEquals(13.33f, GameplayTuning.monsterTravelSeconds(5f, 0.75f), 0.01f)
        assertEquals(12.5f, GameplayTuning.monsterTravelSeconds(5f, 0.8f), 0.0001f)
        assertEquals(25f, GameplayTuning.monsterTravelSeconds(5f, 0.4f), 0.0001f)
    }

    @Test
    fun `monster spacing includes the configured visible gap`() {
        assertTrue(GameplayTuning.circlesOverlap(0f, 0f, 0.02f, 0.06f, 0f, 0.02f))
        assertFalse(GameplayTuning.circlesOverlap(0f, 0f, 0.02f, 0.071f, 0f, 0.02f))
    }

    @Test
    fun `helmet must fit completely inside safe rectangle`() {
        assertTrue(GameplayTuning.circleInsideRectangle(0f, 0f, 0.12f, 0f, 0f, 0.8f, 0.8f))
        assertFalse(GameplayTuning.circleInsideRectangle(0.35f, 0f, 0.12f, 0f, 0f, 0.8f, 0.8f))
    }

    @Test
    fun `grounded model bottom receives only the anti clipping clearance`() {
        assertEquals(1.205f, GameplayTuning.groundedBaseY(1.2f), 0.0001f)
    }
}
