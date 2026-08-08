package com.smart32.ambientdisplaydimmer;

import static com.smart32.ambientdisplaydimmer.AmbientDisplayOverride.mScreenOffFixWakeLock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class DozeScreenOffFixHook {

    private static volatile boolean sUseDirectContextFallback = false;

    // --- Helper method to resolve context ---
    private static Context resolveContext(Object dozeHost) {
        Context context = null;

        if (!sUseDirectContextFallback) {
            // Try via mCentralSurfaces
            try {
                Object centralSurfaces = XposedHelpers.getObjectField(dozeHost, "mCentralSurfaces");
                if (centralSurfaces != null) {
                    context = (Context) XposedHelpers.getObjectField(centralSurfaces, "mContext");
                }
                if (context != null) {
                    return context;
                }
            } catch (Throwable ignored) {
                AmbientDisplayOverride.logInfo("mCentralSurfaces approach failed, switching to direct mContext fallback...");
            }
            // Remember the fallback path after the first failed attempt
            sUseDirectContextFallback = true;
        }

        // Fallback to direct mContext on DozeHost
        try {
            context = (Context) XposedHelpers.getObjectField(dozeHost, "mContext");
        } catch (Throwable t) {
            CrashAnalyzer.analyzeAndLog(t, dozeHost.getClass(), "Resolve Context via direct mContext");
        }

        return context;
    }

    public static void hook(final LoadPackageParam lpparam) {
        final Class<?> dozeScreenStateClass;
        final Class<?> dozeStateEnum;
        final Class<?> dozeServiceHostClass;

        try {
            dozeScreenStateClass = XposedHelpers.findClass("com.android.systemui.doze.DozeScreenState", lpparam.classLoader);
            dozeStateEnum = XposedHelpers.findClass("com.android.systemui.doze.DozeMachine$State", lpparam.classLoader);
            dozeServiceHostClass = XposedHelpers.findClass("com.android.systemui.statusbar.phone.DozeServiceHost", lpparam.classLoader);
        } catch (Throwable t) {
            CrashAnalyzer.analyzeClassNotFound(t, lpparam.classLoader, "com.android.systemui.doze.DozeScreenState", "Find DozeScreenOffFix classes");
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(dozeScreenStateClass, "transitionTo",
                    dozeStateEnum, dozeStateEnum, new XC_MethodHook() {

                        // --- Replace the buggy DOZE_PULSING with turning on the screen ---
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Enum<?> newState = (Enum<?>) param.args[1];

                                if ("DOZE_REQUEST_PULSE".equals(newState.name())) {
                                    if (!AmbientDisplayOverride.sHookPulseOverride) return;

                                    Object dozeScreenStateInstance = param.thisObject;
                                    Object dozeHost = null;

                                    try {
                                        dozeHost = XposedHelpers.getObjectField(dozeScreenStateInstance, "mDozeHost");
                                    } catch (Throwable t) {
                                        CrashAnalyzer.analyzeAndLog(t, dozeScreenStateInstance.getClass(), "Find mDozeHost in DozeScreenState");
                                        return;
                                    }

                                    if (dozeHost == null) {
                                        AmbientDisplayOverride.logError("DozeHost instance is null during pulse intercept.");
                                        return;
                                    }
                                    if (!dozeServiceHostClass.isInstance(dozeHost)) {
                                        AmbientDisplayOverride.logError("DozeHost is not the expected DozeServiceHost class during pulse intercept. Found: " + dozeHost.getClass().getName());
                                        return;
                                    }

                                    Context context = resolveContext(dozeHost);
                                    if (context == null) {
                                        AmbientDisplayOverride.logError("Context is null during pulse intercept.");
                                        return;
                                    }

                                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                                    if (pm == null) {
                                        AmbientDisplayOverride.logError("PowerManager is null during pulse intercept.");
                                        return;
                                    }

                                    try {
                                        long time = SystemClock.uptimeMillis() - 1L;
                                        // Turn on the screen
                                        XposedHelpers.callMethod(pm, "wakeUp", time, 2 /* WAKE_REASON_APPLICATION */, "ADDimmer:PulseOverride");
                                    } catch (Throwable t) {
                                        CrashAnalyzer.analyzeAndLog(t, pm.getClass(), "PowerManager wakeUp");
                                    }

                                    // Prevent the original transition to DOZE_REQUEST_PULSE
                                    param.setResult(null);
                                    AmbientDisplayOverride.logInfo("DOZE_PULSING fix finished.");
                                }
                            } catch (Throwable t) {
                                CrashAnalyzer.analyzeAndLog(t, param.thisObject.getClass(), "DozeScreenState transitionTo (before)");
                            }
                        }

                        // --- Ensure screen is off when in DOZE_AOD_PAUSED ---
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Enum<?> oldState = (Enum<?>) param.args[0];
                                Enum<?> newState = (Enum<?>) param.args[1];
                                Object dozeScreenStateInstance = param.thisObject;

                                if ("DOZE_AOD_PAUSING".equals(oldState.name()) && "DOZE_AOD_PAUSED".equals(newState.name())) {
                                    if (!AmbientDisplayOverride.sHookAodPausedScreenOff) return;

                                    Object dozeHost = null;
                                    try {
                                        dozeHost = XposedHelpers.getObjectField(dozeScreenStateInstance, "mDozeHost");
                                    } catch (Throwable t) {
                                        CrashAnalyzer.analyzeAndLog(t, dozeScreenStateInstance.getClass(), "Find mDozeHost in DozeScreenState");
                                        param.setResult(null);
                                        return;
                                    }

                                    if (dozeHost == null) {
                                        AmbientDisplayOverride.logError("DozeHost instance is null. Aborting hook.");
                                        param.setResult(null);
                                        return;
                                    }
                                    if (!dozeServiceHostClass.isInstance(dozeHost)) {
                                        AmbientDisplayOverride.logError("DozeHost is not the expected DozeServiceHost class. Found: " + dozeHost.getClass().getName());
                                        param.setResult(null);
                                        return;
                                    }

                                    Context context = resolveContext(dozeHost);
                                    if (context == null) {
                                        AmbientDisplayOverride.logError("Context is null during screen off fix.");
                                        param.setResult(null);
                                        return;
                                    }

                                    // Guarantee method execution by acquiring a wakelock
                                    if (mScreenOffFixWakeLock == null) {
                                        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                                        if (pm != null) {
                                            mScreenOffFixWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ADDimmer:ScreenOffFix");
                                            mScreenOffFixWakeLock.setReferenceCounted(false);
                                        } else {
                                            AmbientDisplayOverride.logError("PowerManager is null, cannot acquire WakeLock.");
                                            param.setResult(null);
                                            return;
                                        }
                                    }

                                    if (mScreenOffFixWakeLock != null) {
                                        mScreenOffFixWakeLock.acquire(1000L);
                                    }

                                    // Send the task to the end of the queue
                                    new Handler(Looper.getMainLooper()).post(() -> {
                                        try {
                                            XposedHelpers.callMethod(dozeScreenStateInstance, "applyScreenState", Display.STATE_OFF);
                                        } catch (Throwable t) {
                                            CrashAnalyzer.analyzeAndLog(t, dozeScreenStateInstance.getClass(), "Call applyScreenState");
                                        } finally {
                                            if (mScreenOffFixWakeLock != null && mScreenOffFixWakeLock.isHeld()) {
                                                try {
                                                    mScreenOffFixWakeLock.release();
                                                } catch (Throwable ignored) { }
                                            }
                                            AmbientDisplayOverride.logInfo("Screen off fix finished.");
                                        }
                                    });
                                }
                            } catch (Throwable t) {
                                CrashAnalyzer.analyzeAndLog(t, param.thisObject.getClass(), "DozeScreenState transitionTo (after)");
                            }
                        }
                    });
        } catch (Throwable t) {
            CrashAnalyzer.analyzeAndLog(t, dozeScreenStateClass, "Hook DozeScreenState transitionTo");
        }

        // --- Experimental: Force STATE_ON during Doze ---
        try {
            XposedHelpers.findAndHookMethod(dozeScreenStateClass, "applyScreenState", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (AmbientDisplayOverride.sExperimentalForceStateOn) {
                            int requestedState = (int) param.args[0];

                            // Display.STATE_DOZE (3) or Display.STATE_DOZE_SUSPEND (4)
                            if (requestedState == Display.STATE_DOZE || requestedState == Display.STATE_DOZE_SUSPEND) {
                                param.args[0] = Display.STATE_ON; // 2
                                AmbientDisplayOverride.logInfo("Forced Display.STATE_ON instead of " + (requestedState == Display.STATE_DOZE ? "STATE_DOZE" : "STATE_DOZE_SUSPEND"));
                            }
                        }
                    } catch (Throwable t) {
                        CrashAnalyzer.analyzeAndLog(t, param.thisObject.getClass(), "Experimental force STATE_ON hook");
                    }
                }
            });
        } catch (Throwable t) {
            CrashAnalyzer.analyzeAndLog(t, dozeScreenStateClass, "Hook applyScreenState for force STATE_ON");
        }
    }
}
