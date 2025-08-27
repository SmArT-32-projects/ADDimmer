# Ambient Display Dimmer for Xperia 1 V

An Xposed module that provides adaptive brightness for the Ambient Display (AOD) and improves the reliability of in-pocket detection.

## Features

* **Adaptive Brightness:**
    Adjusts the AOD brightness between two levels (dim/bright) based on the ambient light sensor. To preserve battery life and avoid breaking `systemui` deep sleep, brightness updates on battery are synchronized with system maintenance windows (typically every 1-2 minutes).
    * When the device is charging, the update interval is 10 seconds.
    * Briefly covering the proximity sensor will trigger an immediate brightness update.

* **Improved In-Pocket Detection:**
    Increases the reliability of the native mechanism for turning off the screen. This ensures the AOD turns off consistently when the proximity sensor is covered (e.g., in a pocket or when the phone is placed face down).

## Compatibility
This module was developed and tested specifically for the following configuration:
* **Device:** Sony Xperia 1 V
* **ROM:** crDroid 11.6 (Android 15)

It may or may not work on other devices or ROMs.

## Installation
1.  Ensure you have Magisk (or KernelSU) and LSPosed Framework installed.
2.  Install the module's APK.
3.  Activate the module in the LSPosed Manager app and select **only** `com.android.systemui` (System UI) as the scope.
4.  Reboot your device.
