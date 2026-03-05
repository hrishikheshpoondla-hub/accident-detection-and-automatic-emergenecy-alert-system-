package com.example.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private TextView locationText, statusText, gForceValue, speedValue;
    private Button emergencyButton;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor rotationSensor;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];

    private static final float ACCIDENT_THRESHOLD = 58.86f; // Exactly 6G
    private static final float TILT_THRESHOLD_DEGREES = 45.0f;
    private static final float SPEED_THRESHOLD_KMH = 4.0f;
    
    private float currentSpeedKmh = 0f;
    private long lastTriggerTime = 0;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int BACKGROUND_LOCATION_PERMISSION_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        locationText = findViewById(R.id.locationText);
        statusText = findViewById(R.id.statusText);
        gForceValue = findViewById(R.id.gForceValue);
        speedValue = findViewById(R.id.speedValue);
        emergencyButton = findViewById(R.id.emergencyButton);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        if (emergencyButton != null) {
            emergencyButton.setOnClickListener(v -> {
                startEmergencyPopup();
            });
        }

        setupLocationUpdates();
        checkAndRequestPermissions();
    }

    private void startEmergencyPopup() {
        Intent intent = new Intent(this, EmergencyActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        permissionsNeeded.add(Manifest.permission.SEND_SMS);
        permissionsNeeded.add(Manifest.permission.CALL_PHONE);
        permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissionsNeeded.add(Manifest.permission.FOREGROUND_SERVICE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissionsNeeded.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String perm : permissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(perm);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            checkBackgroundLocationPermission();
            startSensorService();
        }
    }

    private void checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this)
                        .setTitle("Background Location Access")
                        .setMessage("This app needs background location access to detect accidents even when the app is closed. Please select 'Allow all the time' in the next screen.")
                        .setPositiveButton("OK", (dialog, which) -> {
                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BACKGROUND_LOCATION_PERMISSION_CODE);
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                        .create()
                        .show();
            }
        }
    }

    private void startSensorService() {
        Intent serviceIntent = new Intent(this, SensorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startLocationUpdates();
                checkBackgroundLocationPermission();
                startSensorService();
            } else {
                Toast.makeText(this, "Permissions required for full functionality", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == BACKGROUND_LOCATION_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Background location granted!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            float rawAcceleration = (float) Math.sqrt(x * x + y * y + z * z);
            float gValue = rawAcceleration / SensorManager.GRAVITY_EARTH;

            gForceValue.setText(String.format(Locale.getDefault(), "%.2f G", gValue));
            
            // Check for accident conditions
            checkAccidentCondition(rawAcceleration);

        } else if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);
        }
    }

    private void checkAccidentCondition(float rawAcceleration) {
        // Convert orientation to degrees
        float pitch = (float) Math.toDegrees(orientation[1]);
        float roll = (float) Math.toDegrees(orientation[2]);
        float maxTilt = Math.max(Math.abs(pitch), Math.abs(roll));

        boolean isJerkDetected = rawAcceleration >= ACCIDENT_THRESHOLD;
        boolean isOrientationLost = maxTilt > TILT_THRESHOLD_DEGREES;
        boolean isSpeedLow = currentSpeedKmh < SPEED_THRESHOLD_KMH;

        // Combined logic for accurate accident detection
        // Case 1: Jerk (Impact) + Speed drop + Tilt
        // Case 2: Jerk (Impact) + Tilt
        // Case 3: Tilt + Speed is 0 (Stationary fall/crash aftermath)
        
        if ((isJerkDetected && isOrientationLost) || (isOrientationLost && isSpeedLow && currentSpeedKmh == 0)) {
             long currentTime = System.currentTimeMillis();
             if (currentTime - lastTriggerTime > 15000) {
                 lastTriggerTime = currentTime;
                 gForceValue.setTextColor(Color.RED);
                 statusText.setText("🚨 Accident Detected!");
                 startEmergencyPopup();
             }
        } else if (isJerkDetected) {
            gForceValue.setTextColor(Color.RED);
        } else {
            gForceValue.setTextColor(Color.parseColor("#A855F7"));
        }
    }

    private void setupLocationUpdates() {
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
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

                    speedValue.setText(String.format(Locale.getDefault(), "%.1f km/h", currentSpeedKmh));
                    locationText.setText(String.format(Locale.getDefault(), "Lat: %.5f, Lon: %.5f", location.getLatitude(), location.getLongitude()));
                }
            }
        };
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        }
        startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
