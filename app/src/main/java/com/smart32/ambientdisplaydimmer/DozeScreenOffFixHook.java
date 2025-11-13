package com.smart32.ambientdisplaydimmer;

import static com.smart32.ambientdisplaydimmer.AmbientDisplayOverride.TAG;
import static com.smart32.ambientdisplaydimmer.AmbientDisplayOverride.mScreenOffFixWakeLock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;


public class DozeScreenOffFixHook {

    public static void hook(final LoadPackageParam lpparam) {
        try {

            final Class<?> dozeScreenStateClass = XposedHelpers.findClass("com.android.systemui.doze.DozeScreenState", lpparam.classLoader);
            final Class<?> dozeStateEnum = XposedHelpers.findClass("com.android.systemui.doze.DozeMachine$State", lpparam.classLoader);
            final Class<?> dozeServiceHostClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.DozeServiceHost", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(dozeScreenStateClass, "transitionTo",
                    dozeStateEnum, dozeStateEnum, new XC_MethodHook() {

                        // --- Replace the buggy DOZE_PULSING with turning on the screen ---
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            // Enum<?> oldState = (Enum<?>) param.args[0];
                            Enum<?> newState = (Enum<?>) param.args[1];

                            if ("DOZE_REQUEST_PULSE".equals(newState.name())) {
                                // XposedBridge.log(TAG + "Intercepting transition " + oldState + " -> " + newState);

                                Object dozeScreenStateInstance = param.thisObject;
                                Object dozeHost = XposedHelpers.getObjectField(dozeScreenStateInstance, "mDozeHost");
                                if (dozeHost == null) {
                                    XposedBridge.log(TAG + "Error: DozeHost instance is null during pulse intercept.");
                                    return;
                                }
                                if (!dozeServiceHostClass.isInstance(dozeHost)) {
                                    XposedBridge.log(TAG + "Error: DozeHost is not the expected DozeServiceHost class during pulse intercept. Found: " + dozeHost.getClass().getName());
                                    return;
                                }
                                Object centralSurfaces = XposedHelpers.getObjectField(dozeHost, "mCentralSurfaces");
                                Context context = (Context) XposedHelpers.getObjectField(centralSurfaces, "mContext");

                                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                                long time = SystemClock.uptimeMillis() - 1L;
                                // Turn on the screen
                                XposedHelpers.callMethod(pm, "wakeUp", time, 2 /* WAKE_REASON_APPLICATION */, "ADDimmer:PulseOverride");

                                // Prevent the original transition to DOZE_REQUEST_PULSE
                                param.setResult(null);
                            }
                        }

                        // --- Ensure screen is off when in DOZE_AOD_PAUSED ---
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Enum<?> oldState = (Enum<?>) param.args[0];
                            Enum<?> newState = (Enum<?>) param.args[1];
                            Object dozeScreenStateInstance = param.thisObject;

                            if ("DOZE_AOD_PAUSING".equals(oldState.name()) && "DOZE_AOD_PAUSED".equals(newState.name())) {
                                // XposedBridge.log(TAG + "Intercepting transition: " + oldState + " -> " + newState);
                                Object dozeHost = XposedHelpers.getObjectField(dozeScreenStateInstance, "mDozeHost");
                                if (dozeHost == null) {
                                    XposedBridge.log(TAG + "Error: DozeHost instance is null. Aborting hook.");
                                    param.setResult(null);
                                    return;
                                }
                                if (!dozeServiceHostClass.isInstance(dozeHost)) {
                                    XposedBridge.log(TAG + "Error: DozeHost is not the expected DozeServiceHost class. Found: " + dozeHost.getClass().getName());
                                    param.setResult(null);
                                    return;
                                }
                                Object centralSurfaces = XposedHelpers.getObjectField(dozeHost, "mCentralSurfaces");
                                Context context = (Context) XposedHelpers.getObjectField(centralSurfaces, "mContext");

                                // Guarantee method execution by acquiring a wakelock
                                if (mScreenOffFixWakeLock == null) {
                                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                                    mScreenOffFixWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ADDimmer:ScreenOffFix");
                                    mScreenOffFixWakeLock.setReferenceCounted(false);
                                }
                                mScreenOffFixWakeLock.acquire(1000L);

                                // Send the task to the end of the queue
                                new Handler(Looper.getMainLooper()).post(() -> {
                                    try {
                                        // Turn off the screen
                                        XposedHelpers.callMethod(dozeScreenStateInstance, "applyScreenState", Display.STATE_OFF);
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG + "Error in posted screen off fix: " + t);
                                    } finally {
                                        // Release the wakelock
                                        if (mScreenOffFixWakeLock.isHeld()) mScreenOffFixWakeLock.release();
                                        // XposedBridge.log(TAG + "Screen off fix finished.");
                                    }
                                });
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "FATAL: Failed to initialize DozeScreenOffFixHook.");
            XposedBridge.log(t);
        }
    }
}
