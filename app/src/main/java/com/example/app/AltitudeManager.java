package com.example.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class AltitudeManager implements SensorEventListener {
    private static final String TAG = "AltitudeManager";
    
    // Standard atmospheric pressure at sea level (hPa)
    private static final float PRESSURE_SEA_LEVEL = 1013.25f;
    
    // Callback interface
    public interface AltitudeListener {
        void onAltitudeChanged(AltitudeData altitudeData);
        void onFallDetected(FallEvent fallEvent);
    }
    
    // Data classes
    public static class AltitudeData {
        public float currentAltitude;      // Current altitude in meters
        public float baselineAltitude;     // Reference altitude (ground level)
        public float heightAboveGround;    // Height above ground
        public float currentPressure;      // Current atmospheric pressure (hPa)
        public float basePressure;         // Baseline pressure (at ground)
        public long timestamp;
        
        @Override
        public String toString() {
            return String.format(
                "Altitude: %.1f m | Height: %.1f m | Pressure: %.1f hPa",
                currentAltitude, heightAboveGround, currentPressure
            );
        }
    }
    
    public static class FallEvent {
        public float fallDistance;         // Distance fallen in meters
        public float maxHeightReached;     // Maximum height before fall
        public float impactAcceleration;   // G-force at impact
        public long fallDuration;          // Time to hit ground (ms)
        public boolean isSevere;           // Is it a significant fall?
        
        public FallEvent(float fallDistance, float maxHeight, float accel, long duration) {
            this.fallDistance = fallDistance;
            this.maxHeightReached = maxHeight;
            this.impactAcceleration = accel;
            this.fallDuration = duration;
            // Falls > 1 meter are considered severe
            this.isSevere = (fallDistance > 1.0f && accel > 20.0f);
        }
    }
    
    // State variables
    private SensorManager sensorManager;
    private Sensor pressureSensor;
    private Sensor accelerometerSensor;
    
    private AltitudeData currentAltitude = new AltitudeData();
    private float baselinePressure = PRESSURE_SEA_LEVEL;
    private float maxAltitudeReached = 0;
    private float lastAltitude = 0;
    private long fallStartTime = 0;
    private boolean isFalling = false;
    private float peakAcceleration = 0;
    
    private List<AltitudeListener> listeners = new ArrayList<>();
    
    public AltitudeManager(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        }
        
        if (pressureSensor == null) {
            Log.w(TAG, "Pressure sensor not available on this device");
        }
    }
    
    public void addAltitudeListener(AltitudeListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeAltitudeListener(AltitudeListener listener) {
        listeners.remove(listener);
    }
    
    public void startAltitudeTracking() {
        if (pressureSensor != null) {
            sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
            Log.d(TAG, "Started altitude tracking");
        }
        
        if (accelerometerSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }
    
    public void stopAltitudeTracking() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            Log.d(TAG, "Stopped altitude tracking");
        }
    }
    
    // ==================== SENSOR EVENTS ====================
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            handlePressureChange(event);
        } else if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            handleAccelerationChange(event);
        }
    }
    
    private void handlePressureChange(SensorEvent event) {
        float pressure = event.values[0];  // Pressure in hPa
        
        // Calculate altitude using barometric formula
        float altitude = calculateAltitude(pressure, baselinePressure);
        
        lastAltitude = currentAltitude.currentAltitude;
        currentAltitude.currentAltitude = altitude;
        currentAltitude.currentPressure = pressure;
        currentAltitude.heightAboveGround = altitude - currentAltitude.baselineAltitude;
        currentAltitude.timestamp = System.currentTimeMillis();
        
        // Track maximum altitude
        if (altitude > maxAltitudeReached) {
            maxAltitudeReached = altitude;
        }
        
        // Detect falling motion (altitude decreasing)
        // If altitude drops by 0.5 meters
        if (lastAltitude > 0 && altitude < lastAltitude - 0.5f) {
            if (!isFalling) {
                startFall();
            }
        }
        
        // Detect landing (altitude stable, was falling)
        if (isFalling && Math.abs(altitude - lastAltitude) < 0.2f) {
            endFall();
        }
        
        // Notify listeners
        for (AltitudeListener listener : listeners) {
            listener.onAltitudeChanged(currentAltitude);
        }
    }
    
    private void handleAccelerationChange(SensorEvent event) {
        float acceleration = (float) Math.sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        );
        
        // Track peak acceleration during fall
        if (isFalling && acceleration > peakAcceleration) {
            peakAcceleration = acceleration;
        }
    }
    
    // ==================== FALL DETECTION ====================
    
    private void startFall() {
        isFalling = true;
        fallStartTime = System.currentTimeMillis();
        peakAcceleration = 0;
        maxAltitudeReached = currentAltitude.currentAltitude;
        
        Log.d(TAG, "Fall detected! Height: " + currentAltitude.currentAltitude + " m");
    }
    
    private void endFall() {
        if (!isFalling) return;
        
        isFalling = false;
        long fallDuration = System.currentTimeMillis() - fallStartTime;
        float fallDistance = maxAltitudeReached - currentAltitude.currentAltitude;
        
        Log.d(TAG, String.format(
            "Fall ended! Distance: %.1f m | Duration: %d ms | Peak Accel: %.1f m/s²",
            fallDistance, fallDuration, peakAcceleration
        ));
        
        // Create fall event
        FallEvent fallEvent = new FallEvent(
            fallDistance,
            maxAltitudeReached,
            peakAcceleration,
            fallDuration
        );
        
        // Notify listeners
        for (AltitudeListener listener : listeners) {
            listener.onFallDetected(fallEvent);
        }
    }
    
    // ==================== ALTITUDE CALCULATIONS ====================
    
    /**
     * Calculate altitude using barometric formula
     */
    private float calculateAltitude(float pressure, float seaLevelPressure) {
        return 44330.0f * (1.0f - (float) Math.pow(
            pressure / seaLevelPressure,
            1.0f / 5.255f
        ));
    }
    
    /**
     * Set baseline pressure (calibrate for ground level)
     * e.g., Call this when the app loads or user resets.
     */
    public void calibrateBaseline() {
        baselinePressure = currentAltitude.currentPressure;
        currentAltitude.baselineAltitude = currentAltitude.currentAltitude; // Set baseline altitude to current
        maxAltitudeReached = currentAltitude.currentAltitude;
        Log.d(TAG, "Baseline calibrated. Pressure: " + baselinePressure + " hPa");
    }
    
    public AltitudeData getCurrentAltitude() {
        return currentAltitude;
    }
    
    public float getHeightAboveGround() {
        return currentAltitude.heightAboveGround;
    }
    
    public boolean isFalling() {
        return isFalling;
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Sensor accuracy changed: " + sensor.getName() + " = " + accuracy);
    }
}
