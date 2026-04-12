package com.example.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class LowPowerSensorService extends Service implements SensorEventListener {
    private static final String TAG = "LowPowerService";
    
    private SensorManager sensorManager;
    private boolean isFullServiceActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            Sensor significantMotionSensor = sensorManager.getDefaultSensor(
                Sensor.TYPE_SIGNIFICANT_MOTION);
            
            // Register only significant motion sensor (ultra low power)
            if (significantMotionSensor != null) {
                sensorManager.registerListener(this, significantMotionSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
                Log.d(TAG, "Registered low-power significant motion sensor");
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_SIGNIFICANT_MOTION) {
            Log.d(TAG, "Significant motion detected - starting full sensor suite");
            
            // Start full SensorService
            if (!isFullServiceActive) {
                Intent fullServiceIntent = new Intent(this, EnhancedSensorService.class);
                startService(fullServiceIntent);
                isFullServiceActive = true;
                
                // Auto-stop full service after 2 minutes of inactivity
                scheduleFullServiceTimeout();
            }
        }
    }

    private void scheduleFullServiceTimeout() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            if (isFullServiceActive) {
                Log.d(TAG, "Stopping full sensor service - timeout");
                Intent fullServiceIntent = new Intent(this, EnhancedSensorService.class);
                stopService(fullServiceIntent);
                isFullServiceActive = false;
            }
        }, 120000); // 2 minutes
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
