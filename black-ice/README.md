# Black Ice Emulator 1.0.4 — full integration

Status: Black Ice Home and the native ARMSX2 core are integrated into one Android application. CI performs the structural/UI tests, native universal build, APK signature verification and 16K zip-alignment verification. Real-device gameplay testing is still required before a production release.

Base: https://github.com/ARMSX2/ARMSX2 at bfae0f5a1255e08fa655b667b826cfd41da2e4db.

Black Ice Home is now the sole launcher entry. Play passes the selected Android document URI to com.armsx2.Main inside the same APK. The native ARMSX2 core remains built by its upstream CMake/Gradle project. No separately installed AetherSX2/ARMSX2 app is used.

The existing Black Ice home design, logo animation, playlists, cover downloads, metadata summaries, music and SoundCN effects are preserved. Native BIOS setup, emulation, controls, graphics and save states use the upstream ARMSX2 screens. Those screens have not yet been comprehensively restyled to match Home.

The native activity runs in the app's :emulation process because upstream intentionally kills its process after native shutdown. Its exit now finishes only the core activity, retaining the Black Ice task beneath it. This process split is untested on a device and must be checked, including upstream auxiliary activities and services.

A private same-UID ContentProvider records RUNNING-state intervals directly, pauses on background transitions and checkpoints every 15 seconds. It retains earlier per-file totals and removes the Usage Access requirement. Abrupt process death can lose up to the uncheckpointed interval. Native state/IPC integration requires device testing. Imported or directly booted games outside Black Ice Home are not attributed to a Black Ice file ID.

Application ID stays `dev.aether.preview` for launcher data compatibility. The human-facing version is 1.0.4 and Android versionCode is 10004, so it upgrades the previous Black Ice 1.0.3 test build. Do not uninstall the existing launcher when testing migration.

## Build requirements and recipe

Use JDK 17, Android SDK platform 37, NDK 28.2.13676358 and CMake 3.31.6. Gradle 9.4.1, AGP 9.2.1 and Kotlin 2.4.0 are pinned by the upstream source. A working network connection to Gradle/Maven/Android dependency repositories is required. Configure ANDROID_HOME or platforms/android/local.properties normally.

From the reconstructed repository:

    cd platforms/android
    ./gradlew :app:assemblePlayRelease --max-workers=2

The play variant is selected to omit upstream's self-updater, broad-storage request and optional LSFG integration. This is still a sideloadable APK. The native core must compile; do not disable externalNativeBuild or substitute another app's proprietary binaries.

Expected output after a successful build: platforms/android/app/build/outputs/apk/play/release/app-play-release.apk. Signing credentials are intentionally excluded from this public source repository. Copy `platforms/android/armsx2_keystore.properties.example` to `armsx2_keystore.properties`, configure your own private key, and never commit either file.

## Verification completed

- UI bridge regression checks passed: actual-file library, covers, settings, playlists, escaping, persistence, startup skip, motion preference and volume dispatch.
- Structural integration checks passed: sole Black Ice launcher entry, same-app core intent, isolated emulation process, private tracking IPC, no Usage Access and version identity.
- git diff --check passed.
- Full Gradle build was attempted but failed before configuration: downloading gradle-9.4.1-bin.zip raised java.net.SocketException: Network is unreachable. Proxy and IPv4 configuration did not resolve it. No native or Kotlin build success is claimed.
- A Java-only check was also unavailable because the prior temporary Android compiler/toolchain was no longer present. No Android device or emulator runtime test was performed.

Run the available source checks from the reconstructed repository:

    node black-ice/tests/library-ui.cjs
    python3 black-ice/tests/integration.py

Before calling 1.0.4 production-ready, test BIOS setup, real ISO/CHD boot, graphics/audio, controller/touch input, pause/resume, save states, memory cards, app backgrounding, close-game return to Home, process recovery, tracking, and upgrade data retention on ARM64 Android hardware.

## Source and notices

ARMSX2 / PCSX2 source retains COPYING.GPLv3 and its original notices. Black Ice source changes are provided under GPL-3.0-or-later. Preserve third-party notices and provide corresponding source with any distributed combined binaries. Black Ice branding/theme are user-supplied assets; this statement does not independently grant third-party rights to those assets. SoundCN/Kenney CC0 effects and notices are included. BIOS and game images are not supplied.
