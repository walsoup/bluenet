# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview
BlueNet is an Android Bluetooth proxy/VPN application that enables network tethering and tunneling over Bluetooth L2CAP and RFCOMM. The app has a Host mode (acting as a proxy server) and a Client mode (using Android's `VpnService` to capture and route traffic over Bluetooth).

## High-Level Architecture
- **`com.bluenet.bluetooth`**: Handles low-level Bluetooth socket connections. Uses L2CAP Connection-Oriented Channels (CoC) with a fallback to RFCOMM High-Speed if L2CAP is unavailable.
- **`com.bluenet.multiplexer`**: A custom stream multiplexer protocol (`StreamMultiplexer`, `Frame`, `FrameType`) that allows concurrent network streams over a single Bluetooth socket.
- **`com.bluenet.host`**: Contains `HostService` and `HostProxyManager`. Implements the server-side proxy that receives multiplexed packets and forwards them to the real network.
- **`com.bluenet.client`**: Contains `BlueNetVpnService` and `TunPacketRouter`. Sets up a virtual network interface (TUN) to intercept device traffic and route it through the multiplexer to the Host.
- **`MainActivity.kt`**: The single activity managing the UI, mode switching (Host vs. Client), and requesting Bluetooth and VPN permissions.

## Build and Development Commands
*Note: If the `gradlew` script is missing from the root directory, you may need to use a system-installed `gradle` or generate the wrapper using `gradle wrapper`.*

- **Build APK:** `./gradlew assembleDebug`
- **Run Linting:** `./gradlew lint`
- **Run Unit Tests:** `./gradlew testDebugUnitTest`
- **Run a specific test:** `./gradlew testDebugUnitTest --tests "com.bluenet.ExampleTest"`
- **Clean Project:** `./gradlew clean`

## Code Conventions and Notes
- Written in Kotlin, using `build.gradle.kts` for Gradle configuration.
- The project targets Android API 34 and has a minimum SDK of 29 (Android 10 required for L2CAP CoC APIs).
- Ensure required Bluetooth permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`) are handled properly for API 31+ (Android 12+).
- The Client mode depends heavily on `VpnService`, which requires user consent via standard Android VPN permission dialogues.