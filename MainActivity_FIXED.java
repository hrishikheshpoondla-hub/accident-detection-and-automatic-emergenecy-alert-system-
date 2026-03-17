package com.example.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.SceneView;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ModelRenderable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "MainActivity";
    private TextView locationText, gForceValue, speedValue, orientationText;
    private Button emergencyButton;
    private OrientationView orientationView;
    private SceneView sceneView;
    private Node bikeNode;
    
    private SharedPreferences sharedPreferences;
    
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;

    private SensorManager sensorManager;
    private Sensor linearAccelerometer;
    private Sensor rotationSensor;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    
    private float currentSpeedKmh = 0f;
    private float filteredLinearAcc = 0f;
    private static final float ALPHA = 0.25f; // Balanced smoothing for dashboard

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int BACKGROUND_LOCATION_PERMISSION_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        locationText = findViewById(R.id.locationText);
        gForceValue = findViewById(R.id.gForceValue);
        speedValue = findViewById(R.id.speedValue);
        orientationText = findViewById(R.id.orientationText);
        emergencyButton = findViewById(R.id.emergencyButton);
        orientationView = findViewById(R.id.orientationView);
        sceneView = findViewById(R.id.sceneView);
        
        sharedPreferences = getSharedPreferences("GuardianPrefs", MODE_PRIVATE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        if (emergencyButton != null) {
            emergencyButton.setOnClickListener(v -> startEmergencyPopup());
        }

        setupLocationUpdates();
        checkAndRequestPermissions();
        init3DModel();
    }

    private void init3DModel() {
        if (sceneView == null) return;
        sceneView.getRenderer().setClearColor(new com.google.ar.sceneform.rendering.Color(0, 0, 0, 0));

        ModelRenderable.builder()
            .setSource(this, Uri.parse("bike_model.glb"))
            .setIsFilamentGltf(true)
            .build()
            .thenAccept(renderable -> {
                if (isDestroyed() || isFinishing()) return;
                bikeNode = new Node();
                bikeNode.setRenderable(renderable);
                bikeNode.setLocalScale(new Vector3(1.0f, 1.0f, 1.0f)); 
                bikeNode.setLocalPosition(new Vector3(0f, -0.2f, -1.0f)); 
                sceneView.getScene().addChild(bikeNode);
                sceneView.setVisibility(View.VISIBLE);
                if (orientationView != null) orientationView.setVisibility(View.GONE);
            })
            .exceptionally(throwable -> {
                Log.e(TAG, "3D Model Failed", throwable);
                return null;
            });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_emergency_contact) {
            showContactDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_contact, null);
        builder.setView(dialogView);

        EditText input1 = dialogView.findViewById(R.id.contactInput1);
        EditText input2 = dialogView.findViewById(R.id.contactInput2);
        EditText input3 = dialogView.findViewById(R.id.contactInput3);

        if (input1 != null) input1.setText(sharedPreferences.getString("emergency_contact_1", ""));
        if (input2 != null) input2.setText(sharedPreferences.getString("emergency_contact_2", ""));
        if (input3 != null) input3.setText(sharedPreferences.getString("emergency_contact_3", ""));

        builder.setTitle("Emergency Contacts")
               .setPositiveButton("Save", (dialog, which) -> {
                   SharedPreferences.Editor editor = sharedPreferences.edit();
                   editor.putString("emergency_contact_1", input1.getText().toString().trim());
                   editor.putString("emergency_contact_2", input2.getText().toString().trim());
                   editor.putString("emergency_contact_3", input3.getText().toString().trim());
                   // Use commit() for critical contact updates to ensure immediate persistence
                   if (editor.commit()) {
                       Toast.makeText(this, "Contacts Saved", Toast.LENGTH_SHORT).show();
                   } else {
                       Toast.makeText(this, "Error saving contacts!", Toast.LENGTH_SHORT).show();
                   }
               })
               .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.create().show();
    }

    private void startEmergencyPopup() {
        Intent intent = new Intent(this, EmergencyActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void checkAndRequestPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.SEND_SMS);
        perms.add(Manifest.permission.CALL_PHONE);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        perms.add(Manifest.permission.READ_PHONE_STATE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) perms.add(Manifest.permission.FOREGROUND_SERVICE);

        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                checkBackgroundLocationPermission();
                startSensorService();
            } else {
                Toast.makeText(this, "Permissions required for background monitoring", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startSensorService() {
        Intent intent = new Intent(this, SensorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            float rawAcc = (float) Math.sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2]);
            filteredLinearAcc = filteredLinearAcc + ALPHA * (rawAcc - filteredLinearAcc);
            
            if (gForceValue != null) {
                gForceValue.setText(String.format(Locale.getDefault(), "%.2f G", filteredLinearAcc / 9.81f));
            }
            
        } else if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientation);
            
            float pitchDeg = (float) Math.toDegrees(orientation[1]);
            float rollDeg = (float) Math.toDegrees(orientation[2]);
            
            if (orientationText != null) {
                orientationText.setText(String.format(Locale.getDefault(), "Tilt: %.1f°", Math.max(Math.abs(pitchDeg), Math.abs(rollDeg))));
            }
            
            if (bikeNode != null) {
                Quaternion baseRotation = Quaternion.axisAngle(new Vector3(0f, 1f, 0f), 90f);
                Quaternion tiltRotation = Quaternion.multiply(
                    Quaternion.axisAngle(new Vector3(0f, 0f, 1f), -rollDeg),
                    Quaternion.axisAngle(new Vector3(1f, 0f, 0f), pitchDeg + 90f)
                );
                bikeNode.setLocalRotation(Quaternion.multiply(tiltRotation, baseRotation));
            }
        }
    }

    private void setupLocationUpdates() {
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location l = result.getLastLocation();
                if (l != null) {
                    float speedKmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
                    currentSpeedKmh = speedKmh > 1.5f ? speedKmh : 0f;
                    
                    if (speedValue != null) speedValue.setText(String.format(Locale.getDefault(), "%.1f km/h", currentSpeedKmh));
                    if (locationText != null) locationText.setText(String.format(Locale.getDefault(), "Lat: %.5f, Lon: %.5f", l.getLatitude(), l.getLongitude()));
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            } catch (Exception e) {
                Log.e(TAG, "Location update request failed", e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null) {
            if (linearAccelerometer != null) sensorManager.registerListener(this, linearAccelerometer, SensorManager.SENSOR_DELAY_UI);
            if (rotationSensor != null) sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        }
        if (sceneView != null) {
            try { sceneView.resume(); } catch (Exception e) { Log.e(TAG, "SceneView resume error", e); }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (sceneView != null) {
            try { sceneView.pause(); } catch (Exception e) { Log.e(TAG, "SceneView pause error", e); }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sceneView != null) {
            try {
                sceneView.destroy();
                sceneView = null;
            } catch (Exception e) {
                Log.e(TAG, "SceneView destroy error", e);
            }
        }
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        bikeNode = null;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
