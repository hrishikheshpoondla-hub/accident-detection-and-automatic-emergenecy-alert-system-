package com.example.app;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telephony.PhoneStateListener;
import android.telephony.SmsManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class EmergencyActivity extends AppCompatActivity {

    private static final String TAG = "EmergencyActivity";
    private TextView countdownText;
    private CountDownTimer timer;
    private final List<String> emergencyNumbers = new ArrayList<>();
    private int currentContactIndex = 0;
    private int totalCallAttempts = 0;
    private static final int MAX_CALL_ATTEMPTS = 6;

    private LocationManager locationManager;
    private Location bestLocation;
    private Vibrator vibrator;
    private TelephonyManager telephonyManager;
    private CameraManager cameraManager;
    private String cameraId;
    private boolean isCallActive = false;
    private final Handler callRetryHandler = new Handler(Looper.getMainLooper());
    private Runnable callTimeoutRunnable;
    
    private final Handler sosFlashHandler = new Handler(Looper.getMainLooper());
    private boolean isFlashOn = false;
    private boolean isSosActive = true;

    private static final String SENT = "SMS_SENT";
    private static final long CALL_TIMEOUT_MS = 45000; 
    
    private Object callStateCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency);

        countdownText = findViewById(R.id.countdownText);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        
        initFlashlight();
        setupCallMonitoring();
        loadEmergencyContacts();

        if (emergencyNumbers.isEmpty()) {
            Toast.makeText(this, "No valid emergency contacts found! SOS aborted.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        requestFreshLocation();
        startVibration();
        startFlashBlinking();

        timer = new CountDownTimer(10000, 1000) {
            public void onTick(long millisUntilFinished) {
                if (countdownText != null) {
                    countdownText.setText(String.valueOf(millisUntilFinished / 1000));
                }
                pulseVibration();
            }

            public void onFinish() {
                if (countdownText != null) {
                    countdownText.setText("0");
                }
                stopVibration();
                sendSmsAlerts();
                startCallingLoop();
            }
        }.start();
    }

    private void initFlashlight() {
        try {
            if (cameraManager != null) {
                String[] ids = cameraManager.getCameraIdList();
                for (String id : ids) {
                    // Check if camera has a flash
                    android.hardware.camera2.CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    Boolean hasFlash = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (hasFlash != null && hasFlash) {
                        cameraId = id;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Flashlight init error", e);
        }
    }

    private void loadEmergencyContacts() {
        SharedPreferences sharedPreferences = getSharedPreferences("GuardianPrefs", MODE_PRIVATE);
        String[] keys = {"emergency_contact_1", "emergency_contact_2", "emergency_contact_3"};
        for (String key : keys) {
            String number = sharedPreferences.getString(key, "");
            if (number != null && !number.trim().isEmpty() && isValidPhoneNumber(number)) {
                emergencyNumbers.add(number.trim());
            }
        }
    }

    private boolean isValidPhoneNumber(String number) {
        return number.matches("^[+]?[0-9]{8,15}$");
    }

    private void startFlashBlinking() {
        if (cameraId == null) return;
        sosFlashHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isSosActive) return;
                isFlashOn = !isFlashOn;
                toggleFlashlight(isFlashOn);
                sosFlashHandler.postDelayed(this, 400);
            }
        });
    }

    private void stopFlashBlinking() {
        isSosActive = false;
        sosFlashHandler.removeCallbacksAndMessages(null);
        toggleFlashlight(false);
    }

    private void toggleFlashlight(boolean state) {
        if (cameraManager != null && cameraId != null) {
            try {
                cameraManager.setTorchMode(cameraId, state);
            } catch (Exception e) {
                Log.e(TAG, "Toggle flashlight error", e);
            }
        }
    }

    private void setupCallMonitoring() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                CallStateCallbackWrapper callback = new CallStateCallbackWrapper(this);
                callStateCallback = callback;
                telephonyManager.registerTelephonyCallback(getMainExecutor(), callback);
            } else {
                PhoneStateListenerWrapper listener = new PhoneStateListenerWrapper(this);
                callStateCallback = listener;
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up call monitoring", e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static class CallStateCallbackWrapper extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        private final WeakReference<EmergencyActivity> activityRef;
        public CallStateCallbackWrapper(EmergencyActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }
        @Override
        public void onCallStateChanged(int state) {
            EmergencyActivity activity = activityRef.get();
            if (activity != null) activity.handleCallState(state);
        }
    }

    private static class PhoneStateListenerWrapper extends PhoneStateListener {
        private final WeakReference<EmergencyActivity> activityRef;
        public PhoneStateListenerWrapper(EmergencyActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }
        @Override
        public void onCallStateChanged(int state, String phoneNumber) {
            EmergencyActivity activity = activityRef.get();
            if (activity != null) activity.handleCallState(state);
        }
    }

    public void handleCallState(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_OFFHOOK:
                isCallActive = true;
                cancelCallTimeout();
                break;
            case TelephonyManager.CALL_STATE_IDLE:
                if (isCallActive) {
                    isCallActive = false;
                    moveToNextContact();
                }
                break;
        }
    }

    private void startVibration() {
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                long[] pattern = {0, 500, 500};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            } catch (Exception e) {
                Log.e(TAG, "Vibration error", e);
            }
        }
    }

    private void pulseVibration() {
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(100);
                }
            } catch (Exception e) {
                Log.e(TAG, "Pulse vibration error", e);
            }
        }
    }

    private void stopVibration() {
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping vibration", e);
            }
        }
    }

    private void requestFreshLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                bestLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (bestLocation == null) {
                    bestLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }

                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
                    @Override
                    public void onLocationChanged(@NonNull Location location) {
                        bestLocation = location;
                    }
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                    @Override public void onProviderEnabled(@NonNull String provider) {}
                    @Override public void onProviderDisabled(@NonNull String provider) {}
                }, Looper.getMainLooper());
            }
        } catch (Exception e) {
            Log.e(TAG, "Location request error", e);
        }
    }

    public void cancelAlert(View view) {
        cleanupAndFinish("Emergency Alert Cancelled");
    }

    private void cleanupAndFinish(String toastMessage) {
        isSosActive = false;
        if (timer != null) timer.cancel();
        stopVibration();
        stopFlashBlinking();
        cancelCallTimeout();
        callRetryHandler.removeCallbacksAndMessages(null);
        sosFlashHandler.removeCallbacksAndMessages(null);
        
        if (toastMessage != null) {
            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void sendSmsAlerts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            sendOfflineSOS();
        }
    }

    private void startCallingLoop() {
        if (emergencyNumbers.isEmpty() || !isSosActive) return;
        makeCall(emergencyNumbers.get(currentContactIndex));
    }

    private void makeCall(String number) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return;
        if (!isSosActive) return;

        totalCallAttempts++;
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + number));
        try {
            startActivity(callIntent);
            startCallTimeout();
        } catch (Exception e) {
            Log.e(TAG, "Call failed: " + e.getMessage());
            moveToNextContact();
        }
    }

    private void startCallTimeout() {
        cancelCallTimeout();
        callTimeoutRunnable = () -> {
            Log.d(TAG, "Call timed out - moving to next contact");
            moveToNextContact();
        };
        callRetryHandler.postDelayed(callTimeoutRunnable, CALL_TIMEOUT_MS);
    }

    private void cancelCallTimeout() {
        if (callTimeoutRunnable != null) {
            callRetryHandler.removeCallbacks(callTimeoutRunnable);
            callTimeoutRunnable = null;
        }
    }

    private void moveToNextContact() {
        if (emergencyNumbers.isEmpty() || !isSosActive) return;
        
        if (totalCallAttempts >= MAX_CALL_ATTEMPTS) {
            Log.d(TAG, "Max call attempts reached. Ending loop.");
            cleanupAndFinish(null);
            return;
        }

        currentContactIndex++;
        if (currentContactIndex >= emergencyNumbers.size()) {
            currentContactIndex = 0; // Restart list until MAX_CALL_ATTEMPTS
        }
        
        callRetryHandler.postDelayed(() -> {
            if (isSosActive) {
                makeCall(emergencyNumbers.get(currentContactIndex));
            }
        }, 5000);
    }

    private void sendOfflineSOS() {
        int battery = getBatteryLevel();
        String message = "🚨 Guardian SOS! Accident detected.\n" + "Battery: " + battery + "%\n";
        
        if (bestLocation != null) {
            message += "Location: https://maps.google.com/?q=" + bestLocation.getLatitude() + "," + bestLocation.getLongitude();
        } else {
            message += "Location: (No fix yet)";
        }

        try {
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }

            if (smsManager == null) return;

            PendingIntent sentPI = PendingIntent.getBroadcast(this, 0, new Intent(SENT), PendingIntent.FLAG_IMMUTABLE);
            ArrayList<String> parts = smsManager.divideMessage(message);
            
            for (String number : emergencyNumbers) {
                if (parts.size() > 1) {
                    ArrayList<PendingIntent> sentIntents = new ArrayList<>();
                    for (int i = 0; i < parts.size(); i++) sentIntents.add(sentPI);
                    smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, null);
                } else {
                    smsManager.sendTextMessage(number, null, message, sentPI, null);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS Error: " + e.getMessage());
        }
    }

    private int getBatteryLevel() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            return (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupAndFinish(null);
        
        if (telephonyManager != null && callStateCallback != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    telephonyManager.unregisterTelephonyCallback((TelephonyCallback) callStateCallback);
                } else {
                    telephonyManager.listen((PhoneStateListener) callStateCallback, PhoneStateListener.LISTEN_NONE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Cleanup error", e);
            }
        }
    }
}
