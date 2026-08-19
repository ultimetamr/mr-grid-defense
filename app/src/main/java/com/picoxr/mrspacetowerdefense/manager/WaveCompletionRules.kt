package com.picoxr.mrspacetowerdefense.manager

/** Pure completion gate shared by runtime code and host-side regression tests. */
object WaveCompletionRules {
    fun canComplete(
        spawningFinished: Boolean,
        killedMonsterCount: Int,
        plannedMonsterCount: Int,
        completedVisibleHazardCycle: Boolean,
    ): Boolean =
        spawningFinished &&
            killedMonsterCount >= plannedMonsterCount &&
            completedVisibleHazardCycle
}
