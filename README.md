# CAMCSCHAR

**Context-Aware Multimodal Content Selection (CAMCS)**

CAMCSCHAR is an Android application designed to dynamically manage auditory and visual Augmented Reality (AR) content delivery based on real-time estimations of a user's **cognitive load (CL)**. 

By analyzing inputs such as physiological data (heart rate, heart rate variability), visual factors (gait variance), and behavioral metrics (voice activity) using smartphone sensors and Bluetooth Low Energy (BLE) monitors, the app adjusts content delivery to maximize information retention and minimize subjective cognitive overload.

## Key Features
*   **Dynamic Content Adaptation:** Adjusts Text-to-Speech (TTS) parameters like speed, information density, pauses, and audio ducking based on real-time stress/load factors.
*   **Visual Interface Adjustments:** Modifies visual clutter (e.g., swapping inline images for buttons or simplifying text) depending on the calculated cognitive load level (Low, Medium, High, Overload).
*   **Location-Based Delivery:** Integrates GPS geofencing to trigger specific Cultural Heritage content as users approach Points of Interest (POIs).

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
