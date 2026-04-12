package com.example.app;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;


public class AccidentDetectionManager implements VerticalMotionTracker.VerticalMotionListener, AltitudeManager.AltitudeListener {
    private static final String TAG = "AccidentDetectionManager";

    public interface AccidentAlertListener {
        void onSevereAccidentConfirmed(String details);
    }

    private VerticalMotionTracker verticalMotionTracker;
    private AltitudeManager altitudeManager;
    private AccidentAlertListener listener;

    // We keep a small rolling window of pressure to detect airbag spikes
    private float baselinePressureBeforeImpact = 0;

    public AccidentDetectionManager(Context context, VerticalMotionTracker verticalMotionTracker, AltitudeManager altitudeManager) {
        super();
        this.verticalMotionTracker = verticalMotionTracker;
        this.altitudeManager = altitudeManager;
        
        // Attach listeners
        if (this.verticalMotionTracker != null) {
            this.verticalMotionTracker.addVerticalMotionListener(this);
        }
        if (this.altitudeManager != null) {
            this.altitudeManager.addAltitudeListener(this);
        }
    }

    public void setAccidentAlertListener(AccidentAlertListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (verticalMotionTracker != null) verticalMotionTracker.startTracking();
        if (altitudeManager != null) altitudeManager.startAltitudeTracking();
        Log.d(TAG, "Accident Detection System Started");
    }

    public void stop() {
        if (verticalMotionTracker != null) verticalMotionTracker.stopTracking();
        if (altitudeManager != null) altitudeManager.stopAltitudeTracking();
    }

    // =================================================================
    // AltitudeManager Callbacks
    // =================================================================

    @Override
    public void onAltitudeChanged(AltitudeManager.AltitudeData altitudeData) {
        // Keep tracking the baseline pressure to compare if an impact happens
        baselinePressureBeforeImpact = altitudeData.currentPressure;
    }

    @Override
    public void onFallDetected(AltitudeManager.FallEvent fallEvent) {
        // The barometer detected a fall.
        Log.d(TAG, "Barometric Fall Detected! Distance: " + fallEvent.fallDistance);
    }

    // =================================================================
    // VerticalMotionTracker Callbacks
    // =================================================================

    @Override
    public void onHeightEstimated(float height) {
        // Ignored
    }

    @Override
    public void onAccidentDetected(float impactMagnitude) {
        // This is triggered by VerticalMotionTracker AFTER the rest-state verification!
        long impactTime = System.currentTimeMillis();

        Log.w(TAG, "Potential Accident Flagged by Accelerometer. Magnitude: " + impactMagnitude + " at " + impactTime);
        verifyAccidentWithSensorFusion(impactMagnitude, impactTime);
    }

    // =================================================================
    // Sensor Fusion Validation
    // =================================================================

    private void verifyAccidentWithSensorFusion(float impactMagnitude, long impactTime) {
        // 1. We know there was a massive impact AND the device is now at rest.
        // 2. Let's check the barometer for a sudden pressure spike (Airbag deployment typically causes a sharp, brief increase in cabin pressure)
        
        boolean airbagSuspected = false;
        if (altitudeManager != null) {
            float currentPressure = altitudeManager.getCurrentAltitude().currentPressure;
            
            // A rapid jump of ~2-5 hPa in a fraction of a second is indicative of an airbag
            if (Math.abs(currentPressure - baselinePressureBeforeImpact) > 2.0f) {
                airbagSuspected = true;
                Log.w(TAG, "Airbag Deployment Suspected! Pressure jump: " + (currentPressure - baselinePressureBeforeImpact) + " hPa");
            }
        }

        // Prepare the alert details
        String details = String.format("Time: %d | Impact Force: %.1f m/s² | Airbag Suspected: %b", impactTime, impactMagnitude, airbagSuspected);

        // If the user was registered traveling fast (GPS), we would factor that in here.
        // For now, if the VerticalMotionTracker confirmed rest-state after high-impact, we trigger the final alert.

        if (listener != null) {
            Log.e(TAG, "SEVERE ACCIDENT CONFIRMED. Triggering Alert Lifecycle.");
            listener.onSevereAccidentConfirmed(details);
        }
    }
}



class VerticalMotionTracker implements SensorEventListener {
    private static final String TAG = "VerticalMotionTracker";
    
    public interface VerticalMotionListener {
        void onHeightEstimated(float height);
        void onAccidentDetected(float impactMagnitude); // New callback
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
    
    // Robust Crash Detection State Machine
    public static final int STATE_IDLE = 0;
    public static final int STATE_FREE_FALL = 1;
    public static final int STATE_IMPACT = 2;
    public static final int STATE_REST_VERIFY = 3;
    
    private int crashState = STATE_IDLE;
    private long stateEntryTime = 0;
    private float maxImpactMagnitude = 0;
    private float restVarianceSum = 0;
    private int restSampleCount = 0;
    
    private List<VerticalMotionListener> listeners = new ArrayList<>();
    
    public VerticalMotionTracker(Context context) {
        super();
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
        
        float rawAccelX = event.values[0];
        float rawAccelY = event.values[1];
        float rawAccelZ = event.values[2];
        
        // Signal Vector Magnitude (SVM) for crash detection
        // Note: LINEAR_ACCELERATION excludes gravity, so rest state SVM is ~0
        float vectorMagnitude = (float) Math.sqrt(rawAccelX*rawAccelX + rawAccelY*rawAccelY + rawAccelZ*rawAccelZ);
        
        float pitch = orientation[1]; // pitch angle in radians
        float roll = orientation[2];  // roll angle in radians
        
        // Extrapolate vertical component of linear accel using orientation matrix
        // (Assuming device is mostly flat or vertical, but projecting onto earth's Z-axis)
        // Earth's Z-axis relative to device = [ -sin(pitch)*cos(roll), sin(roll), cos(pitch)*cos(roll) ]
        // We dot product device linear accel with Earth's Up vector:
        
        float earthUpX = (float) (-Math.sin(pitch) * Math.cos(roll));
        float earthUpY = (float) (Math.sin(roll));
        float earthUpZ = (float) (Math.cos(pitch) * Math.cos(roll));
        
        verticalAcceleration = (rawAccelX * earthUpX) + (rawAccelY * earthUpY) + (rawAccelZ * earthUpZ);
        
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
        
        // -------------------------------------------------------------
        // Crash Detection State Machine (Robust Pattern Analysis)
        // -------------------------------------------------------------
        
        float impactThreshold = 25.0f; // Safe baseline threshold for 2.5g impact
        
        switch (crashState) {
            case STATE_IDLE:
                // If a massive spike occurs -> Impact
                if (vectorMagnitude > impactThreshold) {
                    crashState = STATE_IMPACT;
                    stateEntryTime = currentTime;
                    maxImpactMagnitude = vectorMagnitude;
                    Log.d(TAG, "Crash Detection: IMPACT triggered. Mag: " + vectorMagnitude);
                }
                break;
                
            case STATE_IMPACT:
                // Track peak impact magnitude for 0.5s window
                if (vectorMagnitude > maxImpactMagnitude) {
                    maxImpactMagnitude = vectorMagnitude;
                }
                
                if (currentTime - stateEntryTime > 500) {
                    // Start Rest Verification Phase after impact
                    crashState = STATE_REST_VERIFY;
                    stateEntryTime = currentTime;
                    restVarianceSum = 0;
                    restSampleCount = 0;
                    Log.d(TAG, "Crash Detection: Entering REST_VERIFY phase.");
                }
                break;
                
            case STATE_REST_VERIFY:
                // Sample activity for 2.5 seconds to see if phone is resting or moving
                restVarianceSum += vectorMagnitude;
                restSampleCount++;
                
                // If there's continued extreme tumbling (> 10m/s^2), it's not at rest, but could still be part of a severe crash
                // However, false positive drops usually have a spike then immediate pick-up showing smaller movements.
                
                if (currentTime - stateEntryTime > AdaptiveThresholdManager.getRestVerificationDurationMs()) {
                    float averageRestActivity = restVarianceSum / Math.max(1, restSampleCount);
                    Log.d(TAG, "Crash Detection: REST_VERIFY finished. Avg Activity: " + averageRestActivity);
                    
                    // Since LINEAR_ACCELERATION is ~0 at rest, an average < threshold indicates quiescence
                    if (averageRestActivity < AdaptiveThresholdManager.getRestActivityThreshold()) {
                        Log.w(TAG, "CRITICAL: ACCIDENT CONFIRMED! Impact: " + maxImpactMagnitude);
                        for (VerticalMotionListener listener : listeners) {
                            listener.onAccidentDetected(maxImpactMagnitude);
                        }
                    } else {
                        Log.d(TAG, "Crash Detection: False positive filtered out. Device not at rest.");
                    }
                    
                    // Reset
                    crashState = STATE_IDLE;
                }
                break;
        }
        // -------------------------------------------------------------
        
        // Broadcast updates smoothly
        for (VerticalMotionListener listener : listeners) {
            listener.onHeightEstimated(estimatedHeight);
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}



class AltitudeManager implements SensorEventListener {
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

        public AltitudeData() {
            super();
            // Explicit no-arg constructor required
        }

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
            super();
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
        super();
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


class AdaptiveThresholdManager {
    private static final String TAG = "AdaptiveThreshold";

    public AdaptiveThresholdManager() {
        super();
    }

    // Base thresholds
    private static final float IMPACT_THRESHOLD_BASE = 25.0f;
    private static final float IMPACT_THRESHOLD_IN_POCKET = 20.0f;
    private static final float IMPACT_THRESHOLD_IN_BAG = 18.0f;
    private static final float IMPACT_THRESHOLD_IN_HAND = 28.0f;

    // Speed thresholds
    private static final float SPEED_THRESHOLD_CYCLING = 8.0f;
    private static final float SPEED_THRESHOLD_RIDING = 15.0f;
    private static final float SPEED_THRESHOLD_MOTORIZED = 25.0f;

    public static float getAdaptiveThreshold(SensorContextManager.DeviceContext context) {
        float threshold = IMPACT_THRESHOLD_BASE;

        // Adjust based on pocket detection
        if (context.isInPocket) {
            threshold = IMPACT_THRESHOLD_IN_POCKET;
        }
        // Could add bag detection in future
        // else if (context.isInBag) { threshold = IMPACT_THRESHOLD_IN_BAG; }

        // Adjust based on movement
        if (context.isWalking) {
            // Walking users might hold phone loosely - lower threshold
            threshold *= 0.95f;
        }

        Log.d(TAG, String.format("Adaptive threshold: %.1f (pocket: %b, walking: %b)",
            threshold, context.isInPocket, context.isWalking));

        return threshold;
    }

    public static float getRestActivityThreshold() {
        // Defines the maximum average motion (m/s^2) for the device to be considered "at rest" post-impact
        return 1.5f; 
    }

    public static long getRestVerificationDurationMs() {
        // Standard duration to wait after an impact to confirm the device has stopped moving
        return 2500; // 2.5 seconds
    }

    public static float getAdaptiveSpeedThreshold(float currentSpeed) {
        // Return appropriate stationary threshold based on activity
        if (currentSpeed < SPEED_THRESHOLD_CYCLING) {
            return SPEED_THRESHOLD_CYCLING;
        } else if (currentSpeed < SPEED_THRESHOLD_RIDING) {
            return SPEED_THRESHOLD_RIDING;
        } else if (currentSpeed < SPEED_THRESHOLD_MOTORIZED) {
            return SPEED_THRESHOLD_MOTORIZED;
        }
        return 0; // Only low speeds count as "stationary"
    }

    public static String getActivityType(float speed) {
        if (speed < 2.0f) return "STATIONARY";
        if (speed < 8.0f) return "WALKING";
        if (speed < 15.0f) return "CYCLING";
        if (speed < 25.0f) return "MOTORCYCLE";
        return "MOTORIZED";
    }
}
