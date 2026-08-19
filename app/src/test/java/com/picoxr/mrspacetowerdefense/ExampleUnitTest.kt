package com.picoxr.mrspacetowerdefense

import org.junit.Test

import org.junit.Assert.*
import com.picoxr.mrspacetowerdefense.model.GameConfig

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun gameBoard_isThreeByThree() {
        val config = GameConfig()
        assertEquals(3, config.boardRows)
        assertEquals(3, config.boardColumns)
        assertEquals(9, config.boardRows * config.boardColumns)
    }
}
