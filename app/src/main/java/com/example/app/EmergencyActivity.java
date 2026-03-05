package com.example.app;

import android.Manifest;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.telephony.SmsManager;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.Locale;

public class EmergencyActivity extends AppCompatActivity {

    private TextView countdownText;
    private CountDownTimer timer;
    private static final String EMERGENCY_NUMBER = "9876543210"; // CHANGE THIS

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency);

        countdownText = findViewById(R.id.countdownText);

        timer = new CountDownTimer(10000, 1000) {
            public void onTick(long millisUntilFinished) {
                countdownText.setText(String.valueOf(millisUntilFinished / 1000));
            }

            public void onFinish() {
                countdownText.setText("0");
                sendEmergencyAlerts();
            }
        }.start();
    }

    public void cancelAlert(View view) {
        if (timer != null) {
            timer.cancel();
        }
        Toast.makeText(this, "Emergency Alert Cancelled", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void sendEmergencyAlerts() {
        sendSMS();
        makeCall();
        finish();
    }

    private void sendSMS() {
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location == null) {
            location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }

        String message;
        if (location != null) {
            message = "🚨 Emergency! Accident detected. My location:\n" +
                    "https://maps.google.com/?q=" +
                    location.getLatitude() + "," + location.getLongitude();
        } else {
            message = "🚨 Emergency! Accident detected. Location unavailable.";
        }

        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(EMERGENCY_NUMBER, null, message, null, null);
            Toast.makeText(this, "SMS Sent", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void makeCall() {
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + EMERGENCY_NUMBER));
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(callIntent);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (timer != null) {
            timer.cancel();
        }
    }
}
