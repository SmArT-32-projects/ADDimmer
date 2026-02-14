package com.smart32.ambientdisplaydimmer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class AmbientDisplayOverride implements IXposedHookLoadPackage {

    static final String TAG = "[AD Dimmer] ";
    private static final String TARGET_PACKAGE = "com.android.systemui";

    private Handler mHandler;
    private Runnable mBrightnessRunnable;
    private volatile boolean isAodActive = false;
    private WakeLock mWakeLock;
    static WakeLock mScreenOffFixWakeLock;
    private WakeLock mProximityCheckWakeLock;
    private Runnable mDelayedProximityCheckRunnable;


    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) return;

        // Start the persistent proximity monitor
        boolean hookedSystemUIApplication = false;
        try {
            final Class<?> systemUIApplicationImplClass = XposedHelpers.findClass(
                    "com.android.systemui.application.impl.SystemUIApplicationImpl",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(systemUIApplicationImplClass, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context context = (Context) param.thisObject;
                    PersistentProximityMonitor.init(context);
                }
            });
            hookedSystemUIApplication = true;
        } catch (Throwable ignored) { }

        if (!hookedSystemUIApplication) {
            try {
                final Class<?> systemUIApplicationClass = XposedHelpers.findClass(
                        "com.android.systemui.SystemUIApplication",
                        lpparam.classLoader);
                XposedHelpers.findAndHookMethod(systemUIApplicationClass, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Context context = (Context) param.thisObject;
                        PersistentProximityMonitor.init(context);
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + "Failed to hook SystemUIApplication.onCreate to initialize monitor: " + t);
            }
        }

        final Class<?> dozeTriggersClass;
        final Class<?> dozeStateEnum;
        final Class<?> dozeServiceClass;
        final Class<?> dozeScreenBrightnessClass;

        try {
            dozeTriggersClass = XposedHelpers.findClass("com.android.systemui.doze.DozeTriggers", lpparam.classLoader);
            dozeStateEnum = XposedHelpers.findClass("com.android.systemui.doze.DozeMachine$State", lpparam.classLoader);
            dozeServiceClass = XposedHelpers.findClass("com.android.systemui.doze.DozeService", lpparam.classLoader);
            dozeScreenBrightnessClass = XposedHelpers.findClass("com.android.systemui.doze.DozeScreenBrightness", lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to find core Doze classes: " + t);
            return;
        }

        // --- Disable native AOD brightness control ---
        try {
            XposedHelpers.findAndHookMethod(dozeScreenBrightnessClass, "setLightSensorEnabled",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[0] = false;
                        }
                    });
            XposedHelpers.findAndHookMethod(dozeScreenBrightnessClass, "updateBrightnessAndReady", boolean.class, XC_MethodReplacement.DO_NOTHING);
            XposedHelpers.findAndHookMethod(dozeScreenBrightnessClass, "onSensorChanged", android.hardware.SensorEvent.class, XC_MethodReplacement.DO_NOTHING);
            // XposedBridge.log(TAG + "Native AOD brightness control disabled.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to disable native AOD brightness: " + t);
        }

        // --- Lifecycle management (start/stop) ---
        XposedHelpers.findAndHookMethod(dozeTriggersClass, "transitionTo", dozeStateEnum, dozeStateEnum, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Enum<?> oldState = (Enum<?>) param.args[0];
                Enum<?> newState = (Enum<?>) param.args[1];

                if (newState.name().equals("DOZE_AOD")) {
                    if (!isAodActive) {
                        isAodActive = true;
                        // XposedBridge.log(TAG + "AOD active. Starting checks.");

                        Object dozeTriggersInstance = param.thisObject;
                        if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());

                        mBrightnessRunnable = new BrightnessRunnable(dozeTriggersInstance);
                        mHandler.removeCallbacksAndMessages(null);

                        // Handle the transition to DOZE_AOD based on the previous state
                        if (oldState.name().equals("DOZE_AOD_PAUSED")) {
                            // XposedBridge.log(TAG + "AOD resumed from PAUSED. Delaying first check by 2s");
                            acquireTempWakeLock((Context) XposedHelpers.getObjectField(dozeTriggersInstance, "mContext"), 2400L);
                            mHandler.postDelayed(mBrightnessRunnable, 2000); // Phone is being taken out of a pocket, ensure the service stays awake during this time
                        } else if (oldState.name().equals("DOZE_AOD_PAUSING")) {
                            // XposedBridge.log(TAG + "AOD resumed from PAUSING.");
                            mHandler.postDelayed(mBrightnessRunnable, 100); // A brief trigger of the proximity sensor
                        } else {
                            mHandler.post(mBrightnessRunnable); // All other cases
                            if (oldState.name().equals("INITIALIZED")) {
                                startDelayedProximityCheck(param.thisObject); // Screen turned off by the power button or timeout
                            }
                        }
                    }
                } else {
                    if (isAodActive) {
                        isAodActive = false;
                        // XposedBridge.log(TAG + "AOD inactive. Stopping checks.");
                        stopAodListeners();
                    }
                }
            }
        });

        // --- Ensure stop on service destruction ---
        XposedHelpers.findAndHookMethod(dozeServiceClass, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (isAodActive) {
                    isAodActive = false;
                    stopAodListeners();
                }
            }
        });

        // --- Ensure screen is off when in DOZE_AOD_PAUSED ---
        DozeScreenOffFixHook.hook(lpparam);
    }

    private void acquireTempWakeLock(Context context, long timeout) {
        try {
            if (mWakeLock == null) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ADDimmer:TempResumeWakeLock");
                mWakeLock.setReferenceCounted(false);
            }
            if (!mWakeLock.isHeld()) {
                mWakeLock.acquire(timeout);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to acquire temp WakeLock: " + t);
        }
    }

    private void stopAodListeners() {
        if (mHandler != null) {
            if (mBrightnessRunnable != null) {
                try {
                    ((BrightnessRunnable) mBrightnessRunnable).stop();
                } catch (Throwable ignored) {}
                mHandler.removeCallbacks(mBrightnessRunnable);
            }
        }
        // Perform cleanup
        stopProximityCheck();
    }

    // --- Force transition to DOZE_AOD_PAUSING if the phone is "in pocket" ---
    private void startDelayedProximityCheck(final Object dozeTriggersInstance) {
        try {
            final Context context = (Context) XposedHelpers.getObjectField(dozeTriggersInstance, "mContext");
            if (context == null) return;

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (mProximityCheckWakeLock == null) {
                mProximityCheckWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ADDimmer:ProximityCheck");
                mProximityCheckWakeLock.setReferenceCounted(false);
            }
            if (!mProximityCheckWakeLock.isHeld()) {
                // 4000ms for the standard DOZE_AOD start + a buffer to ensure the service stays awake
                mProximityCheckWakeLock.acquire(4800L);
            }

            mDelayedProximityCheckRunnable = () -> {
                // Check the value from the persistent monitor
                if (isAodActive && PersistentProximityMonitor.sLastProximityValue == 0.0f) {
                    try {
                        Object dozeMachine = XposedHelpers.getObjectField(dozeTriggersInstance, "mMachine");
                        Class<?> stateEnum = XposedHelpers.findClass("com.android.systemui.doze.DozeMachine$State", dozeTriggersInstance.getClass().getClassLoader());
                        @SuppressWarnings("rawtypes")
                        Enum targetState = Enum.valueOf((Class) stateEnum, "DOZE_AOD_PAUSING");
                        XposedHelpers.callMethod(dozeMachine, "requestState", targetState);
                    } catch (Throwable ignored) {}
                }
                // Release the wakelock regardless of the outcome
                stopProximityCheck();
            };

            // Wait for 4.4 seconds to ensure the system's native listeners are initialized
            mHandler.postDelayed(mDelayedProximityCheckRunnable, 4400L);

        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to check proximity: " + t);
            stopProximityCheck(); // Cleanup in case of an error
        }
    }


    private void stopProximityCheck() {
        if (mHandler != null && mDelayedProximityCheckRunnable != null) {
            mHandler.removeCallbacks(mDelayedProximityCheckRunnable);
            mDelayedProximityCheckRunnable = null;
        }
        if (mProximityCheckWakeLock != null && mProximityCheckWakeLock.isHeld()) {
            mProximityCheckWakeLock.release();
        }
    }

    private class BrightnessRunnable implements Runnable {
        private final Context mContext;
        private final SensorManager mSensorManager;
        private final Sensor mLightSensor;
        private final Object mDozeService;
        private boolean mInitFailed = false;

        // The check interval is not guaranteed on battery;
        // the next check will occur during the next system maintenance window (1...2 minutes)
        private static final long CHECK_INTERVAL_MS = 5000;
        private static final long SENSOR_TIMEOUT_MS = 400;

        BrightnessRunnable(Object dozeTriggersInstance) {
            Context ctx = null;
            Object dozeSvc = null;
            SensorManager sm = null;
            Sensor ls = null;
            try {
                ctx = (Context) XposedHelpers.getObjectField(dozeTriggersInstance, "mContext");
                if (ctx != null) {
                    Object dozeMachine = XposedHelpers.getObjectField(dozeTriggersInstance, "mMachine");
                    if (dozeMachine != null) dozeSvc = XposedHelpers.getObjectField(dozeMachine, "mDozeService");
                    sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
                    if (sm != null) ls = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
                } else { mInitFailed = true; }
            } catch (Throwable t) { mInitFailed = true; }
            mContext = ctx;
            mDozeService = dozeSvc;
            mSensorManager = sm;
            mLightSensor = ls;
            if (mSensorManager == null || mLightSensor == null) mInitFailed = true;
        }


        @Override
        public void run() {
            if (!isAodActive || mInitFailed) return;

            final Runnable timeoutRunnable[] = new Runnable[1];

            final SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (timeoutRunnable[0] != null) {
                        mHandler.removeCallbacks(timeoutRunnable[0]);
                    }
                    mSensorManager.unregisterListener(this);
                    if (event != null && event.values != null && event.values.length > 0) {
                        float lux = event.values[0];
                        float brightness = calculateBrightness(lux);
                        if (mDozeService != null) {
                            try {
                                // Try new float API first (Android 16 QPR2+)
                                XposedHelpers.callMethod(mDozeService, "setDozeScreenBrightness", brightness);
                            } catch (Throwable t) {
                                // Fallback to old int API
                                int intBrightness = (lux >= 170f) ? 3 : 1;
                                XposedHelpers.callMethod(mDozeService, "setDozeScreenBrightness", intBrightness);
                            }
                        }
                    }
                    scheduleNext();
                }
                @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
            };

            timeoutRunnable[0] = () -> {
                mSensorManager.unregisterListener(listener);
                scheduleNext();
            };

            try {
                mSensorManager.registerListener(listener, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
                mHandler.postDelayed(timeoutRunnable[0], SENSOR_TIMEOUT_MS);
            } catch (Throwable t) {
                scheduleNext();
            }
        }

        private void scheduleNext() {
            if (isAodActive) {
                mHandler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        }

        void stop() {
            // Cleanup on exiting AOD
            mHandler.removeCallbacks(this);
        }

        private float calculateBrightness(float lux) {
            // Doze brightness expects a float in [0..1].
            if (lux >= 170f) return 3.0f / 255f;
            return 1.0f / 255.0f;
        }
    }
}
