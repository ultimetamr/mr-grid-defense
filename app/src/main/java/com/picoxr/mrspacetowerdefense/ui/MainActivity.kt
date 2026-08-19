package com.picoxr.mrspacetowerdefense.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.pico.spatial.ui.platform.stub.delegate.SpatialActivityDelegate
import com.pico.spatial.ui.platform.stub.delegate.spatialActivityDelegate
import com.picoxr.mrspacetowerdefense.R
import com.picoxr.mrspacetowerdefense.databinding.ActivityMainBinding
import com.picoxr.mrspacetowerdefense.extension.enterImmersiveMode
import com.picoxr.mrspacetowerdefense.manager.SpatialManager
import com.picoxr.mrspacetowerdefense.manager.GameManager
import com.picoxr.mrspacetowerdefense.manager.UIManager
import com.picoxr.mrspacetowerdefense.utils.PermissionUtils
import com.picoxr.mrspacetowerdefense.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private val spatialDelegate: SpatialActivityDelegate by spatialActivityDelegate()
    private val viewModel: MainViewModel by viewModels()
    private var permissionDialog: AlertDialog? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys
            if (denied.isEmpty()) {
                permissionDialog?.dismiss()
                if (!isFinishing) viewModel.startPlaneDetection()
            } else {
                viewModel.onPermissionDenied(denied)
                showPermissionFallback()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spatialDelegate.setSpatialContent()
        enterImmersiveMode()

        // The SDK's public initialization entry is Application.launch(::mainApp).
        // This manager owns app-scoped plane tracking without retaining this Activity.
        SpatialManager.initialize(applicationContext)
        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        GameManager.onAppForegrounded()
        UIManager.resumeSpatialTracking()
        enterImmersiveMode()
        if (PermissionUtils.hasAllRuntimePermissions(this)) {
            viewModel.startPlaneDetection()
        }
    }

    override fun onPause() {
        GameManager.onAppBackgrounded()
        UIManager.pauseSpatialTracking()
        viewModel.stopPlaneDetection()
        super.onPause()
    }

    override fun onDestroy() {
        permissionDialog?.dismiss()
        permissionDialog = null
        SpatialManager.releaseSpatialResources()
        super.onDestroy()
    }

    private fun requestRequiredPermissions() {
        val missingPermissions = PermissionUtils.missingPermissions(this)
        if (missingPermissions.isEmpty()) {
            viewModel.startPlaneDetection()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun showPermissionFallback() {
        if (isFinishing || permissionDialog?.isShowing == true) return
        val binding = ActivityMainBinding.inflate(layoutInflater)
        binding.statusText.setText(R.string.permission_status_denied)
        permissionDialog =
            AlertDialog.Builder(this)
                .setView(binding.root)
                .setMessage(R.string.permission_message)
                .setNegativeButton(R.string.continue_limited, null)
                .setPositiveButton(R.string.open_settings) { _, _ -> openAppSettings() }
                .create()
                .also(AlertDialog::show)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}
