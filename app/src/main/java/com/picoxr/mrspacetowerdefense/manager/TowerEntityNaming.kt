package com.picoxr.mrspacetowerdefense.manager

import com.picoxr.mrspacetowerdefense.model.TowerType

/** PICO entity names accept underscores but reject UUID hyphens. */
internal object TowerEntityNaming {
    fun create(type: TowerType, runtimeId: String): String =
        "Tower_${type.name}_${runtimeId.replace('-', '_')}"
}
