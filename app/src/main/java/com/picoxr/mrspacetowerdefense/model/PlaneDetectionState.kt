package com.picoxr.mrspacetowerdefense.model

sealed interface PlaneDetectionState {
    data object Ready : PlaneDetectionState

    data object Starting : PlaneDetectionState

    data class Running(val detectedPlaneCount: Int) : PlaneDetectionState

    data object Stopped : PlaneDetectionState

    data class PermissionDenied(val deniedPermissions: Set<String>) : PlaneDetectionState

    data class Failed(val message: String) : PlaneDetectionState
}
