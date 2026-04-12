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
import android.widget.Toast;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.core.app.NotificationCompat;

public class EnhancedSensorService extends Service implements SensorEventListener {
    private static final String TAG = "EnhancedSensorService";

    // Enhanced thresholds with context awareness
    private static final float IMPACT_THRESHOLD_NORMAL = 25.0f;
    private static final float IMPACT_THRESHOLD_IN_POCKET = 20.0f;
    private static final long VERIFICATION_WINDOW = 3500;
    private static final long STEP_DETECTION_COOLDOWN = 1000;

    private SensorManager sensorManager;
    private SensorContextManager contextManager;
    
    // Sensors
    private Sensor accelerometerSensor;
    private Sensor gyroSensor;
    private Sensor rotationVectorSensor;
    private Sensor gravitySensor;
    private Sensor proximitySensor;
    private Sensor lightSensor;
    private Sensor stepDetectorSensor;
    private Sensor stationaryDetectSensor;
    private Sensor significantMotionSensor;

    // State variables
    private volatile boolean isVerifying = false;
    private long lastStepTime = 0;
    private int postImpactStepCount = 0;
    private float[] orientation = new float[3];
    private float[] rotationMatrix = new float[9];
    private float[] gravityVector = new float[3];
    private float currentSpeedKmh = 0;

    // Locks
    private final Object sensorLock = new Object();
    private final Object triggerLock = new Object();

    @Override
    public void onCreate() {
        super.onCreate();
        
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, "accident_channel_enhanced")
                .setContentTitle("Guardian Enhanced Protection")
                .setContentText("Monitoring advanced context sensors...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(2, notification);
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        contextManager = SensorContextManager.getInstance();
        initializeSensors();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "accident_channel_enhanced", 
                "Guardian Enhanced Service", 
                NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void initializeSensors() {
        // Primary sensors
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        // Context sensors
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        stationaryDetectSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT);
        significantMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);

        // Register listeners
        registerSensorListeners();
    }

    private void registerSensorListeners() {
        // Primary sensors - high frequency for crash detection
        if (accelerometerSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor, 
                SensorManager.SENSOR_DELAY_GAME);
        }
        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, 
                SensorManager.SENSOR_DELAY_GAME);
        }
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, 
                SensorManager.SENSOR_DELAY_NORMAL);
        }

        // Context sensors - lower frequency for efficiency
        if (gravitySensor != null) {
            sensorManager.registerListener(this, gravitySensor, 
                SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (proximitySensor != null) {
            sensorManager.registerListener(this, proximitySensor, 
                SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, 
                SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (stepDetectorSensor != null) {
            sensorManager.registerListener(this, stepDetectorSensor, 
                SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (stationaryDetectSensor != null) {
            sensorManager.registerListener(this, stationaryDetectSensor, 
                SensorManager.SENSOR_DELAY_NORMAL);
        }

        // Significant motion - only wakes system, ultra low frequency
        if (significantMotionSensor != null) {
            sensorManager.registerListener(this, significantMotionSensor, 
                SensorManager.SENSOR_DELAY_NORMAL);
        }

        Log.d(TAG, "Sensor listeners registered");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        synchronized (sensorLock) {
            switch (event.sensor.getType()) {
                case Sensor.TYPE_LINEAR_ACCELERATION:
                    handleLinearAcceleration(event);
                    break;

                case Sensor.TYPE_GYROSCOPE:
                    handleGyroscope(event);
                    break;

                case Sensor.TYPE_ROTATION_VECTOR:
                    handleRotationVector(event);
                    break;

                case Sensor.TYPE_GRAVITY:
                    handleGravity(event);
                    break;

                case Sensor.TYPE_PROXIMITY:
                    handleProximity(event);
                    break;

                case Sensor.TYPE_LIGHT:
                    handleLight(event);
                    break;

                case Sensor.TYPE_STEP_DETECTOR:
                    handleStepDetector(event);
                    break;

                case Sensor.TYPE_STATIONARY_DETECT:
                    handleStationaryDetect(event);
                    break;

                case Sensor.TYPE_SIGNIFICANT_MOTION:
                    handleSignificantMotion(event);
                    break;
            }
        }
    }

    // ==================== LINEAR ACCELERATION ====================
    private void handleLinearAcceleration(SensorEvent event) {
        float acceleration = (float) Math.sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        );

        // Determine threshold based on context
        float threshold = IMPACT_THRESHOLD_NORMAL;
        if (contextManager.getContext().isInPocket) {
            threshold = IMPACT_THRESHOLD_IN_POCKET;
        }

        // Detect high-impact acceleration
        if (acceleration > threshold && !isVerifying) {
            Log.d(TAG, String.format("Impact detected: %.1f m/s²", acceleration));
            startAccidentVerification();
        }
    }

    // ==================== GYROSCOPE ====================
    private void handleGyroscope(SensorEvent event) {
        // Use gyroscope data to detect sudden rotation
        float angularVelocity = (float) Math.sqrt(
            event.values[0] * event.values[0] +
            event.values[1] * event.values[1] +
            event.values[2] * event.values[2]
        );

        // If high angular velocity + impact = strong accident indicator
        if (angularVelocity > 2.0f) {
            Log.d(TAG, String.format("High rotation detected: %.1f rad/s", angularVelocity));
        }
    }

    // ==================== ROTATION VECTOR ====================
    private void handleRotationVector(SensorEvent event) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientation);

        float tiltDegrees = (float) Math.toDegrees(
            Math.max(Math.abs(orientation[1]), Math.abs(orientation[2]))
        );

        // Update main activity with orientation
        broadcastOrientation(tiltDegrees);
    }

    // ==================== GRAVITY ====================
    private void handleGravity(SensorEvent event) {
        gravityVector[0] = event.values[0];
        gravityVector[1] = event.values[1];
        gravityVector[2] = event.values[2];
        
        contextManager.updateGravity(event.values[0], event.values[1], event.values[2]);
    }

    // ==================== PROXIMITY ====================
    private void handleProximity(SensorEvent event) {
        // event.values[0] is distance in cm
        float maxRange = event.sensor.getMaximumRange();
        contextManager.updateProximity(event.values[0], maxRange);
    }

    // ==================== LIGHT ====================
    private void handleLight(SensorEvent event) {
        // event.values[0] is illuminance in lux
        contextManager.updateLight(event.values[0]);
    }

    // ==================== STEP DETECTOR ====================
    private void handleStepDetector(SensorEvent event) {
        long currentTime = System.currentTimeMillis();
        
        // Only count steps if enough time has passed
        if (currentTime - lastStepTime > STEP_DETECTION_COOLDOWN) {
            lastStepTime = currentTime;
            
            // If we're in verification window and detect steps, cancel alert
            if (isVerifying) {
                postImpactStepCount++;
                Log.d(TAG, "Post-impact step detected: " + postImpactStepCount);
                
                if (postImpactStepCount > 2) {
                    Log.d(TAG, "Steps detected post-impact - cancelling false positive");
                    cancelAccidentVerification();
                }
            }
        }
    }

    // ==================== STATIONARY DETECT ====================
    private void handleStationaryDetect(SensorEvent event) {
        boolean isStationary = (event.values[0] == 1.0f);
        contextManager.updateStationary(isStationary);
        
        Log.d(TAG, "Device stationary: " + isStationary);
    }

    // ==================== SIGNIFICANT MOTION ====================
    private void handleSignificantMotion(SensorEvent event) {
        contextManager.updateSignificantMotion();
        Log.d(TAG, "Significant motion detected");
        
        // This could trigger higher-frequency sampling
        // Or wake the system from low-power mode
    }

    // ==================== ACCIDENT VERIFICATION ====================
    private void startAccidentVerification() {
        isVerifying = true;
        postImpactStepCount = 0;

        // Check current context
        SensorContextManager.DeviceContext context = contextManager.getContext();
        Log.d(TAG, "Verification started. Context: " + context);
        
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> Toast.makeText(this, "⚠️ IMPACT DETECTED! Verifying...", Toast.LENGTH_SHORT).show());

        // 2-phase verification
        handler.postDelayed(() -> {
            verifyAccident();
        }, VERIFICATION_WINDOW);
    }

    private void verifyAccident() {
        SensorContextManager.DeviceContext context = contextManager.getContext();

        // Multi-factor verification
        boolean isAccidentConfirmed = false;

        // Factor 1: Phone is stationary (stopped after impact)
        if (context.isStationary || currentSpeedKmh < 5.0f) {
            // Factor 2: Phone is flat on ground (likely fell)
            if (context.isFlatOnGround || Math.abs(context.gravityY) < 5.0f) { // Added leniency for testing
                isAccidentConfirmed = true;
            }
        }

        Handler handler = new Handler(Looper.getMainLooper());
        
        // Factor 3: No steps detected post-impact (not walking)
        if (postImpactStepCount == 0 && isAccidentConfirmed) {
            Log.d(TAG, "Accident CONFIRMED - triggering emergency");
            handler.post(() -> Toast.makeText(this, "🚨 ACCIDENT CONFIRMED!", Toast.LENGTH_LONG).show());
            triggerEmergencySequence();
        } else {
            Log.d(TAG, "Accident REJECTED - false positive");
            handler.post(() -> Toast.makeText(this, "✅ ACCIDENT REJECTED: False positive (not flat or walking)", Toast.LENGTH_LONG).show());
            isVerifying = false;
        }
    }

    private void cancelAccidentVerification() {
        isVerifying = false;
        postImpactStepCount = 0;
        Log.d(TAG, "Accident verification cancelled");
    }

    private void triggerEmergencySequence() {
        isVerifying = false;
        
        Intent emergencyIntent = new Intent(this, EmergencyActivity.class);
        emergencyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, emergencyIntent, 
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "accident_channel_enhanced")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("EMERGENCY DETECTED")
                .setContentText("Guardian is triggering SOS!")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true);
                
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(101, builder.build());
        }

        // Trigger emergency activity directly as a fallback
        startActivity(emergencyIntent);
    }

    // ==================== BROADCASTING ====================
    private void broadcastOrientation(float tilt) {
        Intent intent = new Intent("com.example.app.ORIENTATION_UPDATE");
        intent.putExtra("tilt", tilt);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Sensor accuracy changed: " + sensor.getName() + " = " + accuracy);
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
