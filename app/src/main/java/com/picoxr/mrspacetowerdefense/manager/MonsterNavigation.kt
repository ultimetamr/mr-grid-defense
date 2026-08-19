package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.ObstacleBounds
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal object MonsterNavigation {
    private const val GEOMETRY_EPSILON = 0.0001f
    private const val WAYPOINT_EDGE_CLEARANCE_METERS = 0.08f

    fun nextPosition(
        x: Float,
        z: Float,
        targetX: Float,
        targetZ: Float,
        distance: Float,
        preferredSide: Int,
        isBlocked: (Float, Float) -> Boolean,
    ): Pair<Float, Float> {
        val output = FloatArray(2)
        nextPositionInto(x, z, targetX, targetZ, distance, preferredSide, isBlocked, output)
        return output[0] to output[1]
    }

    fun nextPositionInto(
        x: Float,
        z: Float,
        targetX: Float,
        targetZ: Float,
        distance: Float,
        preferredSide: Int,
        isBlocked: (Float, Float) -> Boolean,
        output: FloatArray,
    ) {
        require(output.size >= 2)
        output[0] = x
        output[1] = z
        if (distance <= 0f) return
        val dx = targetX - x
        val dz = targetZ - z
        val length = sqrt(dx * dx + dz * dz)
        if (length <= 0.0001f) return
        val forwardX = dx / length
        val forwardZ = dz / length
        fun tryDirection(directionX: Float, directionZ: Float): Boolean {
            val nextX = x + directionX * distance.coerceAtMost(length)
            val nextZ = z + directionZ * distance.coerceAtMost(length)
            if (isBlocked(nextX, nextZ)) return false
            output[0] = nextX
            output[1] = nextZ
            return true
        }

        if (tryDirection(forwardX, forwardZ)) return
        var sideX = forwardX - forwardZ * preferredSide
        var sideZ = forwardZ + forwardX * preferredSide
        var sideLength = sqrt(sideX * sideX + sideZ * sideZ).coerceAtLeast(0.0001f)
        if (tryDirection(sideX / sideLength, sideZ / sideLength)) return
        sideX = forwardX + forwardZ * preferredSide
        sideZ = forwardZ - forwardX * preferredSide
        sideLength = sqrt(sideX * sideX + sideZ * sideZ).coerceAtLeast(0.0001f)
        if (tryDirection(sideX / sideLength, sideZ / sideLength)) return
        if (tryDirection(-forwardZ * preferredSide, forwardX * preferredSide)) return
        tryDirection(forwardZ * preferredSide, -forwardX * preferredSide)
    }

    /**
     * Last-resort forward progress for a room scan or monster cluster that reports every
     * steering direction blocked. This never allocates and always advances toward target.
     */
    fun forceProgressInto(
        x: Float,
        z: Float,
        targetX: Float,
        targetZ: Float,
        distance: Float,
        output: FloatArray,
    ) {
        require(output.size >= 2)
        output[0] = x
        output[1] = z
        if (distance <= 0f) return
        val dx = targetX - x
        val dz = targetZ - z
        val length = sqrt(dx * dx + dz * dz)
        if (length <= 0.0001f) return
        val step = distance.coerceAtMost(length)
        output[0] = x + dx / length * step
        output[1] = z + dz / length * step
    }

    /**
     * Finds the first real-world obstacle crossed by the route to the siege target.
     * Obstacles containing the target are ignored because the virtual wall may be placed
     * against a detected physical wall and monsters must still be able to enter siege range.
     */
    fun firstBlockingObstacle(
        x: Float,
        z: Float,
        targetX: Float,
        targetZ: Float,
        clearance: Float,
        obstacles: List<ObstacleBounds>,
    ): ObstacleBounds? {
        var nearest: ObstacleBounds? = null
        var nearestEntry = Float.POSITIVE_INFINITY
        for (obstacle in obstacles) {
            val bounds = obstacle.bounds
            val minX = bounds.minX - clearance
            val maxX = bounds.maxX + clearance
            val minZ = bounds.minZ - clearance
            val maxZ = bounds.maxZ + clearance
            if (targetX >= minX && targetX <= maxX && targetZ >= minZ && targetZ <= maxZ) continue
            val entry = segmentEntryTime(x, z, targetX, targetZ, minX, maxX, minZ, maxZ) ?: continue
            if (entry < nearestEntry) {
                nearestEntry = entry
                nearest = obstacle
            }
        }
        return nearest
    }

    /**
     * Builds one persistent local pathfinding waypoint around an expanded obstacle AABB.
     * Replanning after the waypoint is reached naturally walks around a second corner for
     * wide furniture instead of oscillating between one-frame steering directions.
     */
    fun planDetourWaypointInto(
        x: Float,
        z: Float,
        targetX: Float,
        targetZ: Float,
        obstacle: ObstacleBounds,
        clearance: Float,
        preferredSide: Int,
        isBlocked: (Float, Float) -> Boolean,
        output: FloatArray,
    ): Boolean {
        require(output.size >= 2)
        output[0] = x
        output[1] = z
        val bounds = obstacle.bounds
        val minX = bounds.minX - clearance
        val maxX = bounds.maxX + clearance
        val minZ = bounds.minZ - clearance
        val maxZ = bounds.maxZ + clearance
        val edge = WAYPOINT_EDGE_CLEARANCE_METERS
        val inside = x >= minX && x <= maxX && z >= minZ && z <= maxZ
        var bestX = x
        var bestZ = z
        var bestScore = Float.POSITIVE_INFINITY
        val targetDx = targetX - x
        val targetDz = targetZ - z

        fun consider(candidateX: Float, candidateZ: Float, requireVisible: Boolean) {
            if (isBlocked(candidateX, candidateZ)) return
            if (
                requireVisible &&
                segmentEntryTime(x, z, candidateX, candidateZ, minX, maxX, minZ, maxZ) != null
            ) return
            val toCandidateX = candidateX - x
            val toCandidateZ = candidateZ - z
            val firstLeg = sqrt(toCandidateX * toCandidateX + toCandidateZ * toCandidateZ)
            if (firstLeg <= GEOMETRY_EPSILON) return
            val secondDx = targetX - candidateX
            val secondDz = targetZ - candidateZ
            val secondLeg = sqrt(secondDx * secondDx + secondDz * secondDz)
            val cross = targetDx * toCandidateZ - targetDz * toCandidateX
            val wrongSide = if (preferredSide >= 0) cross < 0f else cross > 0f
            val sidePenalty = if (wrongSide) 0.05f else 0f
            val targetStillBlocked =
                segmentEntryTime(
                    candidateX,
                    candidateZ,
                    targetX,
                    targetZ,
                    minX,
                    maxX,
                    minZ,
                    maxZ,
                ) != null
            val remainingObstaclePenalty = if (targetStillBlocked) 0.25f else 0f
            val score = firstLeg + secondLeg + sidePenalty + remainingObstaclePenalty
            if (score < bestScore) {
                bestScore = score
                bestX = candidateX
                bestZ = candidateZ
            }
        }

        if (inside) {
            // Escape through the closest expanded edge first. This handles anchors that are
            // discovered after a monster spawned, leaving its current point inside the AABB.
            consider(minX - edge, z.coerceIn(minZ - edge, maxZ + edge), requireVisible = false)
            consider(maxX + edge, z.coerceIn(minZ - edge, maxZ + edge), requireVisible = false)
            consider(x.coerceIn(minX - edge, maxX + edge), minZ - edge, requireVisible = false)
            consider(x.coerceIn(minX - edge, maxX + edge), maxZ + edge, requireVisible = false)
        } else {
            consider(minX - edge, minZ - edge, requireVisible = true)
            consider(minX - edge, maxZ + edge, requireVisible = true)
            consider(maxX + edge, minZ - edge, requireVisible = true)
            consider(maxX + edge, maxZ + edge, requireVisible = true)
        }
        if (!bestScore.isFinite()) return false
        output[0] = bestX
        output[1] = bestZ
        return true
    }

    fun hasReachedWaypoint(
        x: Float,
        z: Float,
        waypointX: Float,
        waypointZ: Float,
        thresholdMeters: Float,
    ): Boolean {
        val dx = waypointX - x
        val dz = waypointZ - z
        return dx * dx + dz * dz <= thresholdMeters * thresholdMeters
    }

    private fun segmentEntryTime(
        startX: Float,
        startZ: Float,
        endX: Float,
        endZ: Float,
        minX: Float,
        maxX: Float,
        minZ: Float,
        maxZ: Float,
    ): Float? {
        val dx = endX - startX
        val dz = endZ - startZ
        var enter = 0f
        var exit = 1f
        if (abs(dx) <= GEOMETRY_EPSILON) {
            if (startX < minX || startX > maxX) return null
        } else {
            val inverse = 1f / dx
            val first = (minX - startX) * inverse
            val second = (maxX - startX) * inverse
            enter = max(enter, min(first, second))
            exit = min(exit, max(first, second))
            if (enter > exit) return null
        }
        if (abs(dz) <= GEOMETRY_EPSILON) {
            if (startZ < minZ || startZ > maxZ) return null
        } else {
            val inverse = 1f / dz
            val first = (minZ - startZ) * inverse
            val second = (maxZ - startZ) * inverse
            enter = max(enter, min(first, second))
            exit = min(exit, max(first, second))
            if (enter > exit) return null
        }
        return enter.takeIf { exit >= 0f && it <= 1f }
    }
}
