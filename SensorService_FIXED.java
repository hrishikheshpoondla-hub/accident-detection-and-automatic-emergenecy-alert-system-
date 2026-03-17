package com.example.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.concurrent.atomic.AtomicBoolean;

public class SensorService extends Service implements SensorEventListener {

    private static final String TAG = "SensorService";
    private SensorManager sensorManager;
    private Sensor linearAccelerometer;
    private Sensor rotationSensor;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;
    private PowerManager.WakeLock wakeLock;

    // Detection Thresholds
    private static final float IMPACT_THRESHOLD = 35.0f; 
    private static final float TILT_THRESHOLD = 55.0f;   
    private static final float SPEED_STATIONARY = 3.0f;  
    private static final long VERIFICATION_WINDOW = 3500; 
    private static final long COOLDOWN = 20000;          

    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private final Object sensorLock = new Object();
    
    private volatile float currentSpeedKmh = 0f;
    private long lastTriggerTime = 0;
    private final Object triggerLock = new Object();
    
    private final AtomicBoolean isVerifying = new AtomicBoolean(false);
    private final Handler verificationHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, "accident_channel")
                .setContentTitle("Guardian Active Protection")
                .setContentText("Monitoring sensors for potential accidents...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, notification);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Guardian:AccidentDetection");
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

            if (linearAccelerometer != null) {
                sensorManager.registerListener(this, linearAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (rotationSensor != null) {
                sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupLocationUpdates();
    }

    private void setupLocationUpdates() {
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
                .setMinUpdateIntervalMillis(1500)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location l = locationResult.getLastLocation();
                if (l != null) {
                    float speed = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
                    currentSpeedKmh = speed > 1.5f ? speed : 0f;
                }
            }
        };

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting location updates", e);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            float rawAcc = (float) Math.sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2]);
            processDetection(rawAcc);
        } else if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            synchronized (sensorLock) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                SensorManager.getOrientation(rotationMatrix, orientation);
            }
        }
    }

    private void processDetection(float linearAcc) {
        long currentTime = System.currentTimeMillis();
        synchronized (triggerLock) {
            if (isVerifying.get() || (currentTime - lastTriggerTime < COOLDOWN)) return;
        }

        float maxTilt;
        synchronized (sensorLock) {
            maxTilt = (float) Math.toDegrees(Math.max(Math.abs(orientation[1]), Math.abs(orientation[2])));
        }

        // Phase 1: Sudden Impact or Fall Detection
        if ((linearAcc > IMPACT_THRESHOLD && maxTilt > TILT_THRESHOLD) || (maxTilt > TILT_THRESHOLD && currentSpeedKmh < 1.0f)) {
            Log.d(TAG, "Potential incident detected. Starting verification...");
            startVerification();
        }
    }

    private void startVerification() {
        if (!isVerifying.compareAndSet(false, true)) return;
        
        // Acquire wake lock for the duration of verification
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(VERIFICATION_WINDOW + 1000);
        }
        
        verificationHandler.postDelayed(() -> {
            float currentTilt;
            synchronized (sensorLock) {
                currentTilt = (float) Math.toDegrees(Math.max(Math.abs(orientation[1]), Math.abs(orientation[2])));
            }
            
            // Phase 2: Confirm if still tilted and stationary after window
            if (currentTilt > (TILT_THRESHOLD - 5) && currentSpeedKmh < SPEED_STATIONARY) {
                Log.e(TAG, "ACCIDENT CONFIRMED. Triggering Emergency Alert.");
                synchronized (triggerLock) {
                    lastTriggerTime = System.currentTimeMillis();
                }
                triggerEmergencyPopup();
            } else {
                Log.d(TAG, "Verification failed (User moving or upright). False alarm suppressed.");
            }
            isVerifying.set(false);
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }, VERIFICATION_WINDOW);
    }

    private void triggerEmergencyPopup() {
        Intent intent = new Intent(this, EmergencyActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        verificationHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("accident_channel", "Guardian Service", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
}
