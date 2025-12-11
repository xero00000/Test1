# FEXDroid - FEX-Emu x86_64 Emulator for Android

This is an updated Android application for FEXDroid, bringing FEX-Emu x86_64 emulation capabilities to Android devices.

## Overview

FEXDroid is an Android port of FEX-Emu, allowing ARM64 Android devices to emulate and run x86_64 binaries and applications.

## Requirements

- Android API Level 24 or higher (Android 7.0+)
- ARM64 device or emulator
- At least 2GB of free storage for rootfs
- Android SDK/NDK 26.1

## Building

### Prerequisites

1. Android SDK with API level 34 (or adjust `compileSdk` in `build.gradle.kts`)
2. Android NDK version 26.1
3. Gradle 8.4 or compatible

### Build Steps

```bash
# Clone the repository
git clone <repo-url>
cd FEXDroid

# Build the debug APK
./gradlew assembleDebug

# Build the release APK
./gradlew assembleRelease

# Install on connected device/emulator
./gradlew installDebug
```

## Project Structure

```
FEXDroid/
├── app/                              # Main Android app module
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/                # Kotlin/Java source
│   │   │   ├── res/                 # Android resources
│   │   │   └── AndroidManifest.xml
│   │   └── test/                    # Unit tests
│   └── build.gradle.kts             # App-level build config
├── FEXDroid/                        # Original FEXDroid distribution
├── build.gradle.kts                 # Top-level build config
├── settings.gradle.kts              # Gradle settings
├── gradle.properties                # Gradle properties
└── gradlew                          # Gradle wrapper script
```

## Gradle Configuration

The project uses modern Android Gradle plugin v8.2.0 with:

- Kotlin v1.9.21
- Kotlin DSL for build scripts
- AndroidX libraries
- Target SDK 34 with minimum SDK 24
- NDK v26.1

## AndroidX & Modern Dependencies

All dependencies use current stable versions:
- androidx.core:core-ktx:1.12.0
- androidx.appcompat:appcompat:1.6.1
- com.google.android.material:material:1.11.0
- androidx.constraintlayout:constraintlayout:2.1.4

## Features

- Modern Material Design UI
- View Binding support
- Kotlin coroutines for async operations
- Full AndroidX support
- Proper lifecycle management
- Steam Big Picture auto-launcher with controller awareness

## Steam Big Picture Auto-launch

The Android client now boots directly into Steam Big Picture mode as soon as the app starts:

1. Bundled FEXDroid scripts, Vulkan shims, and the qemu-x86_64 binary are copied from the APK assets to app storage on first launch.
2. A generated `steam-big-picture.sh` script injects the right `-tenfoot`, `-bigpicture`, and controller flags before delegating to the Steam runtime.
3. The launcher restores any saved credentials or Guard tokens, enabling persistent sessions across app launches.
4. Real-time logs and controller status are surfaced in the UI so users can confirm input readiness and diagnose launch issues.

## Testing

Run tests with:

```bash
./gradlew test
./gradlew connectedAndroidTest
```

## Troubleshooting

### Build Issues

If you encounter build issues:

1. Clear build cache: `./gradlew clean`
2. Update Gradle: `./gradlew wrapper --gradle-version 8.4`
3. Sync project: `./gradlew sync`

### Common Deprecation Warnings

All deprecated APIs have been updated to use current Android APIs:
- Using `WindowInsetsCompat` instead of deprecated inset methods
- Using `ActivityResultContracts` for permission/activity results
- Using AndroidX lifecycle components

## Development

### Code Style

- Kotlin with idiomatic patterns
- Follow Android Architecture Components guidelines
- Use AndroidX libraries exclusively

### Adding Features

1. Create feature branches from `main`
2. Implement features following MVVM pattern
3. Add unit tests for business logic
4. Ensure no Gradle warnings on build

## License

This project builds upon FEXDroid by GameXtra4u and FEX-Emu emulator.

## Contributing

Contributions are welcome! Please ensure:
- Code compiles without warnings
- Tests pass
- AndroidX and modern API usage
- Proper Kotlin/Java style

## Resources

- [FEX-Emu Documentation](https://fex-emu.com/)
- [Android Documentation](https://developer.android.com/)
- [Kotlin Documentation](https://kotlinlang.org/)
- [AndroidX Release Notes](https://developer.android.com/jetpack/androidx/releases)
