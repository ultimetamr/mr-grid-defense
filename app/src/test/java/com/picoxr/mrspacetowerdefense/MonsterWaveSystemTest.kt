package com.picoxr.mrspacetowerdefense

import com.picoxr.mrspacetowerdefense.manager.MonsterCatalog
import com.picoxr.mrspacetowerdefense.manager.MonsterCleanupAction
import com.picoxr.mrspacetowerdefense.manager.MonsterCleanupGate
import com.picoxr.mrspacetowerdefense.manager.MonsterNavigation
import com.picoxr.mrspacetowerdefense.manager.MonsterAttackRules
import com.picoxr.mrspacetowerdefense.manager.MonsterSpawnRules
import com.picoxr.mrspacetowerdefense.manager.WaveCatalog
import com.picoxr.mrspacetowerdefense.manager.WaveCompletionRules
import com.picoxr.mrspacetowerdefense.model.GroundBounds
import com.picoxr.mrspacetowerdefense.model.MonsterType
import com.picoxr.mrspacetowerdefense.model.ObstacleBounds
import com.picoxr.mrspacetowerdefense.model.ScenePoint
import com.picoxr.mrspacetowerdefense.model.SceneRect
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonsterWaveSystemTest {
    @Test
    fun `monster cleanup is deferred during frame and strongest request wins`() {
        val gate = MonsterCleanupGate()
        gate.beginFrame()

        assertTrue(gate.deferIfFrameActive(MonsterCleanupAction.RECYCLE_WAVE))
        assertTrue(gate.deferIfFrameActive(MonsterCleanupAction.RELEASE_BATTLE_RESOURCES))
        assertEquals(MonsterCleanupAction.RELEASE_BATTLE_RESOURCES, gate.endFrame())
        assertFalse(gate.deferIfFrameActive(MonsterCleanupAction.RECYCLE_WAVE))
    }

    @Test
    fun `monster catalog matches the seven requested archetypes`() {
        assertEquals(7, MonsterCatalog.all().size)
        assertConfig(MonsterType.NORMAL, 30f, 1f, 1, 5)
        assertConfig(MonsterType.FAST, 20f, 2f, 1, 8)
        assertConfig(MonsterType.ARMORED, 150f, 0.5f, 4, 15)
        assertConfig(MonsterType.SELF_DESTRUCT, 60f, 1.1f, 0, 12)
        assertEquals(35, MonsterCatalog.get(MonsterType.SELF_DESTRUCT).selfDestructDamage)
        assertConfig(MonsterType.ACID, 80f, 0.75f, 3, 18)
        assertConfig(MonsterType.ELITE, 300f, 0.8f, 8, 25)
        assertConfig(MonsterType.BOSS, 2_500f, 0.4f, 25, 150)
    }

    @Test
    fun `melee monsters touch wall while acid monster keeps ranged standoff`() {
        val wallDepth = 0.18f
        val hitRadius = 0.02f
        val normal = MonsterCatalog.get(MonsterType.NORMAL)
        val acid = MonsterCatalog.get(MonsterType.ACID)

        val meleeStop = MonsterAttackRules.stopDistance(normal, hitRadius, wallDepth)
        assertEquals(0.11f, meleeStop, 0.0001f)
        assertTrue(meleeStop < 0.3f)
        assertFalse(MonsterAttackRules.attacksWithProjectile(normal))

        assertEquals(2.11f, MonsterAttackRules.stopDistance(acid, hitRadius, wallDepth), 0.0001f)
        assertTrue(MonsterAttackRules.attacksWithProjectile(acid))
    }

    @Test
    fun `direct attack movement always advances without obstacle callbacks`() {
        val output = FloatArray(2)
        MonsterNavigation.forceProgressInto(
            x = -1f,
            z = -5f,
            targetX = 0f,
            targetZ = 0f,
            distance = 0.1f,
            output = output,
        )

        assertTrue(output[0] > -1f)
        assertTrue(output[1] > -5f)
    }

    @Test
    fun `wave progression applies count stats and ray interval rules`() {
        assertEquals(
            listOf(5, 8, 11, 14, 17, 20, 23, 26, 29, 32),
            (1..WaveCatalog.TOTAL_WAVES).map { WaveCatalog.get(it).monsterCount },
        )
        val wave1 = WaveCatalog.get(1)
        assertEquals(5, wave1.monsterCount)
        assertEquals(1f, wave1.hpMultiplier, 0.0001f)
        assertEquals(1f, wave1.moveSpeedMultiplier, 0.0001f)
        assertEquals(6_000L, wave1.rayRefreshInterval)

        val wave10 = WaveCatalog.get(10)
        assertEquals(32, wave10.monsterCount)
        assertEquals(2.8f, wave10.hpMultiplier, 0.0001f)
        assertEquals(1.18f, wave10.moveSpeedMultiplier, 0.0001f)
        assertEquals(3_000L, wave10.rayRefreshInterval)
        assertEquals(5_333L, WaveCatalog.get(3).rayRefreshInterval)
        assertEquals(4_667L, WaveCatalog.get(5).rayRefreshInterval)
        assertEquals(4_000L, WaveCatalog.get(7).rayRefreshInterval)
        assertEquals(3_333L, WaveCatalog.get(9).rayRefreshInterval)
        assertEquals(50, WaveCatalog.WAVE_REWARD_GOLD)

        assertEquals(
            MonsterCatalog.get(MonsterType.BOSS).baseHp * wave10.hpMultiplier,
            2_500f * 2.8f,
            0.01f,
        )
    }

    @Test
    fun `ray cadence decreases smoothly from six to three seconds`() {
        val intervals = (1..WaveCatalog.TOTAL_WAVES).map { WaveCatalog.get(it).rayRefreshInterval }

        assertEquals(6_000L, intervals.first())
        assertEquals(3_000L, intervals.last())
        intervals.zipWithNext().forEach { (current, next) ->
            assertTrue(next < current)
            assertTrue(current - next in 333L..334L)
        }
    }

    @Test
    fun `fast-cleared wave waits until one visible red-beam cycle completes`() {
        assertFalse(
            WaveCompletionRules.canComplete(
                spawningFinished = true,
                killedMonsterCount = 5,
                plannedMonsterCount = 5,
                completedVisibleHazardCycle = false,
            ),
        )
        assertTrue(
            WaveCompletionRules.canComplete(
                spawningFinished = true,
                killedMonsterCount = 5,
                plannedMonsterCount = 5,
                completedVisibleHazardCycle = true,
            ),
        )
    }

    @Test
    fun `visible hazard alone cannot finish a wave before spawning and kills finish`() {
        assertFalse(
            WaveCompletionRules.canComplete(
                spawningFinished = false,
                killedMonsterCount = 5,
                plannedMonsterCount = 5,
                completedVisibleHazardCycle = true,
            ),
        )
        assertFalse(
            WaveCompletionRules.canComplete(
                spawningFinished = true,
                killedMonsterCount = 4,
                plannedMonsterCount = 5,
                completedVisibleHazardCycle = true,
            ),
        )
    }

    @Test
    fun `monster unlock rhythm and final boss sequence are deterministic`() {
        assertTrue(MonsterType.FAST in WaveCatalog.get(2).monsterTypes)
        assertTrue(MonsterType.ARMORED in WaveCatalog.get(4).monsterTypes)
        assertTrue(MonsterType.SELF_DESTRUCT in WaveCatalog.get(6).monsterTypes)
        assertTrue(MonsterType.ACID in WaveCatalog.get(7).monsterTypes)
        assertTrue(MonsterType.ELITE in WaveCatalog.get(8).monsterTypes)

        val bossWave = WaveCatalog.get(10)
        val sequence = WaveCatalog.spawnSequence(bossWave)
        assertEquals(32, sequence.size)
        assertEquals(1, sequence.count { it == MonsterType.BOSS })
        assertEquals(MonsterType.BOSS, sequence.last())
    }

    @Test
    fun `navigation tries a side route when the forward step is obstructed`() {
        val next =
            MonsterNavigation.nextPosition(
                x = 0f,
                z = 0f,
                targetX = 0f,
                targetZ = 10f,
                distance = 1f,
                preferredSide = 1,
                isBlocked = { x, z -> kotlin.math.abs(x) < 0.1f && z > 0.5f },
            )
        assertFalse(kotlin.math.abs(next.first) < 0.1f)
        assertTrue(next.second > 0f)
    }

    @Test
    fun `hard navigation fallback always advances toward siege target`() {
        val output = FloatArray(2)

        MonsterNavigation.forceProgressInto(
            x = -1f,
            z = -5f,
            targetX = -0.5f,
            targetZ = 0f,
            distance = 0.25f,
            output = output,
        )

        assertTrue(output[0] > -1f)
        assertTrue(output[1] > -5f)
        val movedX = output[0] + 1f
        val movedZ = output[1] + 5f
        assertEquals(0.25f, kotlin.math.sqrt(movedX * movedX + movedZ * movedZ), 0.0001f)
    }

    @Test
    fun `real obstacle pathfinder creates a persistent side waypoint`() {
        val obstacle =
            ObstacleBounds(
                UUID.randomUUID(),
                GroundBounds(minX = -0.45f, maxX = 0.45f, minZ = -3.2f, maxZ = -2.2f),
            )
        val found =
            MonsterNavigation.firstBlockingObstacle(
                x = 0f,
                z = -5f,
                targetX = 0f,
                targetZ = 0f,
                clearance = 0.1f,
                obstacles = listOf(obstacle),
            )
        assertEquals(obstacle, found)

        val output = FloatArray(2)
        assertTrue(
            MonsterNavigation.planDetourWaypointInto(
                x = 0f,
                z = -5f,
                targetX = 0f,
                targetZ = 0f,
                obstacle = obstacle,
                clearance = 0.1f,
                preferredSide = 1,
                isBlocked = { x, z -> obstacle.bounds.contains(x, z) },
                output = output,
            ),
        )
        assertTrue(kotlin.math.abs(output[0]) > 0.45f)
        assertTrue(output[1] < -2.2f)
        assertFalse(obstacle.bounds.contains(output[0], output[1]))
    }

    @Test
    fun `pathfinder escapes when a refreshed room anchor contains the monster`() {
        val obstacle =
            ObstacleBounds(
                UUID.randomUUID(),
                GroundBounds(minX = -0.5f, maxX = 0.5f, minZ = -3.5f, maxZ = -2.5f),
            )
        val output = FloatArray(2)

        assertTrue(
            MonsterNavigation.planDetourWaypointInto(
                x = 0f,
                z = -3f,
                targetX = 0f,
                targetZ = 0f,
                obstacle = obstacle,
                clearance = 0.05f,
                preferredSide = -1,
                isBlocked = { _, _ -> false },
                output = output,
            ),
        )
        assertTrue(output[0] < -0.5f || output[0] > 0.5f || output[1] < -3.5f || output[1] > -2.5f)
    }

    @Test
    fun `physical wall containing siege target is not treated as a detour obstacle`() {
        val wallObstacle =
            ObstacleBounds(
                UUID.randomUUID(),
                GroundBounds(minX = -1.2f, maxX = 1.2f, minZ = -0.1f, maxZ = 0.1f),
            )

        assertEquals(
            null,
            MonsterNavigation.firstBlockingObstacle(
                x = 0f,
                z = -5f,
                targetX = 0f,
                targetZ = 0f,
                clearance = 0.05f,
                obstacles = listOf(wallObstacle),
            ),
        )
    }

    @Test
    fun `persistent waypoints carry a monster around furniture to the wall`() {
        val obstacle =
            ObstacleBounds(
                UUID.randomUUID(),
                GroundBounds(minX = -0.5f, maxX = 0.5f, minZ = -3.4f, maxZ = -2.2f),
            )
        val obstacles = listOf(obstacle)
        var x = 0f
        var z = -5f
        var hasWaypoint = false
        var waypointX = 0f
        var waypointZ = 0f
        val plan = FloatArray(2)
        val step = FloatArray(2)
        repeat(240) {
            if (hasWaypoint && MonsterNavigation.hasReachedWaypoint(x, z, waypointX, waypointZ, 0.06f)) {
                hasWaypoint = false
            }
            if (!hasWaypoint) {
                val blocker =
                    MonsterNavigation.firstBlockingObstacle(
                        x, z, 0f, 0f, 0.1f, obstacles,
                    )
                if (blocker != null) {
                    assertTrue(
                        MonsterNavigation.planDetourWaypointInto(
                            x = x,
                            z = z,
                            targetX = 0f,
                            targetZ = 0f,
                            obstacle = blocker,
                            clearance = 0.1f,
                            preferredSide = 1,
                            isBlocked = { px, pz ->
                                px >= -0.6f && px <= 0.6f && pz >= -3.5f && pz <= -2.1f
                            },
                            output = plan,
                        ),
                    )
                    waypointX = plan[0]
                    waypointZ = plan[1]
                    hasWaypoint = true
                }
            }
            val targetX = if (hasWaypoint) waypointX else 0f
            val targetZ = if (hasWaypoint) waypointZ else 0f
            MonsterNavigation.nextPositionInto(
                x = x,
                z = z,
                targetX = targetX,
                targetZ = targetZ,
                distance = 0.05f,
                preferredSide = 1,
                isBlocked = { px, pz ->
                    px >= -0.6f && px <= 0.6f && pz >= -3.5f && pz <= -2.1f
                },
                output = step,
            )
            x = step[0]
            z = step[1]
        }

        assertTrue("Expected attacker to reach wall, final=($x,$z)", kotlin.math.sqrt(x * x + z * z) < 0.15f)
    }

    @Test
    fun `spawn candidates scatter across width and depth instead of forming one row`() {
        val boundary = SceneRect(ScenePoint(0f, 0f, -3f), width = 2.4f, depth = 0f)
        val candidates =
            (0 until 16).map { spawnOrdinal ->
                MonsterSpawnRules.candidate(
                    boundary = boundary,
                    spawnOrdinal = spawnOrdinal,
                    attemptIndex = 0,
                    maxDepthRetreatMeters = 1f,
                )
            }

        assertEquals(candidates.size, candidates.map { it.x to it.z }.toSet().size)
        assertTrue(candidates.all { it.x in -1.2f..1.2f })
        assertTrue(candidates.all { it.z in -3f..-2f })
        assertTrue(candidates.maxOf { it.x } - candidates.minOf { it.x } > 1.2f)
        assertTrue(candidates.maxOf { it.z } - candidates.minOf { it.z } > 0.5f)
    }

    @Test
    fun `spawn side maps to a distributed wall target`() {
        val boundary = SceneRect(ScenePoint(0f, 0f, -5f), width = 2.4f, depth = 0f)
        val wall = SceneRect(ScenePoint(0f, 0f, 0f), width = 2.4f, depth = 0.18f)
        val left =
            MonsterSpawnRules.wallTargetForSpawn(
                wall,
                boundary,
                boundary.pointAt(-1f),
                edgePaddingMeters = 0.1f,
            )
        val right =
            MonsterSpawnRules.wallTargetForSpawn(
                wall,
                boundary,
                boundary.pointAt(1f),
                edgePaddingMeters = 0.1f,
            )

        assertTrue(left.x in -0.7f..-0.4f)
        assertTrue(right.x in 0.4f..0.7f)
        assertEquals(0f, left.z, 0.0001f)
        assertEquals(0f, right.z, 0.0001f)
        assertEquals(0.60f, MonsterSpawnRules.WALL_TARGET_CONVERGENCE_RATIO, 0.0001f)
    }

    @Test
    fun `wave spawns are visibly staggered`() {
        assertEquals(800L, WaveCatalog.SPAWN_INTERVAL_MILLIS)
        assertTrue(WaveCatalog.SPAWN_INTERVAL_MILLIS <= 1_000L)
    }

    private fun assertConfig(
        type: MonsterType,
        hp: Float,
        speed: Float,
        siegeDamage: Int,
        reward: Int,
    ) {
        val config = MonsterCatalog.get(type)
        assertEquals(hp, config.baseHp, 0.0001f)
        assertEquals(speed, config.moveSpeed, 0.0001f)
        assertEquals(siegeDamage, config.siegeDamage)
        assertEquals(reward, config.killGoldReward)
    }
}
