# CAMCSCHAR

**Context-Aware Multimodal Content Selection (CAMCS)**

CAMCSCHAR is an Android application that delivers a **hands-free, location-aware cultural-heritage audio tour** and continuously adapts its narration — speech rate, inter-line pausing, information density, and key-fact repetition — to real-time estimates of the user's **cognitive load (CL)**.

By analyzing physiological data (heart rate), motor factors (gait variance), and behavioral metrics (voice activity), captured with smartphone sensors and a Bluetooth Low Energy (BLE) heart-rate monitor, the app adapts narration delivery to maximize information retention and minimize subjective cognitive overload.

## Key Features
*   **Cognitive-Load-Adaptive Audio:** Adjusts TTS speech rate (1.0×–0.75×), inter-line pauses, script density (detailed vs. simplified), and checkpoint repetition based on the estimated load level (Low, Medium, High, Overload).
*   **Audio-First by Design:** Eyes-free playback with media-notification controls; the phone stays in the pocket. A presentation-mode flag (`multimodal` / `audio_first` / `audio_only`) retains the legacy v1 visual UI (map, inline images, transcript) for backward comparison.
*   **Location-Based Delivery:** Integrates GPS geofencing to trigger specific Cultural Heritage content as users approach Points of Interest (POIs), with an arrival earcon before each POI.

---

## Prerequisites

Before building the project, ensure you have the following installed on your machine:
*   **Java Development Kit (JDK):** JDK 11 or 17 (recommended for modern Android projects).
*   **Android SDK:** Ensure the SDK paths are properly configured on your system (usually handled by setting the `ANDROID_HOME` environment variable or using a `local.properties` file in the root of the project with `sdk.dir=/path/to/sdk`).
*   **ADB (Android Debug Bridge):** Required to install the compiled application onto your phone.

## Build Instructions (Command Line)

This project uses Gradle for building. You can compile it directly from your terminal:

1.  **Clone / Navigate to the repository directory:**
    ```bash
    cd CAMCSCHAR-demo
    ```

2.  **Make the Gradle wrapper executable (macOS/Linux only):**
    ```bash
    chmod +x gradlew
    ```

3.  **Build the application:**
    Run the following command to compile the app and create an installable debugging APK:
    ```bash
    ./gradlew assembleDebug
    ```
    Once the build completes successfully, the generated `.apk` file (`app-debug.apk`) will be available in `app/build/outputs/apk/debug/`.

## Installation (ADB)

To install the built APK onto your Android device using the command line:

1.  **Enable USB Debugging** on your Android device (found in Settings > Developer Options).
2.  Connect your device to your computer via USB.
3.  **Verify your device is connected:**
    ```bash
    adb devices
    ```
    *You should see your device's serial number listed.*
4.  **Install the APK:**
    ```bash
    adb install app/build/outputs/apk/debug/app-debug.apk
    ```
    *If you need to overwrite an existing installation, use the `-r` flag: `adb install -r app/build/outputs/apk/debug/app-debug.apk`*

Now you can open the **CAMCSCHAR** app from your phone's app launcher!
