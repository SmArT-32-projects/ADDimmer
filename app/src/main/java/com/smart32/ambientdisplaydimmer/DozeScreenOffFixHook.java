package com.smart32.ambientdisplaydimmer;

import static com.smart32.ambientdisplaydimmer.AmbientDisplayOverride.TAG;
import static com.smart32.ambientdisplaydimmer.AmbientDisplayOverride.mScreenOffFixWakeLock;

import android.content.Context;
import android.os.PowerManager;
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
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Enum<?> oldState = (Enum<?>) param.args[0];
                            Enum<?> newState = (Enum<?>) param.args[1];

                            // Check the transition scenario
                            if ("DOZE_AOD_PAUSING".equals(oldState.name()) && "DOZE_AOD_PAUSED".equals(newState.name())) {

                                // XposedBridge.log(TAG + "Intercepting transition: " + oldState + " -> " + newState);

                                Object dozeScreenStateInstance = param.thisObject;
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

                                // Keep the state consistent as much as possible,
                                // and guarantee method execution by acquiring a wakelock
                                if (mScreenOffFixWakeLock == null) {
                                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                                    mScreenOffFixWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ADDimmer:ScreenOffFix");
                                    mScreenOffFixWakeLock.setReferenceCounted(false);
                                }
                                mScreenOffFixWakeLock.acquire(1000L);

                                try {
                                    // Call the ScrimController method to update its state
                                    commandScrimControllerOff(context, dozeHost);

                                    // Turn off the screen
                                    XposedHelpers.callMethod(dozeScreenStateInstance, "applyScreenState", Display.STATE_OFF);

                                    // Cancel the original method
                                    param.setResult(null);

                                } finally {
                                    // Release the wakelock
                                    if (mScreenOffFixWakeLock.isHeld()) {
                                        mScreenOffFixWakeLock.release();
                                    }
                                    // XposedBridge.log(TAG + "Screen off fix finished.");
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "FATAL: Failed to initialize DozeScreenOffFixHook.");
            XposedBridge.log(t);
        }
    }

    // Call internalTransitionTo on the ScrimController
    private static void commandScrimControllerOff(Context context, Object dozeHost) {
        Object scrimController = XposedHelpers.getObjectField(dozeHost, "mScrimController");
        if (scrimController == null) return;

        try {
            Class<?> scrimStateClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.ScrimState", context.getClassLoader());
            Class<?> callbackClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.ScrimController$Callback", context.getClassLoader());

            // Get the OFF enum constant
            Object scrimStateOff = XposedHelpers.getStaticObjectField(scrimStateClass, "OFF");

            // Prepare arrays of types and values for an exact method call
            Class<?>[] parameterTypes = new Class<?>[] { callbackClass, scrimStateClass };
            Object[] argumentValues = new Object[] { null, scrimStateOff };

            XposedHelpers.callMethod(scrimController, "internalTransitionTo", parameterTypes, argumentValues);

            // XposedBridge.log(TAG + "Successfully commanded ScrimController to OFF state.");

        } catch (Throwable t) {
            XposedBridge.log(TAG + "ERROR: Failed to command ScrimController with new signature.");
            XposedBridge.log(t);
        }
    }
}
