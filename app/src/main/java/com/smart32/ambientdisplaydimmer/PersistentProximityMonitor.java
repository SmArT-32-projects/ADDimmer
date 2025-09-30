package com.smart32.ambientdisplaydimmer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import de.robv.android.xposed.XposedBridge;


// Persistently monitors the proximity sensor and stores its last value in a public static variable.
public class PersistentProximityMonitor {

    private static final String TAG = "[AD Dimmer] ";

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
            XposedBridge.log(TAG + "PersistentProximityMonitor: Proximity sensor not found.");
            return;
        }

        sProximityListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                // Update the value
                sLastProximityValue = event.values[0];
            }
            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        // Register the persistent listener.
        sSensorManager.registerListener(sProximityListener, sProximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        isInitialized = true;
        // XposedBridge.log(TAG + "PersistentProximityMonitor initialized.");
        BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            XposedBridge.log(TAG + "ACTION_SCREEN_ON received. Re-registering proximity sensor listener.");
            sSensorManager.unregisterListener(sProximityListener);
            sSensorManager.registerListener(sProximityListener, sProximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        };
        context.registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }
}
