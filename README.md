# Ambient Display Dimmer for Xperia 1 V

An Xposed module that provides adaptive brightness for the Ambient Display (AOD) and improves the reliability of in-pocket detection.

> **⚠️ Looking for the Configuration File or EvolutionX fixes?**
> Check out the [Releases page](https://github.com/SmArT-32-projects/ADDimmer/releases) and look for version marked as **Pre-release** (v1.3) to get the latest experimental features.

## Main features

* **Adaptive Brightness:**
    Adjusts the AOD brightness between two levels (dim/bright) based on the ambient light sensor. To preserve battery life and avoid breaking `systemui` deep sleep, brightness updates on battery are synchronized with system maintenance windows (typically every 1-2 minutes).
    * When the device is charging, the update interval is 5 seconds.
    * Briefly covering the proximity sensor will trigger an immediate brightness update.

* **Improved In-Pocket Detection:**
    Increases the reliability of the native mechanism for turning off the screen. This ensures the AOD turns off consistently when the proximity sensor is covered (e.g., in a pocket or when the phone is placed face down).

* **Fix for Black Screen Battery Drain:**
    Prevents battery drain that occurs when the screen is black but the display panel remains active when the device is in a pocket.

* **Fix for Black Screen bug upon receiving notifications:**
    Notifications no longer cause the screen to stay black upon receiving notifications after exiting the Pocket Mode.

## 🚀 Experimental features (v1.3)

* **Configuration File:** A hidden `.ADDimmer_config.txt` file in your internal storage allows you to customize brightness thresholds, adjust sensor polling intervals, and toggle specific helper hooks.
* **Experimental hooks:** Experimental low brightness wake fix for EvolutionX and optional forcing display `STATE_ON` during AOD.

## Compatibility and Risks

This module was originally developed for the **Sony Xperia 1 V** and **crDroid 11.6**, but it may work on other devices with AOSP-based ROMs if the ROM developer has not significantly modified the `com.android.systemui.doze` component.


### Device & ROM Compatibility

| Device | ROM / OS Version | Status | Notes |
| :--- | :--- | :--- | :--- |
| **Sony Xperia 1 V** *(pdx234)* | crDroid 11.6 | ✅ Fully Compatible | Confirmed working |
| | LineageOS 22.2, 23.2 | ✅ Fully Compatible | Confirmed working |
| | LineageOS / crDroid (Other versions) | 🟡 Presumed Compatible | Needs further testing |
| | Evolution X (Android 15 & 16) | ⚠️ Minor Bug | Screen stays dark when turned on in manual brightness mode (testing needed) |
| **Poco F6 Pro/Redmi K70** *(vermeer)* | Lunaris AOSP 3.12 (Android 16) | ✅ Compatible | Requires manual brightness configuration |
| **Redmi Note 5** *(whyred)* | LineageOS 22.2 | ❓ Presumed Incompatible | Insufficient data, needs further testing |

> **Troubleshooting Note:** If you encounter bugs on the stable release, please check if they persist on the latest **experimental pre-release**. If the bug is already fixed in the experimental build, letting me know in a report or feedback is still greatly appreciated!

This module is intended to solve the following common issues:
1.  AOD brightness gets stuck at the level it was when the screen was turned off.
2.  AOD fails to turn off if the phone is pocketed too quickly, or if the screen is accidentally activated while in a pocket (e.g., by a full-screen notification).
3.  Excessive battery drain that can occur when the phone is in a pocket, caused by the screen failing to completely power down.
4.  Occasional black screen when you take your phone out of your pocket after receiving a notification.

**WARNING:** This module hooks into a core system component (System UI). On incompatible devices, it could theoretically cause instability, such as a System UI crash loop. As a precaution, please have a recovery method available (e.g., the ability to boot into Safe Mode for Magisk/KernelSU to disable the module).

## Feedback and Contributions

If you can confirm that this module works on another device or ROM, or if you encounter a bug, please open an issue on GitHub! Your feedback is highly appreciated.
* **[Report a Bug](https://github.com/SmArT-32-projects/ADDimmer/issues/new?template=bug_report.md)**
* **[Report Device Compatibility](https://github.com/SmArT-32-projects/ADDimmer/issues/new?template=compatibility_report.md)**

> **Important:** When submitting a bug report, it is highly recommended to reproduce the issue on the latest **experimental pre-release** build with the `LOG_INFO` option enabled in the config.

## Installation
1.  Ensure you have Magisk (or KernelSU) and LSPosed Framework installed.
2.  Install the module's APK.
3.  Activate the module in the LSPosed Manager app and select **only** `com.android.systemui` (System UI) as the scope.
4.  Reboot your device.
                         
