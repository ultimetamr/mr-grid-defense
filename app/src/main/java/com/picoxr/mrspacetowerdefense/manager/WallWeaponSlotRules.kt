package com.picoxr.mrspacetowerdefense.manager

/** Allocation-free occupancy rules for the nine fixed wall weapon mounts. */
object WallWeaponSlotRules {
    const val MAX_SLOTS = 9

    /** Center-out order, with every final mount lying on one evenly spaced wall line. */
    fun lateralOffset(placementIndex: Int, slotSpacing: Float): Float {
        require(placementIndex in 0 until MAX_SLOTS)
        require(slotSpacing > 0f && slotSpacing.isFinite())
        val step = (placementIndex + 1) / 2
        val direction =
            when {
                placementIndex == 0 -> 0
                placementIndex % 2 == 1 -> -1
                else -> 1
            }
        return direction * step * slotSpacing
    }

    fun nextFreeSlot(occupiedMask: Int): Int? {
        for (index in 0 until MAX_SLOTS) {
            if (occupiedMask and (1 shl index) == 0) return index
        }
        return null
    }
}
