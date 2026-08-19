package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.SceneRect

object GridRules {
    const val GRID_COUNT = 9
    const val WARNING_DURATION_SECONDS = 1f
    const val BEAM_RAISE_DURATION_SECONDS = 0.3f
    const val BEAM_ACTIVE_DURATION_SECONDS = 1f
    const val WARNING_FLASHES_PER_SECOND = 2f
    const val BEAM_HEIGHT_METERS = 2f
    const val BEAM_DIAMETER_METERS = 0.5f
    const val BEAM_RADIUS_METERS = BEAM_DIAMETER_METERS / 2f

    fun nextSafeIndex(previousIndex: Int?, randomValue: Int): Int {
        if (previousIndex == null) return Math.floorMod(randomValue, GRID_COUNT)
        require(previousIndex in 0 until GRID_COUNT)
        val slot = Math.floorMod(randomValue, GRID_COUNT - 1)
        return if (slot >= previousIndex) slot + 1 else slot
    }

    fun cellIndexAt(cells: List<SceneRect>, x: Float, z: Float): Int? {
        require(cells.size == GRID_COUNT) { "The safe grid must contain exactly nine cells" }
        return cells.indexOfFirst { cell ->
            cell.containsHorizontal(x, z)
        }.takeIf { it >= 0 }
    }

    /** The complete 3x3 footprint is the player's movement area between hazards. */
    fun isInsideActivityArea(cells: List<SceneRect>, x: Float, z: Float): Boolean =
        cellIndexAt(cells, x, z) != null

    /**
     * Returns the dangerous beam whose rendered 50 cm cylinder intersects the horizontal
     * HMD helmet circle. Height is intentionally ignored: PICO tracking-origin changes can
     * shift world Y independently from the calibrated floor, while a player standing in a
     * floor-to-ceiling energy column must still be hit. Being outside the green cell alone
     * is not lethal; the head's horizontal proxy must touch a visible red column.
     */
    fun touchedDangerBeamIndex(
        cells: List<SceneRect>,
        safeIndex: Int,
        helmetX: Float,
        helmetZ: Float,
        helmetRadius: Float,
    ): Int? {
        require(cells.size == GRID_COUNT)
        require(safeIndex in 0 until GRID_COUNT)
        require(helmetRadius >= 0f && helmetRadius.isFinite())
        if (!helmetX.isFinite() || !helmetZ.isFinite()) return null
        for (index in cells.indices) {
            if (index == safeIndex) continue
            if (helmetCircleTouchesBeam(cells[index], helmetX, helmetZ, helmetRadius)) {
                return index
            }
        }
        return null
    }

    /** Red beam contact is always fatal; permanent shields never suppress this hazard. */
    fun isLethalBeamContact(touchedDangerBeamIndex: Int?): Boolean =
        touchedDangerBeamIndex != null

    private fun helmetCircleTouchesBeam(
        cell: SceneRect,
        helmetX: Float,
        helmetZ: Float,
        helmetRadius: Float,
    ): Boolean {
        val dx = helmetX - cell.center.x
        val dz = helmetZ - cell.center.z
        val combinedRadius = BEAM_RADIUS_METERS + helmetRadius
        return dx * dx + dz * dz <= combinedRadius * combinedRadius
    }

    fun beamProgress(elapsedSeconds: Float): Float =
        (elapsedSeconds / BEAM_RAISE_DURATION_SECONDS).coerceIn(0f, 1f)

    /**
     * Remaining retracted time after the warning phase. The configured wave interval is
     * the complete safe-cell movement window, so warning + cooldown equals that interval.
     */
    fun warningDurationSeconds(bonus: Float): Float =
        WARNING_DURATION_SECONDS * (1f + bonus.coerceAtLeast(0f))

    fun rayTriggerDelaySeconds(
        rayIntervalSeconds: Float,
        safeWindowBonus: Float = 0f,
        warningDurationSeconds: Float = WARNING_DURATION_SECONDS,
    ): Float {
        val completeMovementWindow = rayIntervalSeconds * (1f + safeWindowBonus.coerceAtLeast(0f))
        return (completeMovementWindow - warningDurationSeconds.coerceAtLeast(0f)).coerceAtLeast(0f)
    }

    fun warningFlashOn(elapsedSeconds: Float): Boolean {
        val togglesPerSecond = WARNING_FLASHES_PER_SECOND * 2f
        return (elapsedSeconds * togglesPerSecond).toInt() % 2 == 0
    }
}
