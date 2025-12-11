# Game Library Storage and Android Permissions System

## Overview

This document describes the comprehensive game library storage infrastructure and Android permissions system implemented for the FEXDroid Steam app. The system handles storage management, permission requests, game library management, and cleanup operations across different Android versions.

## Components

### 1. StorageManager (`StorageManager.kt`)
**Purpose**: Handles all storage operations and path management

**Key Features**:
- **Storage Paths**: Manages internal/external storage paths for games, downloads, and temp files
- **Symlink Support**: Creates symlinks to Steam game directories for easy access
- **Quota Management**: Monitors storage usage and available space
- **Cross-Version Compatibility**: Handles different Android storage permission schemes
- **Storage Verification**: Tests storage accessibility and write/read operations

**Storage Structure**:
```
GameLibrary/
├── InstalledGames/          # Local game installations
├── Downloads/               # Downloaded content
├── temp/                    # Temporary files
├── steam-common/           # Symlink to Steam common directory
└── steam-workshop/         # Symlink to Steam workshop content
```

### 2. PermissionManager (`PermissionManager.kt`)
**Purpose**: Manages Android runtime permissions across different API levels

**Key Features**:
- **Cross-Version Support**: Handles permissions for Android 11+ and Android 13+
- **Permission Detection**: Identifies missing permissions automatically
- **User-Friendly Dialogs**: Explains why permissions are needed
- **State Management**: Tracks permission request states and results

**Permission Strategy**:
- **Android 10 and below**: Legacy storage permissions (READ/WRITE_EXTERNAL_STORAGE)
- **Android 11-12**: MANAGE_EXTERNAL_STORAGE permission via Settings
- **Android 13+**: Granular media permissions (READ_MEDIA_*)

### 3. GameLibraryManager (`GameLibraryManager.kt`)
**Purpose**: Manages game installations and library metadata

**Key Features**:
- **Game Discovery**: Scans directories for installed games
- **Metadata Management**: Extracts game information from manifests and executables
- **Installation Sources**: Distinguishes between Steam library and local installations
- **Configuration Persistence**: Saves game library state in JSON format
- **Update Detection**: Monitors game changes and updates

**Supported Game Detection**:
- Searches for common executable patterns
- Extracts AppID from Steam manifest files
- Identifies game icons and metadata files
- Calculates installation sizes

### 4. StorageCleanupManager (`StorageCleanupManager.kt`)
**Purpose**: Provides automated storage maintenance and cleanup

**Key Features**:
- **Automated Cleanup**: Removes temporary files, logs, and cache
- **Space Recommendations**: Analyzes storage usage and suggests cleanup actions
- **Smart Cleaning**: Preserves important files while removing unnecessary data
- **Scheduled Maintenance**: Performs periodic cleanup operations

**Cleanup Categories**:
- Temporary files (>7 days old)
- Log files (>30 days old)
- Cache directories (size-based)
- Steam temporary files
- Old download files

## Android Permissions Strategy

### Manifest Permissions
```xml
<!-- Core permissions -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Storage permissions with version compatibility -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" 
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" 
    android:maxSdkVersion="28" />

<!-- Android 11+ permissions -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" 
    tools:ignore="ScopedStorage" />

<!-- Android 13+ granular media permissions -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
```

### Permission Flow
1. **App Startup**: Check for existing permissions
2. **Missing Permissions**: Request via appropriate method based on Android version
3. **Android 11+**: Show explanatory dialog, redirect to Settings
4. **Legacy Versions**: Request runtime permissions directly
5. **User Response**: Handle grant/denial with appropriate UI feedback

## Integration with MainActivity

The MainActivity has been enhanced with:

### Permission Handling
```kotlin
// Initialize storage and permission managers
initializeManagers()

// Check and request permissions
checkAndRequestPermissions()
```

### Storage Initialization
```kotlin
private fun initializeStorage() {
    lifecycleScope.launch {
        when (val result = storageManager.initializeStorage()) {
            is StorageResult.Success -> {
                initializeGameLibrary()
            }
            // Handle other result types...
        }
    }
}
```

### User Experience
- **Permission Dialogs**: User-friendly explanations for permission requests
- **Status Updates**: Real-time feedback during initialization
- **Error Handling**: Graceful degradation when permissions are denied
- **Retry Mechanisms**: Allow users to re-attempt permission requests

## Success Criteria Achieved

✅ **App requests and obtains all necessary permissions on startup**
- Automatic permission detection and requests
- Cross-version compatibility (Android 10-14+)
- User-friendly permission explanations

✅ **Game library directory is accessible and writable**
- Proper storage path management
- Symlink creation for Steam directories
- Write verification and error handling

✅ **Storage paths work consistently across Android versions**
- Unified storage interface
- Version-specific permission handling
- Fallback mechanisms for different Android versions

✅ **Users can manage available storage for game installations**
- Real-time storage monitoring
- Automated cleanup utilities
- Storage recommendations and maintenance

## Usage Examples

### Checking Storage Status
```kotlin
lifecycleScope.launch {
    val storageInfo = storageManager.getStorageInfo()
    println("Available space: ${storageInfo.availableBytes} bytes")
    println("Usage: ${storageInfo.usagePercentage}%")
}
```

### Requesting Permissions
```kotlin
permissionManager.requestPermissions(activity) { result ->
    when (result) {
        PermissionRequestResult.AllGranted -> {
            initializeStorage()
        }
        // Handle other cases...
    }
}
```

### Performing Cleanup
```kotlin
lifecycleScope.launch {
    val cleanupManager = StorageCleanupManager(context, storageManager)
    val recommendations = cleanupManager.getCleanupRecommendations()
    
    // Show recommendations to user
    recommendations.forEach { recommendation ->
        println("Cleanup suggestion: ${recommendation.description}")
    }
}
```

## Testing and Validation

The system includes comprehensive error handling and validation:

- **Storage Verification**: Tests read/write operations before proceeding
- **Permission Validation**: Verifies all required permissions are granted
- **Fallback Mechanisms**: Provides alternative approaches when primary methods fail
- **User Feedback**: Clear error messages and retry options

## Future Enhancements

Potential improvements for future versions:

1. **WorkManager Integration**: Schedule automatic cleanup tasks
2. **Cloud Storage**: Integration with cloud storage services
3. **Game Streaming**: Support for remote game libraries
4. **Multiple Library Paths**: Support for additional game directories
5. **Storage Analytics**: Detailed storage usage analytics
6. **Game Metadata**: Enhanced game information extraction
7. **Backup/Restore**: Game library backup functionality