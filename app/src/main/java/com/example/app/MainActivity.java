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
import android.telephony.SmsManager;
import android.text.InputType;
import android.util.Log;
import android.widget.LinearLayout;
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
import androidx.appcompat.widget.SwitchCompat;
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

public class MainActivity extends AppCompatActivity implements SensorEventListener, SensorContextManager.ContextChangeListener, AltitudeManager.AltitudeListener, VerticalMotionTracker.VerticalMotionListener {

    private static final String TAG = "MainActivity";
    private TextView locationText, gForceValue, speedValue, orientationText;
    private Button emergencyButton;
    private OrientationView orientationView;
    private SceneView sceneView;
    private Node bikeNode;
    
    private SensorContextManager contextManager;
    private AltitudeManager altitudeManager;
    private VerticalMotionTracker verticalMotionTracker;
    private AccidentDetectionManager accidentDetectionManager;
    private TextView contextDisplayView;
    private SwitchCompat modeSwitch;
    private View visualizationCard;
    private String latestAltitudeStr = "Altitude: -- m";
    
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
    private static final float ALPHA = 0.25f; 

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
        contextDisplayView = findViewById(R.id.contextDisplay);
        modeSwitch = findViewById(R.id.modeSwitch);
        visualizationCard = findViewById(R.id.visualizationCard);
        
        if (visualizationCard != null) {
            visualizationCard.setVisibility(View.GONE);
        }
        
        if (modeSwitch != null) {
            modeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (visualizationCard != null) {
                    visualizationCard.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
            });
        }
        
        sharedPreferences = getSharedPreferences("GuardianPrefs", MODE_PRIVATE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        contextManager = SensorContextManager.getInstance();
        contextManager.addContextListener(this);
        
        altitudeManager = new AltitudeManager(this);
        altitudeManager.addAltitudeListener(this);
        altitudeManager.startAltitudeTracking();
        
        verticalMotionTracker = new VerticalMotionTracker(this);
        verticalMotionTracker.addVerticalMotionListener(this);
        verticalMotionTracker.startTracking();

        accidentDetectionManager = new AccidentDetectionManager(this, verticalMotionTracker, altitudeManager);
        accidentDetectionManager.setAccidentAlertListener(details -> {
            runOnUiThread(() -> {
                sendEmergencySMS(details);
                startEmergencyPopup();
            });
        });
        accidentDetectionManager.start();

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
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        
        TextView desc = new TextView(this);
        desc.setText("Enter up to 3 phone numbers for SOS alerts and calls.");
        desc.setTextColor(android.graphics.Color.parseColor("#64748B"));
        desc.setTextSize(14f);
        desc.setPadding(0, 0, 0, padding/2);
        layout.addView(desc);
        
        EditText input1 = new EditText(this);
        input1.setHint("Contact 1 (e.g. +1234567890)");
        input1.setInputType(InputType.TYPE_CLASS_PHONE);
        layout.addView(input1);
        
        EditText input2 = new EditText(this);
        input2.setHint("Contact 2 (Optional)");
        input2.setInputType(InputType.TYPE_CLASS_PHONE);
        layout.addView(input2);
        
        EditText input3 = new EditText(this);
        input3.setHint("Contact 3 (Optional)");
        input3.setInputType(InputType.TYPE_CLASS_PHONE);
        layout.addView(input3);
        
        builder.setView(layout);

        input1.setText(sharedPreferences.getString("emergency_contact_1", ""));
        input2.setText(sharedPreferences.getString("emergency_contact_2", ""));
        input3.setText(sharedPreferences.getString("emergency_contact_3", ""));

        builder.setTitle("Emergency Contacts")
               .setPositiveButton("Save", (dialog, which) -> {
                   SharedPreferences.Editor editor = sharedPreferences.edit();
                   editor.putString("emergency_contact_1", input1.getText().toString().trim());
                   editor.putString("emergency_contact_2", input2.getText().toString().trim());
                   editor.putString("emergency_contact_3", input3.getText().toString().trim());
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
    
    private void sendEmergencySMS(String crashDetails) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Cannot send SMS. Permission denied.");
            return;
        }

        String contact1 = sharedPreferences.getString("emergency_contact_1", "");
        String contact2 = sharedPreferences.getString("emergency_contact_2", "");
        String contact3 = sharedPreferences.getString("emergency_contact_3", "");

        List<String> contacts = new ArrayList<>();
        if (!contact1.isEmpty()) contacts.add(contact1);
        if (!contact2.isEmpty()) contacts.add(contact2);
        if (!contact3.isEmpty()) contacts.add(contact3);

        if (contacts.isEmpty()) {
            Toast.makeText(this, "Accident detected, but no emergency contacts are saved!", Toast.LENGTH_LONG).show();
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
            String locationLink = "Unknown Location";
            if (location != null) {
                locationLink = "http://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
            }

            // SmsManager format
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }

            String message = "EMERGENCY: A severe accident has been detected!\n" +
                             crashDetails + "\n" +
                             "Location: " + locationLink;

            for (String number : contacts) {
                try {
                    smsManager.sendTextMessage(number, null, message, null, null);
                    Log.i(TAG, "Emergency SMS sent to: " + number);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send SMS to " + number, e);
                }
            }
            Toast.makeText(this, "Emergency SMS dispatched to contacts", Toast.LENGTH_LONG).show();
        });
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
        Intent intentEnhanced = new Intent(this, EnhancedSensorService.class);
        Intent intentLowPower = new Intent(this, LowPowerSensorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intentEnhanced);
        } else {
            startService(intentEnhanced);
        }
        startService(intentLowPower);
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
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
                .setMinUpdateIntervalMillis(200)
                .build();
                
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location l = result.getLastLocation();
                if (l != null) {
                    float rawSpeedKmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
                    boolean isStationary = contextManager != null && contextManager.getContext().isStationary;
                    
                    // Filter out GPS jitter when stationary or very slow 
                    if (isStationary || rawSpeedKmh < 2.5f) {
                        currentSpeedKmh = 0f;
                    } else {
                        currentSpeedKmh = rawSpeedKmh;
                    }
                    
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
        if (altitudeManager != null) {
            altitudeManager.stopAltitudeTracking();
        }
        if (verticalMotionTracker != null) {
            verticalMotionTracker.stopTracking();
        }
        if (accidentDetectionManager != null) {
            accidentDetectionManager.stop();
        }
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        bikeNode = null;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onContextChanged(SensorContextManager.DeviceContext context) {
        runOnUiThread(() -> updateContextDisplay(context));
    }

    @Override
    public void onAltitudeChanged(AltitudeManager.AltitudeData altitudeData) {
        // If barometer returns a real reading, use it
        if (altitudeData.currentAltitude > 0 || altitudeData.currentPressure > 0) {
            latestAltitudeStr = String.format(Locale.getDefault(), 
                "Barometer Alt: %.1f m (h: %.1f m)", 
                altitudeData.currentAltitude, altitudeData.heightAboveGround);
            
            if (contextManager != null) {
                runOnUiThread(() -> updateContextDisplay(contextManager.getContext()));
            }
        }
    }

    @Override
    public void onFallDetected(AltitudeManager.FallEvent fallEvent) {
        runOnUiThread(() -> {
            Toast.makeText(this, "BAROMETER FALL DETECTED: " + fallEvent.fallDistance + "m", Toast.LENGTH_LONG).show();
            if (fallEvent.isSevere) {
                startEmergencyPopup();
            }
        });
    }

    @Override
    public void onHeightEstimated(float height) {
        // Only override the string if Barometer failed/is not present
        if (!latestAltitudeStr.startsWith("Barometer")) {
            latestAltitudeStr = String.format(Locale.getDefault(), 
                "Accel Fall Ht: %.2f m", Math.abs(height));
            
            if (contextManager != null) {
                runOnUiThread(() -> updateContextDisplay(contextManager.getContext()));
            }
        }
    }

    @Override
    public void onAccidentDetected(float impactMagnitude) {
        // MainActivity defers this handling to AccidentDetectionManager
    }

    private void updateContextDisplay(SensorContextManager.DeviceContext context) {
        if (contextDisplayView == null) return;
        String contextStr = String.format(Locale.getDefault(),
            "📱 Context:\n" +
            "Pocket: %s\n" +
            "Moving: %s\n" +
            "Stationary: %s\n" +
            "Flat: %s\n" +
            "Light: %.0f lux\n" +
            "Proximity: %s\n" +
            "%s",
            context.isInPocket ? "✓" : "✗",
            context.isMoving ? "✓" : "✗",
            context.isStationary ? "✓" : "✗",
            context.isFlatOnGround ? "✓" : "✗",
            context.lux,
            context.proximityBlocked ? "Near" : "Away",
            latestAltitudeStr
        );

        contextDisplayView.setText(contextStr);
    }
}
