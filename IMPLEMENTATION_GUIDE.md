# IMPLEMENTATION GUIDE - Guardian Accident Detection App

## 📋 Overview
Follow this guide to implement the 15 fixes in the Guardian project. All core source files have already been updated in the workspace.

## 🛠 Prerequisites
- Android Studio Ladybug or later.
- Gradle 8.x
- Minimum SDK 26 (Android 8.0)
- Target SDK 34 (Android 14)

## 📦 Implementation Steps

### PHASE 1: Source Code Verification
- ✅ **SensorService.java:** Verify `synchronized` blocks around sensor processing and `lastTriggerTime`.
- ✅ **EmergencyActivity.java:** Verify the new static inner `CallStateCallbackWrapper` and call retry limits.
- ✅ **MainActivity.java:** Verify `ALPHA = 0.25f` and `editor.commit()` in contact dialog.

### PHASE 2: Permission Verification
Check `AndroidManifest.xml` for the following:
```xml
<uses-permission android:name="android.permission.SEND_SMS"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.CALL_PHONE"/>
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
```

### PHASE 3: Testing Procedures
1. **Critical Functionality Test:**
   - Add an emergency contact in Settings.
   - Tilt the device > 55° while stationary for 3.5s.
   - Verify that the `EmergencyActivity` launches and the countdown starts.
2. **Resource Leak Test:**
   - Repeatedly open and close the `MainActivity` (10-20 times).
   - Use Android Studio's Profiler to monitor memory usage. Total memory should stabilize.
3. **Hardware Test:**
   - Verify the flashlight pulses SOS during an emergency.
   - Verify the vibrator pattern.
4. **Call Loop Test:**
   - Ensure the calling loop terminates after 6 attempts if no one answers.

## ⚠️ Troubleshooting
- **Crashes on Startup:** Verify that all permissions are granted. Check `Logcat` for `SecurityException`.
- **Sensors Not Working:** Ensure the device supports `TYPE_LINEAR_ACCELERATION` and `TYPE_ROTATION_VECTOR`.
- **SMS Not Sent:** Check if the device has a valid SIM card and credits.
