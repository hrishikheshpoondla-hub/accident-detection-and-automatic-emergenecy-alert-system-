# QUICK REFERENCE - Guardian Accident Detection App

## ⚡ Error Matrix

| Severity | Issues | Priority |
|----------|--------|----------|
| 🔴 CRITICAL | Race Conditions, Memory Leaks, SMS NPE, Smoothing | Immediate |
| 🟠 HIGH | Location Nulls, Vibrator, Flash, Manifest Permissions | High |
| 🟡 MEDIUM | Call Loop, Contacts Validation, Async Prefs, SceneView | Standard |

## 📁 File Changes

### SensorService.java
- **Race Condition:** Added `triggerLock` and `sensorLock` synchronization.
- **Wakelock:** Added `PowerManager.WakeLock` for the verification phase.

### EmergencyActivity.java
- **Memory Leaks:** Converted `TelephonyCallback` and `PhoneStateListener` to static inner classes.
- **Robustness:** Added try-catch for `Vibrator` and `CameraManager`.
- **Logic:** Implemented `isValidPhoneNumber` and a 6-attempt calling limit.

### MainActivity.java
- **Dashboard Smoothing:** Set `ALPHA = 0.25f` for stable G-force readings.
- **Data Safety:** Changed `editor.apply()` to `editor.commit()` for contact saving.
- **Memory Cleanup:** Improved `onDestroy` to release `SceneView` and `bikeNode`.

## ✅ Test Checklist
- [ ] Emergency Alert trigger (stationary tilt > 55°)
- [ ] SOS Blinking (400ms interval)
- [ ] Pulse Vibration (1s interval)
- [ ] Contact Validation (reject invalid numbers)
- [ ] Calling Loop (rotates through contacts, stops after 6 attempts)
- [ ] SharedPreferences (restart app, verify contacts persist)
- [ ] Notification Channel (created on first run)
