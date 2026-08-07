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
    private static volatile boolean isListening = false;
    private static SensorEventListener sProximityListener;
    private static SensorManager sSensorManager;
    private static Sensor sProximitySensor;
    private static BroadcastReceiver sUserPresentReceiver;

    // Dynamically update the monitor state based on the configuration flag
    public static synchronized void updateState(Context context, boolean enable) {
        if (context == null) return;

        // One-time initialization of managers, listener and receiver
        if (!isInitialized) {
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
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };

            sUserPresentReceiver = new BroadcastReceiver() {
                private final Handler mHandler = new Handler(Looper.getMainLooper());
                private final Runnable mReRegister = () -> {
                    if (AmbientDisplayOverride.sHookPersistentProximity) {
                        try {
                            boolean registered = sSensorManager.registerListener(
                                    sProximityListener,
                                    sProximitySensor,
                                    SensorManager.SENSOR_DELAY_NORMAL
                            );
                            if (registered) {
                                isListening = true;
                            } else {
                                AmbientDisplayOverride.logError("Failed to re-register proximity listener (returned false).");
                            }
                        } catch (Throwable t) {
                            AmbientDisplayOverride.logError("Failed to re-register listener: " + t);
                        }
                    }
                };

                // Re-register the listener to prevent its permanent hanging
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (AmbientDisplayOverride.sHookPersistentProximity && isListening) {
                        AmbientDisplayOverride.logInfo("ACTION_USER_PRESENT received. Re-registering proximity sensor listener.");
                        try {
                            sSensorManager.unregisterListener(sProximityListener);
                        } catch (Throwable t) {
                            AmbientDisplayOverride.logError("Failed to unregister listener during re-registration: " + t);
                        }
                        isListening = false;
                        mHandler.removeCallbacks(mReRegister);
                        mHandler.postDelayed(mReRegister, 20L);
                    }
                }
            };
            context.registerReceiver(sUserPresentReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
            isInitialized = true;
            AmbientDisplayOverride.logInfo("PersistentProximityMonitor initialized.");
        }

        // Toggle sensor registration based on the passed flag
        if (enable && !isListening) {
            try {
                boolean registered = sSensorManager.registerListener(
                        sProximityListener,
                        sProximitySensor,
                        SensorManager.SENSOR_DELAY_NORMAL
                );
                if (registered) {
                    isListening = true;
                    AmbientDisplayOverride.logInfo("PersistentProximityMonitor started.");
                } else {
                    AmbientDisplayOverride.logError("Failed to register proximity listener (returned false).");
                }
            } catch (Throwable t) {
                AmbientDisplayOverride.logError("Failed to register proximity listener: " + t);
            }
        } else if (!enable && isListening) {
            try {
                sSensorManager.unregisterListener(sProximityListener);
            } catch (Throwable t) {
                AmbientDisplayOverride.logError("Failed to unregister proximity listener: " + t);
            } finally {
                isListening = false;
                sLastProximityValue = -1f; // Reset to default when disabled
                AmbientDisplayOverride.logInfo("PersistentProximityMonitor stopped.");
            }
        }
    }
}
