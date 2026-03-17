package com.example.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class VerticalMotionTracker implements SensorEventListener {
    private static final String TAG = "VerticalMotionTracker";
    
    public interface VerticalMotionListener {
        void onVerticalVelocityChanged(float velocity);
        void onHeightEstimated(float height);
        void onFallDetected(float estimatedFallHeight);
    }
    
    private SensorManager sensorManager;
    private Sensor accelerometerSensor;
    private Sensor rotationVectorSensor;
    
    private float[] rotationMatrix = new float[9];
    private float[] orientation = new float[3];
    
    private float verticalAcceleration = 0;
    private float verticalVelocity = 0;
    private float estimatedHeight = 0;
    private float peakHeight = 0;
    private long lastUpdateTime = 0;
    
    private boolean isFalling = false;
    private float fallStartHeight = 0;
    
    private List<VerticalMotionListener> listeners = new ArrayList<>();
    
    public VerticalMotionTracker(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
    }
    
    public void addVerticalMotionListener(VerticalMotionListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void startTracking() {
        lastUpdateTime = System.currentTimeMillis();
        
        if (accelerometerSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME);
            Log.d(TAG, "Vertical Motion Tracking (Accel) started.");
        }
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }
    
    public void stopTracking() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);
        } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            handleVerticalAcceleration(event);
        }
    }
    
    private void handleVerticalAcceleration(SensorEvent event) {
        long currentTime = System.currentTimeMillis();
        long deltaTime = currentTime - lastUpdateTime;
        lastUpdateTime = currentTime;
        
        // Convert to seconds
        float deltaSeconds = deltaTime / 1000.0f;
        if (deltaSeconds <= 0 || deltaSeconds > 1.0f) {
            return; // Ignore jumps in time
        }
        
        // This is a rough estimation assuming the Y-axis of the phone is pointing "mostly" up/down.
        // A true 3D projection requires multiplying event.values by the inverse rotationMatrix,
        // but for simplicity and processing speed, we use the raw Y component (which points bottom-to-top of screen)
        // and adjust it by the pitch angle to extract the true vertical vector relative to earth's gravity.
        
        float rawAccelY = event.values[1];
        float rawAccelZ = event.values[2];
        
        float pitch = orientation[1]; // pitch angle in radians
        float roll = orientation[2];  // roll angle in radians
        
        // Extrapolate vertical component of linear accel using orientation matrix
        // (Assuming device is mostly flat or vertical, but projecting onto earth's Z-axis)
        // Earth's Z-axis relative to device = [ -sin(pitch)*cos(roll), sin(roll), cos(pitch)*cos(roll) ]
        // We dot product device linear accel with Earth's Up vector:
        
        float earthUpX = (float) (-Math.sin(pitch) * Math.cos(roll));
        float earthUpY = (float) (Math.sin(roll));
        float earthUpZ = (float) (Math.cos(pitch) * Math.cos(roll));
        
        verticalAcceleration = (event.values[0] * earthUpX) + (event.values[1] * earthUpY) + (event.values[2] * earthUpZ);
        
        // Filter out extreme micro-noise (drift compensation)
        if (Math.abs(verticalAcceleration) < 0.2f) {
            verticalAcceleration = 0;
            // Slowly bleed velocity to 0 to counter accumulation of drift when stationary
            verticalVelocity *= 0.9f; 
        }
        
        // Double integration: Accel -> Velocity -> Distance
        verticalVelocity += verticalAcceleration * deltaSeconds;
        estimatedHeight += verticalVelocity * deltaSeconds;
        
        // Highpass leak: slowly return estimated height towards 0 over long periods to prevent infinite drift
        estimatedHeight *= 0.999f;
        
        if (estimatedHeight > peakHeight) {
            peakHeight = estimatedHeight;
        }
        
        // Detect falling (velocity is highly negative, moving downwards quickly)
        if (verticalVelocity < -1.5f && !isFalling) {
            isFalling = true;
            fallStartHeight = peakHeight;
            Log.d(TAG, "Accel Fall started from relative height: " + fallStartHeight + " m");
        }
        
        // Detect landing (impact!)
        if (isFalling && verticalAcceleration > 5.0f /* Sudden spike in opposing accel */) {
            float fallDistance = fallStartHeight - estimatedHeight;
            isFalling = false;
            
            for (VerticalMotionListener listener : listeners) {
                listener.onFallDetected(Math.abs(fallDistance));
            }
            
            // Re-level height
            estimatedHeight = 0;
            verticalVelocity = 0;
            fallStartHeight = 0;
        }
        
        // Broadcast updates smoothly
        for (VerticalMotionListener listener : listeners) {
            listener.onVerticalVelocityChanged(verticalVelocity);
            listener.onHeightEstimated(estimatedHeight);
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
