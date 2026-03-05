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
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

public class SensorService extends Service implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor rotationSensor;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;

    private static final float IMPACT_THRESHOLD_G = 6.0f; // 6G
    private static final float TILT_THRESHOLD_DEGREES = 45.0f;
    private static final float SPEED_THRESHOLD_KMH = 4.0f;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private float currentSpeedKmh = 0f;
    private long lastTriggerTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, "accident_channel")
                .setContentTitle("Guardian AI")
                .setContentText("Monitoring for accidents in the background...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, notification);
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (rotationSensor != null) {
                sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupLocationUpdates();
    }

    private void setupLocationUpdates() {
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
                .setMinUpdateIntervalMillis(1000)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    float speed = 0f;
                    if (location.hasSpeed()) {
                        speed = location.getSpeed() * 3.6f;
                    }
                    if (speed < SPEED_THRESHOLD_KMH) {
                        speed = 0f;
                    }
                    currentSpeedKmh = speed;
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            double gForce = Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;

            checkAccidentCondition(gForce);

        } else if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);
        }
    }

    private void checkAccidentCondition(double gForce) {
        // Roll is rotation around the Y-axis (tilting sideways)
        // Pitch is rotation around the X-axis (tilting forward/backward)
        float rollDegrees = (float) Math.toDegrees(orientation[2]);
        float pitchDegrees = (float) Math.toDegrees(orientation[1]);
        float maxTilt = Math.max(Math.abs(rollDegrees), Math.abs(pitchDegrees));

        boolean isJerkDetected = gForce >= IMPACT_THRESHOLD_G;
        boolean isOrientationLost = maxTilt > TILT_THRESHOLD_DEGREES;
        boolean isSpeedLow = currentSpeedKmh < SPEED_THRESHOLD_KMH;

        // Optimized logic for phone in a flat holder:
        // Trigger if:
        // 1. High impact AND significant tilt (sideways or forward/back)
        // 2. Significant tilt AND the bike has stopped (indicating it's down)
        if ((isJerkDetected && isOrientationLost) || (isOrientationLost && isSpeedLow && currentSpeedKmh == 0)) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastTriggerTime > 15000) {
                lastTriggerTime = currentTime;
                triggerEmergencyPopup();
            }
        }
    }

    private void triggerEmergencyPopup() {
        Intent intent = new Intent(this, EmergencyActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

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
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    "accident_channel",
                    "Accident Detection Service",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
