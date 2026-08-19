package com.picoxr.mrspacetowerdefense.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionUtils {
    const val SPATIAL_DATA_PERMISSION = "com.picovr.permission.SPATIAL_DATA"

    val runtimePermissions =
        arrayOf(
            Manifest.permission.CAMERA,
            SPATIAL_DATA_PERMISSION,
        )

    fun missingPermissions(context: Context): Set<String> =
        runtimePermissions
            .filterTo(linkedSetOf()) { permission ->
                ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED
            }

    fun hasAllRuntimePermissions(context: Context): Boolean = missingPermissions(context).isEmpty()
}
