package com.smart32.ambientdisplaydimmer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

// Persistently monitors the proximity sensor and stores its last value in a public static variable.
public class PersistentProximityMonitor {

    public static volatile float sLastProximityValue = -1f;
    private static boolean isInitialized = false;
    private static SensorEventListener sProximityListener;
    private static SensorManager sSensorManager;
    private static Sensor sProximitySensor;

    // Method for one-time initialization
    public static void init(Context context) {
        if (isInitialized || context == null) {
            return;
        }

        sSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sSensorManager != null) {
            sProximitySensor = sSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }

        if (sProximitySensor == null) {
            AmbientDisplayOverride.logError("PersistentProximityMonitor: Proximity sensor not found.");
            return;
        }

        sProximityListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                sLastProximityValue = event.values[0];
            }
            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        // Register the persistent listener.
        sSensorManager.registerListener(sProximityListener, sProximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        isInitialized = true;
        AmbientDisplayOverride.logInfo("PersistentProximityMonitor initialized.");
        BroadcastReceiver userPresentReceiver = new BroadcastReceiver() {
            private final Handler mHandler = new Handler(Looper.getMainLooper());
            private final Runnable mReRegister = () -> {
                try {
                    sSensorManager.registerListener(sProximityListener, sProximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
                } catch (Throwable t) {
                    AmbientDisplayOverride.logError("Failed to re-register listener: " + t);
                }
            };

            // Re-register the listener to prevent its permanent hanging
            @Override
            public void onReceive(Context context, Intent intent) {
                AmbientDisplayOverride.logInfo("ACTION_USER_PRESENT received. Re-registering proximity sensor listener.");
                sSensorManager.unregisterListener(sProximityListener);
                mHandler.removeCallbacks(mReRegister);
                mHandler.postDelayed(mReRegister, 20L);
            }
        };
        context.registerReceiver(userPresentReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
    }
}
