package com.yantkys.memonow.backup

sealed interface BackupState {
    data object Idle : BackupState
    data class Picking(val export: Boolean) : BackupState
    data class Processing(val label: String) : BackupState
    data class Confirm(val plan: RestorePlan) : BackupState
    data class Finished(val message: String, val success: Boolean) : BackupState
}
