package com.gamextra4u.fexdroid.storage

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PermissionManager(
    private val context: Context
) {

    private val _permissionState = MutableStateFlow<PermissionState>(PermissionState.Idle)
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    /**
     * Check if all required permissions are granted
     */
    fun hasAllRequiredPermissions(): Boolean {
        return getMissingPermissions().isEmpty()
    }

    /**
     * Get list of missing permissions
     */
    fun getMissingPermissions(): List<RequiredPermission> {
        val missing = mutableListOf<RequiredPermission>()
        
        // Check storage permissions
        if (!hasStoragePermission()) {
            missing.add(RequiredPermission.STORAGE)
        }
        
        // Check internet permission
        if (!hasInternetPermission()) {
            missing.add(RequiredPermission.INTERNET)
        }
        
        // Check network state permission
        if (!hasNetworkStatePermission()) {
            missing.add(RequiredPermission.NETWORK_STATE)
        }

        // Android 13+ specific permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasMediaPermissions()) {
                missing.add(RequiredPermission.MEDIA)
            }
        }

        return missing
    }

    /**
     * Request all required permissions
     */
    fun requestPermissions(activity: Activity, callback: (PermissionRequestResult) -> Unit) {
        val missingPermissions = getMissingPermissions()
        
        if (missingPermissions.isEmpty()) {
            callback(PermissionRequestResult.AllGranted)
            return
        }

        _permissionState.value = PermissionState.Requesting(missingPermissions.map { it.name })

        if (missingPermissions.contains(RequiredPermission.STORAGE) && 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            
            // For Android 11+, we need to request MANAGE_EXTERNAL_STORAGE
            // This must be done via Settings panel
            _permissionState.value = PermissionState.ManageExternalStorageRequired
            callback(PermissionRequestResult.ManageExternalStorageRequired)
            return
        }

        // Request runtime permissions
        val permissionsToRequest = missingPermissions.mapNotNull { permission ->
            when (permission) {
                RequiredPermission.STORAGE -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                } else null
                RequiredPermission.INTERNET -> Manifest.permission.INTERNET
                RequiredPermission.NETWORK_STATE -> Manifest.permission.ACCESS_NETWORK_STATE
                RequiredPermission.MEDIA -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else null
            }
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            callback(PermissionRequestResult.AllGranted)
        } else {
            requestRuntimePermissions(activity, permissionsToRequest, callback)
        }
    }

    private fun requestRuntimePermissions(
        activity: Activity, 
        permissions: Array<String>, 
        callback: (PermissionRequestResult) -> Unit
    ) {
        // This is a simplified version - in a real app you'd use ActivityResultContracts
        // For now, we'll simulate permission requests
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // In a real implementation, you would use:
                // val result = activityResultLauncher.launch(permissions)
                
                // Simulate permission check
                val grantedPermissions = permissions.filter { permission ->
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                }
                
                val deniedPermissions = permissions.filter { permission ->
                    ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
                }

                if (deniedPermissions.isEmpty()) {
                    _permissionState.value = PermissionState.Granted
                    callback(PermissionRequestResult.AllGranted)
                } else {
                    _permissionState.value = PermissionState.Denied(deniedPermissions)
                    callback(PermissionRequestResult.PartiallyGranted(grantedPermissions, deniedPermissions))
                }
            } catch (e: Exception) {
                _permissionState.value = PermissionState.Error(e.message ?: "Permission request failed")
                callback(PermissionRequestResult.Error(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * Handle permission request result
     */
    fun handlePermissionResult(
        permissions: Array<String>,
        grantResults: IntArray
    ): PermissionRequestResult {
        val deniedPermissions = permissions.filterIndexed { index, _ ->
            grantResults[index] != PackageManager.PERMISSION_GRANTED
        }

        return when {
            deniedPermissions.isEmpty() -> {
                _permissionState.value = PermissionState.Granted
                PermissionRequestResult.AllGranted
            }
            deniedPermissions.contains(RequiredPermission.STORAGE.name) && 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                _permissionState.value = PermissionState.ManageExternalStorageRequired
                PermissionRequestResult.ManageExternalStorageRequired
            }
            else -> {
                _permissionState.value = PermissionState.Denied(deniedPermissions)
                PermissionRequestResult.PartiallyGranted(
                    permissions.filterIndexed { index, _ -> 
                        grantResults[index] == PackageManager.PERMISSION_GRANTED 
                    },
                    deniedPermissions
                )
            }
        }
    }

    /**
     * Check if we have storage permission
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ uses granular permissions, we primarily need READ_MEDIA_* 
            true // READ_MEDIA_* permissions are requested via manifest
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12 requires MANAGE_EXTERNAL_STORAGE
            Environment.isExternalStorageManager()
        } else {
            // Android 10 and below use legacy storage permissions
            ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if we have internet permission
     */
    private fun hasInternetPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.INTERNET
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if we have network state permission
     */
    private fun hasNetworkStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if we have media permissions (Android 13+)
     */
    private fun hasMediaPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }
    }

    /**
     * Get human-readable permission requirements
     */
    fun getPermissionRequirements(): List<PermissionRequirement> {
        return listOf(
            PermissionRequirement(
                permission = RequiredPermission.STORAGE,
                title = "Storage Access",
                description = "Required to store and access game files and save data",
                required = true
            ),
            PermissionRequirement(
                permission = RequiredPermission.INTERNET,
                title = "Internet Access",
                description = "Required to connect to Steam and download games",
                required = true
            ),
            PermissionRequirement(
                permission = RequiredPermission.NETWORK_STATE,
                title = "Network Status",
                description = "Required to check network connectivity for online features",
                required = false
            ),
            PermissionRequirement(
                permission = RequiredPermission.MEDIA,
                title = "Media Access",
                description = "Required to access images and videos for game content (Android 13+)",
                required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            )
        )
    }
}

/**
 * Required permissions enumeration
 */
enum class RequiredPermission(val permissionName: String) {
    STORAGE("android.permission.WRITE_EXTERNAL_STORAGE"),
    INTERNET("android.permission.INTERNET"),
    NETWORK_STATE("android.permission.ACCESS_NETWORK_STATE"),
    MEDIA("android.permission.READ_MEDIA_IMAGES")
}

/**
 * Permission states
 */
sealed class PermissionState {
    object Idle : PermissionState()
    data class Requesting(val permissions: List<String>) : PermissionState()
    object Granted : PermissionState()
    data class Denied(val permissions: List<String>) : PermissionState()
    object ManageExternalStorageRequired : PermissionState()
    data class Error(val message: String) : PermissionState()
}

/**
 * Permission request results
 */
sealed class PermissionRequestResult {
    object AllGranted : PermissionRequestResult()
    data class PartiallyGranted(
        val granted: List<String>,
        val denied: List<String>
    ) : PermissionRequestResult()
    object ManageExternalStorageRequired : PermissionRequestResult()
    data class Error(val message: String) : PermissionRequestResult()
}

/**
 * Permission requirement information
 */
data class PermissionRequirement(
    val permission: RequiredPermission,
    val title: String,
    val description: String,
    val required: Boolean
)