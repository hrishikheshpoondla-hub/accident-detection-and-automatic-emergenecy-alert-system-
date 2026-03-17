package com.example.app;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.AnimationDrawable;
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
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.app.KeyguardManager;
import android.view.WindowManager;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class EmergencyActivity extends AppCompatActivity {

    private static final String TAG = "EmergencyActivity";
    private TextView countdownText, contactsInfo;
    private ProgressBar countdownProgress;
    private View pulseBackground, alertCard;
    private CountDownTimer timer;
    private final List<String> emergencyNumbers = new ArrayList<>();
    private int currentContactIndex = 0;
    private int totalCallAttempts = 0;
    private static final int MAX_CALL_ATTEMPTS = 6;

    private FusedLocationProviderClient fusedLocationClient;
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
    
    private TextToSpeech tts;
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private int originalVolume;
    private int lastSpokenSecond = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Wake up the screen and show activity over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        }
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        setContentView(R.layout.activity_emergency);

        countdownText = findViewById(R.id.countdownText);
        contactsInfo = findViewById(R.id.contactsInfo);
        countdownProgress = findViewById(R.id.countdownProgress);
        pulseBackground = findViewById(R.id.pulseBackground);
        alertCard = findViewById(R.id.alertCard);
        
        findViewById(R.id.cancelButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelAlert(v);
            }
        });

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        
        initFlashlight();
        setupCallMonitoring();
        loadEmergencyContacts();
        getLastKnownLocation();

        if (emergencyNumbers.isEmpty()) {
            Toast.makeText(this, "No valid emergency contacts found! SOS aborted.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (contactsInfo != null) {
            contactsInfo.setText(getString(R.string.alerting_contacts, emergencyNumbers.size()));
        }

        if (countdownProgress != null) {
            countdownProgress.setMax(10000);
            countdownProgress.setProgress(10000);
        }

        startAnimations();
        requestFreshLocation();
        startVibration();
        startFlashBlinking();
        initTextToSpeech();

        timer = new CountDownTimer(10000, 100) {
            public void onTick(long millisUntilFinished) {
                int seconds = (int) Math.ceil(millisUntilFinished / 1000.0);
                if (countdownText != null) {
                    countdownText.setText(String.valueOf(seconds));
                }
                pulseVibration();
                
                if (seconds <= 10 && seconds > 0 && seconds != lastSpokenSecond && tts != null) {
                    tts.speak(String.valueOf(seconds), TextToSpeech.QUEUE_FLUSH, null, "countdown_" + seconds);
                    lastSpokenSecond = seconds;
                }
            }

            public void onFinish() {
                if (countdownText != null) {
                    countdownText.setText("0");
                }
                if (countdownProgress != null) {
                    countdownProgress.setProgress(0);
                }
                stopVibration();
                startSirenAlarm();
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

    private void startAnimations() {
        if (pulseBackground != null) {
            ObjectAnimator scaleX = ObjectAnimator.ofFloat(pulseBackground, "scaleX", 1f, 1.4f);
            ObjectAnimator scaleY = ObjectAnimator.ofFloat(pulseBackground, "scaleY", 1f, 1.4f);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(pulseBackground, "alpha", 0.2f, 0.05f);

            scaleX.setRepeatCount(ValueAnimator.INFINITE);
            scaleX.setRepeatMode(ValueAnimator.REVERSE);
            scaleY.setRepeatCount(ValueAnimator.INFINITE);
            scaleY.setRepeatMode(ValueAnimator.REVERSE);
            alpha.setRepeatCount(ValueAnimator.INFINITE);
            alpha.setRepeatMode(ValueAnimator.REVERSE);

            AnimatorSet pulseSet = new AnimatorSet();
            pulseSet.playTogether(scaleX, scaleY, alpha);
            pulseSet.setDuration(1000);
            pulseSet.setInterpolator(new AccelerateDecelerateInterpolator());
            pulseSet.start();
        }

        if (alertCard != null) {
            alertCard.setAlpha(0f);
            alertCard.setTranslationY(100f);
            alertCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(800)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        }

        if (countdownProgress != null) {
            ObjectAnimator progressAnim = ObjectAnimator.ofInt(countdownProgress, "progress", 10000, 0);
            progressAnim.setDuration(10000);
            progressAnim.setInterpolator(new LinearInterpolator());
            progressAnim.start();
        }

        ImageView statusIndicator = findViewById(R.id.statusIndicator);
        if (statusIndicator != null && statusIndicator.getDrawable() instanceof AnimationDrawable) {
            ((AnimationDrawable) statusIndicator.getDrawable()).start();
        }
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

    private void initTextToSpeech() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
            }
        });
    }

    private void startSirenAlarm() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0);
        }

        try {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, alarmUri);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Error playing alarm", e);
        }
    }

    private void stopLoudAlarm() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "Error stopping media player", e);
            }
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0);
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

    private void getLastKnownLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                    if (location != null && bestLocation == null) {
                        bestLocation = location;
                        Log.d(TAG, "Last known location obtained: " + location.getLatitude() + ", " + location.getLongitude());
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting last known location", e);
        }
    }

    private void requestFreshLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                // Try Fused Location Provider for fresh update too
                com.google.android.gms.location.LocationRequest locationRequest = 
                    new com.google.android.gms.location.LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                        .setMinUpdateIntervalMillis(500)
                        .setMaxUpdates(1)
                        .build();

                fusedLocationClient.requestLocationUpdates(locationRequest, new com.google.android.gms.location.LocationCallback() {
                    @Override
                    public void onLocationResult(@NonNull com.google.android.gms.location.LocationResult locationResult) {
                        Location location = locationResult.getLastLocation();
                        if (location != null) {
                            bestLocation = location;
                            Log.d(TAG, "Fresh Fused location obtained: " + location.getLatitude() + ", " + location.getLongitude());
                        }
                    }
                }, Looper.getMainLooper());

                // Keep LocationManager as backup
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, new LocationListener() {
                    @Override
                    public void onLocationChanged(@NonNull Location location) {
                        if (bestLocation == null || location.getAccuracy() < bestLocation.getAccuracy()) {
                            bestLocation = location;
                        }
                    }
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                    @Override public void onProviderEnabled(@NonNull String provider) {}
                    @Override public void onProviderDisabled(@NonNull String provider) {}
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Location request error", e);
        }
    }

    private void sendSmsAlerts() {
        String message = "EMERGENCY! I need help. My current location: " +
                (bestLocation != null ? "https://maps.google.com/?q=" + bestLocation.getLatitude() + "," + bestLocation.getLongitude() : "Unknown");

        SmsManager smsManager;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            smsManager = getSystemService(SmsManager.class);
        } else {
            smsManager = SmsManager.getDefault();
        }

        if (smsManager == null) {
            Log.e(TAG, "SmsManager is null!");
            Toast.makeText(this, "SMS Error: System SMS service unavailable", Toast.LENGTH_SHORT).show();
            return;
        }

        for (String number : emergencyNumbers) {
            try {
                smsManager.sendTextMessage(number, null, message, null, null);
                Log.d(TAG, "SMS sent to " + number);
                Toast.makeText(this, "SMS Alert sent to " + number, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "SMS failed to " + number, e);
                Toast.makeText(this, "SMS Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startCallingLoop() {
        if (currentContactIndex < emergencyNumbers.size() && totalCallAttempts < MAX_CALL_ATTEMPTS) {
            makeCall(emergencyNumbers.get(currentContactIndex));
        } else {
            Toast.makeText(this, "Emergency sequence completed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void makeCall(String number) {
        try {
            Intent intent = new Intent(Intent.ACTION_CALL);
            intent.setData(Uri.parse("tel:" + number));
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                startActivity(intent);
                totalCallAttempts++;
                startCallTimeout();
            }
        } catch (Exception e) {
            Log.e(TAG, "Call failed to " + number, e);
            moveToNextContact();
        }
    }

    private void startCallTimeout() {
        cancelCallTimeout();
        callTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isCallActive) {
                    moveToNextContact();
                }
            }
        };
        callRetryHandler.postDelayed(callTimeoutRunnable, CALL_TIMEOUT_MS);
    }

    private void cancelCallTimeout() {
        if (callTimeoutRunnable != null) {
            callRetryHandler.removeCallbacks(callTimeoutRunnable);
        }
    }

    private void moveToNextContact() {
        currentContactIndex = (currentContactIndex + 1) % emergencyNumbers.size();
        startCallingLoop();
    }

    public void cancelAlert(View view) {
        if (timer != null) timer.cancel();
        stopVibration();
        stopFlashBlinking();
        stopLoudAlarm();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (timer != null) timer.cancel();
        stopVibration();
        stopFlashBlinking();
        stopLoudAlarm();
        cancelCallTimeout();
        if (telephonyManager != null && callStateCallback != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback((TelephonyCallback) callStateCallback);
            } else {
                telephonyManager.listen((PhoneStateListener) callStateCallback, PhoneStateListener.LISTEN_NONE);
            }
        }
    }
}
