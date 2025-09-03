# Ambient Display Dimmer for Xperia 1 V

An Xposed module that provides adaptive brightness for the Ambient Display (AOD) and improves the reliability of in-pocket detection.

## Features

* **Adaptive Brightness:**
    Adjusts the AOD brightness between two levels (dim/bright) based on the ambient light sensor. To preserve battery life and avoid breaking `systemui` deep sleep, brightness updates on battery are synchronized with system maintenance windows (typically every 1-2 minutes).
    * When the device is charging, the update interval is 10 seconds.
    * Briefly covering the proximity sensor will trigger an immediate brightness update.

* **Improved In-Pocket Detection:**
    Increases the reliability of the native mechanism for turning off the screen. This ensures the AOD turns off consistently when the proximity sensor is covered (e.g., in a pocket or when the phone is placed face down).

* **Fix for Black Screen Battery Drain:**
    Prevents battery drain that occurs when the screen is black but the display panel remains active when the device is in a pocket.

## Compatibility and Risks

This module was developed and tested specifically for the following configuration:
* **Device:** Sony Xperia 1 V
* **ROM:** crDroid 11.6 (Android 15)

Functionality on other devices and ROMs is not guaranteed. However, it may theoretically work on other AOSP-based ROMs (Android 15) if the ROM developer has not significantly modified the `com.android.systemui.doze` component implementation.

This module is intended to solve the following common issues:
1.  AOD brightness gets stuck at the level it was when the screen was turned off.
2.  AOD fails to turn off if the phone is pocketed too quickly, or if the screen is accidentally activated while in a pocket (e.g., by a full-screen notification).
3.  Excessive battery drain that can occur when the phone is in a pocket, caused by the screen failing to completely power down.

**WARNING:** This module hooks into a core system component (System UI). On incompatible devices, it could theoretically cause instability, such as a System UI crash loop. As a precaution, please have a recovery method available (e.g., the ability to boot into Safe Mode for Magisk/KernelSU to disable the module).

## Feedback and Contributions

If you can confirm that this module works on another device or ROM, or if you encounter a bug, please open an issue on GitHub! Your feedback is highly appreciated.
* **[Report a Bug](https://github.com/SmArT-32-projects/ADDimmer/issues/new?template=bug_report.md)**
* **[Report Device Compatibility](https://github.com/SmArT-32-projects/ADDimmer/issues/new?template=compatibility_report.md)**

## Installation
1.  Ensure you have Magisk (or KernelSU) and LSPosed Framework installed.
2.  Install the module's APK.
3.  Activate the module in the LSPosed Manager app and select **only** `com.android.systemui` (System UI) as the scope.
4.  Reboot your device.
                         
