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
import android.os.Environment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class AmbientDisplayOverride implements IXposedHookLoadPackage {

    static final String TAG = "[ADDimmer] ";
    private static final String TARGET_PACKAGE = "com.android.systemui";

    private Handler mHandler;
    private Runnable mBrightnessRunnable;
    private volatile boolean isAodActive = false;
    private WakeLock mWakeLock;
    static WakeLock mScreenOffFixWakeLock;
    private WakeLock mProximityCheckWakeLock;
    private Runnable mDelayedProximityCheckRunnable;

    private File getConfigFile() {
        return new File(Environment.getExternalStorageDirectory(), ".ADDimmer_config.txt");
    }
    private List<BrightnessConfig> mBrightnessConfigs = new ArrayList<>();
    private volatile boolean mUseDefaultConfig = true;
    private long mLastConfigModifiedTime = 0L;

    // Helper class to store and sort config pairs
    private static class BrightnessConfig implements Comparable<BrightnessConfig> {
        float luxThreshold;
        float brightnessNumerator;

        BrightnessConfig(float lux, float numerator) {
            this.luxThreshold = lux;
            this.brightnessNumerator = numerator;
        }

        @Override
        public int compareTo(BrightnessConfig other) {
            // Sort in descending order to check highest lux thresholds first
            return Float.compare(other.luxThreshold, this.luxThreshold);
        }
    }

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) return;

        // Start the persistent proximity monitor
        boolean hookedSystemUIApplication = false;

        try {
            final Class<?> systemUIApplicationClass = XposedHelpers.findClass(
                    "com.android.systemui.SystemUIApplication",
                    lpparam.classLoader);
            try {
                XposedHelpers.findAndHookMethod(systemUIApplicationClass, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context context = (Context) param.thisObject;
                            PersistentProximityMonitor.init(context);
                        } catch (Throwable t) {
                            CrashAnalyzer.analyzeAndLog(t, param.thisObject.getClass(), "SystemUI onCreate init");
                        }
                    }
                });
            } catch (Throwable t) {
                CrashAnalyzer.analyzeAndLog(t, systemUIApplicationClass, "Find SystemUI onCreate");
            }
            hookedSystemUIApplication = true;
        } catch (Throwable ignored) { }

        if (!hookedSystemUIApplication) {
            try {
                final Class<?> systemUIApplicationImplClass = XposedHelpers.findClass(
                        "com.android.systemui.application.impl.SystemUIApplicationImpl",
                        lpparam.classLoader);
                try {
                    XposedHelpers.findAndHookMethod(systemUIApplicationImplClass, "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context context = (Context) param.thisObject;
                                PersistentProximityMonitor.init(context);
                            } catch (Throwable t) {
                                CrashAnalyzer.analyzeAndLog(t, param.thisObject.getClass(), "SystemUI onCreate init");
                            }
                        }
                    });
                } catch (Throwable t) {
                    CrashAnalyzer.analyzeAndLog(t, systemUIApplicationImplClass, "Find SystemUI onCreate");
                }
            } catch (Throwable t) {
                CrashAnalyzer.analyzeClassNotFound(t, lpparam.classLoader, "com.android.systemui.SystemUIApplication", "Find SystemUIApplication");
                CrashAnalyzer.analyzeClassNotFound(t, lpparam.classLoader, "com.android.systemui.application.impl.SystemUIApplicationImpl", "Find SystemUIApplicationImpl");
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
            String errorMsg = t.getMessage();
            String expectedClass = "com.android.systemui.doze.DozeTriggers";
            if (errorMsg != null && errorMsg.contains("com.android.systemui.doze")) {
                expectedClass = errorMsg;
            }
            CrashAnalyzer.analyzeClassNotFound(t, lpparam.classLoader, expectedClass, "Find core Doze classes");
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
            XposedBridge.log(TAG + "Native AOD brightness control disabled.");
        } catch (Throwable t) {
            CrashAnalyzer.analyzeAndLog(t, dozeScreenBrightnessClass, "Disable native AOD brightness");
        }

        // --- Lifecycle management (start/stop) ---
        try {
            XposedHelpers.findAndHookMethod(dozeTriggersClass, "transitionTo", dozeStateEnum, dozeStateEnum, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Enum<?> oldState = (Enum<?>) param.args[0];
                        Enum<?> newState = (Enum<?>) param.args[1];

                        if (newState.name().equals("DOZE_AOD")) {
                            if (!isAodActive) {
                                isAodActive = true;
                        
                                // --- Reload configuration every time we enter AOD ---
                                loadConfig();
                        
                                XposedBridge.log(TAG + "AOD active. Starting checks.");

                                Object dozeTriggersInstance = param.thisObject;
                                if (mHandler == null) mHandler = new Handler(Looper.getMainLooper());

                                mBrightnessRunnable = new BrightnessRunnable(dozeTriggersInstance, dozeServiceClass);
                                mHandler.removeCallbacksAndMessages(null);

                                // Handle the transition to DOZE_AOD based on the previous state
                                if (oldState.name().equals("DOZE_AOD_PAUSED")) {
                                    XposedBridge.log(TAG + "AOD resumed from PAUSED. Delaying first check by 2s");
                                    acquireTempWakeLock((Context) XposedHelpers.getObjectField(dozeTriggersInstance, "mContext"), 2400L);
                                    mHandler.postDelayed(mBrightnessRunnable, 2000); // Phone is being taken out of a pocket, ensure the service stays awake during this time
                                } else if (oldState.name().equals("DOZE_AOD_PAUSING")) {
                                    XposedBridge.log(TAG + "AOD resumed from PAUSING.");
                                    mHandler.postDelayed(mBrightnessRunnable, 100); // A brief trigger of the proximity sensor
                                } else {
                                    mHandler.post(mBrightnessRunnable); // All other cases
                                    if (oldState.name().equals("INITIALIZED")) {
                                        startDelayedProximityCheck(param.thisObject, dozeStateEnum); // Screen turned off by the power button or timeout
                                    }
                                }
                            }
                        } else {
                            if (isAodActive) {
                                isAodActive = false;
                                XposedBridge.log(TAG + "AOD inactive. Stopping checks.");
                                stopAodListeners();
                            }
                        }
                    } catch (Throwable t) {
                        CrashAnalyzer.analyzeAndLog(t, param.thisObject.getClass(), "transitionTo callback execution");
                    }
                }
            });
        } catch (Throwable t) {
            CrashAnalyzer.analyzeAndLog(t, dozeTriggersClass, "Hook transitionTo");
        }

        // --- Ensure stop on service destruction ---
        try {
            XposedHelpers.findAndHookMethod(dozeServiceClass, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (isAodActive) {
                            isAodActive = false;
                            stopAodListeners();
                        }
                    } catch (Throwable t) {
                        CrashAnalyzer.analyzeAndLog(t, param.thisObject.getClass(), "DozeService onDestroy cleanup");
                    }
                }
            });
        } catch (Throwable t) {
            CrashAnalyzer.analyzeAndLog(t, dozeServiceClass, "Hook onDestroy");
        }

        // --- Ensure screen is off when in DOZE_AOD_PAUSED ---
        DozeScreenOffFixHook.hook(lpparam);
    }

    private void loadConfig() {
        File configFile = getConfigFile();

        if (!configFile.exists()) {
            createDefaultConfig();
            mUseDefaultConfig = true;
            mBrightnessConfigs.clear();
            mLastConfigModifiedTime = 0L;
            return;
        }

        // Check if the file has been modified since the last read
        long currentModifiedTime = configFile.lastModified();
        if (currentModifiedTime == mLastConfigModifiedTime && !mBrightnessConfigs.isEmpty()) {
            // File hasn't changed, use cached configuration
            return;
        }

        List<BrightnessConfig> newConfigs = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(":");
                if (parts.length == 2) {
                    float numerator = Float.parseFloat(parts[0].trim());
                    float lux = Float.parseFloat(parts[1].trim());

                    if (numerator < 0 || numerator > 255 || lux < 0) {
                        throw new IllegalArgumentException("Values out of bounds (0-255 expected)");
                    }
                    newConfigs.add(new BrightnessConfig(lux, numerator));
                }
            }

            if (newConfigs.size() > 10) {
                XposedBridge.log(TAG + "Config error: More than 10 pairs defined. Falling back to defaults.");
                mUseDefaultConfig = true;
                mBrightnessConfigs.clear();
            } else if (newConfigs.isEmpty()) {
                XposedBridge.log(TAG + "Config error: No valid pairs found. Falling back to defaults.");
                mUseDefaultConfig = true;
                mBrightnessConfigs.clear();
            } else {
                Collections.sort(newConfigs);
                mBrightnessConfigs = newConfigs;
                mUseDefaultConfig = false;

                mLastConfigModifiedTime = currentModifiedTime;
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + "Config parsing error: " + e.getMessage() + ". Falling back to defaults.");
            mUseDefaultConfig = true;
            mBrightnessConfigs.clear(); // Drop any previously cached data on read failure
        }
    }

    private void createDefaultConfig() {
        File file = getConfigFile();
        try {
            if (file.createNewFile()) {
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write("# Ambient Display Dimmer Configuration\n");
                    writer.write("# Format: <screen_brightness_numerator>:<lux_threshold>\n");
                    writer.write("# Maximum allowed pairs: 10. Behavior is stepwise (no interpolation).\n");
                    writer.write("1:0\n");
                    writer.write("3:160\n");
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + "Failed to create default config at " + file.getAbsolutePath() + ": " + e.getMessage());
        }
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

    // On Xperia 1 V (Lineage-based ROM), keeping a persistent proximity listener active appears to
    // allow SystemUI to detect the current proximity state immediately after registering its own
    // listener, causing it to transition to DOZE_AOD_PAUSING without additional intervention.
    // This method serves as a fallback for devices where the initial proximity state is not propagated,
    // forcing the transition only if it has not already happened.
    // In the common case, mDelayedProximityCheckRunnable is cancelled before execution because the system
    // reaches the desired state by itself.
    private void startDelayedProximityCheck(final Object dozeTriggersInstance, final Class<?> stateEnum) {
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
                    Object dozeMachine = null;
                    try {
                        dozeMachine = XposedHelpers.getObjectField(dozeTriggersInstance, "mMachine");
                        @SuppressWarnings("rawtypes")
                        Enum targetState = Enum.valueOf((Class) stateEnum, "DOZE_AOD_PAUSING");
                        XposedHelpers.callMethod(dozeMachine, "requestState", targetState);
                    } catch (Throwable t) {
                        // Dump the DozeMachine if available, otherwise fallback to DozeTriggers
                        Class<?> targetClass = dozeMachine != null ? dozeMachine.getClass() : dozeTriggersInstance.getClass();
                        CrashAnalyzer.analyzeAndLog(t, targetClass, "startDelayedProximityCheck (requestState)");
                    }
                }
                // Release the wakelock regardless of the outcome
                stopProximityCheck();
            };

            // Wait for 4.4 seconds to ensure the system's native listeners are initialized
            mHandler.postDelayed(mDelayedProximityCheckRunnable, 4400L);

        } catch (Throwable t) {
            CrashAnalyzer.analyzeAndLog(t, dozeTriggersInstance.getClass(), "startDelayedProximityCheck (outer init)");
            stopProximityCheck(); // Cleanup in case of an error
        }
    }


    private void stopProximityCheck() {
        if (mHandler != null && mDelayedProximityCheckRunnable != null) {
            mHandler.removeCallbacks(mDelayedProximityCheckRunnable);
            mDelayedProximityCheckRunnable = null;
        }
        if (mProximityCheckWakeLock != null && mProximityCheckWakeLock.isHeld()) {
            try {
                mProximityCheckWakeLock.release();
            } catch (Throwable ignored) { }
        }
    }

    private class BrightnessRunnable implements Runnable {
        private final Context mContext;
        private final SensorManager mSensorManager;
        private final Sensor mLightSensor;
        private final Object mDozeService;
        private final Class<?> mDozeServiceClass;
        private boolean mInitFailed = false;

        // The check interval is not guaranteed on battery;
        // the next check will occur during the next system maintenance window (1...2 minutes)
        private static final long CHECK_INTERVAL_MS = 5000;
        private static final long SENSOR_TIMEOUT_MS = 400;

        BrightnessRunnable(Object dozeTriggersInstance, Class<?> dozeServiceClass) {
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
            } catch (Throwable t) {
                mInitFailed = true;
                CrashAnalyzer.analyzeAndLog(t, dozeTriggersInstance.getClass(), "BrightnessRunnable Init");
            }
            mContext = ctx;
            mDozeService = dozeSvc;
            mDozeServiceClass = dozeServiceClass;
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
                        XposedBridge.log(TAG + "Lux detected: " + lux + " - Brightness set to: " + brightness);
                        if (mDozeService != null) {
                            try {
                                // Try new float API first (Android 16 QPR2+)
                                XposedHelpers.callMethod(mDozeService, "setDozeScreenBrightness", brightness);
                            } catch (Throwable t) {
                                // Fallback to old API
                                try {
                                    XposedHelpers.callMethod(mDozeService, "setDozeScreenBrightnessFloat", brightness);
                                } catch (Throwable fallbackT) {
                                    // Dump the DozeService class itself, as it might override methods
                                    CrashAnalyzer.analyzeAndLog(fallbackT, mDozeServiceClass, "Set Doze Screen Brightness APIs (DozeService)");
                                    // Dump its parent (DreamService), as the methods physically reside there in AOSP
                                    if (mDozeServiceClass != null && mDozeServiceClass.getSuperclass() != null) {
                                        CrashAnalyzer.analyzeAndLog(fallbackT, mDozeServiceClass.getSuperclass(), "Set Doze Screen Brightness APIs (DreamService)");
                                    }
                                }
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
            // Fallback to default logic if config failed or is absent
            if (mUseDefaultConfig || mBrightnessConfigs == null || mBrightnessConfigs.isEmpty()) {
                if (lux >= 160f) return 3.0f / 255f;
                return 1.0f / 255.0f;
            }

            // Stepwise logic using sorted custom config (descending order)
            for (BrightnessConfig config : mBrightnessConfigs) {
                if (lux >= config.luxThreshold) {
                    return config.brightnessNumerator / 255.0f;
                }
            }

            // Safety fallback: if current lux is lower than the lowest defined threshold,
            // return the brightness of the lowest available threshold (last element in sorted list)
            return mBrightnessConfigs.get(mBrightnessConfigs.size() - 1).brightnessNumerator / 255.0f;
        }
    }
}
