# ENHANCED IMPLEMENTATION GUIDE v2 - Guardian

## 📋 Phase 1: Critical System Fixes (1 Hour)

### 1.1 Sensor Synchronization
**File:** `SensorService.java`
- **Change:** Implemented `sensorLock` for orientation data and `triggerLock` for timestamp management.
- **Impact:** Prevents "stale" or "corrupted" detection states caused by multiple threads accessing sensor arrays simultaneously.

### 1.2 Memory Leak Prevention
**File:** `EmergencyActivity.java`
- **Change:** Refactored `TelephonyCallback` to a `static` inner class with `WeakReference`.
- **Impact:** Ensures the Activity can be garbage collected when closed, preventing `OutOfMemoryError` during repeated alerts.

## 📋 Phase 2: Logic & Safety Hardening (1 Hour)

### 2.1 Calling Loop & Contact Validation
- **Limit:** `MAX_CALL_ATTEMPTS = 6`.
- **Validation:** Regex `^[+]?[0-9]{8,15}$` ensures only valid numbers enter the emergency queue.
- **Impact:** Prevents infinite loops and crashes from malformed data.

### 2.2 Hardware Robustness
- **Flashlight:** Checks `FLASH_INFO_AVAILABLE` before enabling torch mode.
- **Vibrator:** Uses `hasVibrator()` and wrapped in try-catch for legacy device compatibility.

## 📋 Phase 3: Build & Deployment

### 3.1 ProGuard / R8 Setup
Ensure `proguard-rules.pro` contains rules for Sceneform and GMS Location:
```pro
-keep class com.google.ar.sceneform.** { *; }
-keep class com.google.android.gms.location.** { *; }
```

### 3.2 Production Verification
1.  **Tilt Test:** Stationary tilt > 55° for 3.5s.
2.  **SMS Test:** Verify location link format.
3.  **Battery Test:** Monitor with Android Studio Power Profiler.
