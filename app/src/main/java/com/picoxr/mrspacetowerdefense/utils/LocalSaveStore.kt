package com.picoxr.mrspacetowerdefense.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.picoxr.mrspacetowerdefense.model.PermanentProgress
import com.picoxr.mrspacetowerdefense.model.PermanentUpgradeType
import java.security.MessageDigest

/** SharedPreferences wrapper with range repair and a checksum against casual editing. */
class LocalSaveStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): PermanentProgress {
        val rawV2 =
            PermanentProgress(
                totalCrystals = preferences.getInt(KEY_CRYSTALS, 0).coerceIn(0, MAX_CRYSTALS),
                upgradeLevels = decodeLevels(preferences.getString(KEY_LEVELS, null)),
                highestWave = preferences.getInt(KEY_WAVE, 0).coerceIn(0, 10),
                highestKills = preferences.getInt(KEY_KILLS, 0).coerceIn(0, MAX_KILLS),
            )
        val v2Checksum = preferences.getString(KEY_CHECKSUM_V2, null)
        if (v2Checksum != null && v2Checksum == SaveChecksum.calculate(rawV2)) {
            val repaired = SaveChecksum.repair(rawV2)
            if (readValuesDifferFrom(repaired)) save(repaired)
            return repaired
        }

        // One-time migration preserves crystals and records from the old single-level save.
        val legacyLevel = preferences.getInt(KEY_LEGACY_LEVEL, 1).coerceIn(1, MAX_LEGACY_GROWTH_LEVEL)
        val legacyChecksum = preferences.getString(KEY_CHECKSUM_V1, null)
        val legacyValid =
            legacyChecksum != null &&
                legacyChecksum == LegacySaveChecksum.calculate(
                    rawV2.totalCrystals,
                    legacyLevel,
                    rawV2.highestWave,
                    rawV2.highestKills,
                )
        val migrated =
            if (legacyValid) {
                val migratedLevel = (legacyLevel - 1).coerceIn(0, MAX_UPGRADE_LEVEL)
                rawV2.copy(
                    upgradeLevels =
                        if (migratedLevel == 0) emptyMap()
                        else mapOf(PermanentUpgradeType.TOWER_DAMAGE to migratedLevel),
                )
            } else {
                PermanentProgress()
            }
        save(migrated)
        return migrated
    }

    fun save(progress: PermanentProgress) {
        val repaired = SaveChecksum.repair(progress)
        // commit=true makes a crystal upgrade durable before the user can immediately exit.
        preferences.edit(commit = true) {
            putInt(KEY_CRYSTALS, repaired.totalCrystals)
            putString(KEY_LEVELS, encodeLevels(repaired.upgradeLevels))
            putInt(KEY_WAVE, repaired.highestWave)
            putInt(KEY_KILLS, repaired.highestKills)
            putString(KEY_CHECKSUM_V2, SaveChecksum.calculate(repaired))
        }
    }

    private fun readValuesDifferFrom(progress: PermanentProgress): Boolean =
        preferences.getInt(KEY_CRYSTALS, 0) != progress.totalCrystals ||
            decodeLevels(preferences.getString(KEY_LEVELS, null)) != progress.upgradeLevels ||
            preferences.getInt(KEY_WAVE, 0) != progress.highestWave ||
            preferences.getInt(KEY_KILLS, 0) != progress.highestKills

    private fun encodeLevels(levels: Map<PermanentUpgradeType, Int>): String =
        PermanentUpgradeType.entries.joinToString(",") { type -> "${type.name}:${levels[type] ?: 0}" }

    private fun decodeLevels(encoded: String?): Map<PermanentUpgradeType, Int> {
        if (encoded.isNullOrBlank()) return emptyMap()
        val parsed = linkedMapOf<PermanentUpgradeType, Int>()
        encoded.split(',').forEach { entry ->
            val parts = entry.split(':', limit = 2)
            val type = parts.getOrNull(0)?.let { runCatching { PermanentUpgradeType.valueOf(it) }.getOrNull() }
            val level = parts.getOrNull(1)?.toIntOrNull()
            if (type != null && level != null && level > 0) parsed[type] = level.coerceAtMost(MAX_UPGRADE_LEVEL)
        }
        return parsed
    }

    companion object {
        private const val FILE_NAME = "mr_grid_defense_save"
        private const val KEY_CRYSTALS = "crystals"
        private const val KEY_LEGACY_LEVEL = "growth_level"
        private const val KEY_LEVELS = "growth_levels_v2"
        private const val KEY_WAVE = "highest_wave"
        private const val KEY_KILLS = "highest_kills"
        private const val KEY_CHECKSUM_V1 = "checksum_v1"
        private const val KEY_CHECKSUM_V2 = "checksum_v2"
        const val MAX_CRYSTALS = 99_999_999
        const val MAX_UPGRADE_LEVEL = 10
        const val MAX_LEGACY_GROWTH_LEVEL = 100
        const val MAX_KILLS = 1_000_000
    }
}

internal object SaveChecksum {
    private const val SALT = "MRGridDefense::PICO::v1"

    fun repair(value: PermanentProgress): PermanentProgress =
        PermanentProgress(
            totalCrystals = value.totalCrystals.coerceIn(0, LocalSaveStore.MAX_CRYSTALS),
            upgradeLevels =
                value.upgradeLevels.mapValues { (_, level) ->
                    level.coerceIn(0, LocalSaveStore.MAX_UPGRADE_LEVEL)
                }.filterValues { it > 0 },
            highestWave = value.highestWave.coerceIn(0, 10),
            highestKills = value.highestKills.coerceIn(0, LocalSaveStore.MAX_KILLS),
        )

    fun calculate(value: PermanentProgress): String {
        val repaired = repair(value)
        val levels = PermanentUpgradeType.entries.joinToString(",") { "${it.name}:${repaired.levelOf(it)}" }
        val canonical = "$SALT|${repaired.totalCrystals}|$levels|${repaired.highestWave}|${repaired.highestKills}"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

private object LegacySaveChecksum {
    private const val SALT = "MRGridDefense::PICO::v1"

    fun calculate(crystals: Int, growthLevel: Int, wave: Int, kills: Int): String {
        val canonical = "$SALT|$crystals|$growthLevel|$wave|$kills"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
