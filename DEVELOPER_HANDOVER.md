# 🛠️ Guardian: Developer Handover Manual
**Confidential: Technical Documentation for System Maintainers**

## 1. System Overview
Guardian is a high-reliability accident detection app. Unlike simple "tilt" sensors, it uses a multi-factor verification system to distinguish between a dropped phone and a high-speed collision.

## 2. Technical Architecture
### A. The Monitoring Layer (`SensorService_ENHANCED.java`)
- **Type:** Foreground Location Service.
- **Priority:** Must be high-priority to prevent Android's OOM (Out of Memory) killer from stopping it.
- **Logic:**
  - Uses `LINEAR_ACCELERATION` for impact detection.
  - Uses `GRAVITY` to determine post-impact orientation.
  - Uses `STATIONARY_DETECT` to confirm the user has stopped.

### B. The Emergency Layer (`EmergencyActivity.java`)
- **Type:** Activity with `FLAG_TURN_SCREEN_ON` and `FLAG_SHOW_WHEN_LOCKED`.
- **Audio:** Overrides system volume using `AudioManager.STREAM_ALARM`.
- **Communication:**
  - `SmsManager`: Sends location coordinates via Google Maps URL.
  - `Intent.ACTION_CALL`: Initiates recursive phone calls.

## 3. Critical Constants (The "Magic Numbers")
If the app is too sensitive or not sensitive enough, tune these in `SensorService_ENHANCED.java`:
- `IMPACT_THRESHOLD_NORMAL`: Default `25.0f`.
- `IMPACT_THRESHOLD_IN_POCKET`: Default `20.0f`.
- `VERIFICATION_WINDOW`: `3500ms` (3.5 seconds to decide if it was an accident).

## 4. Permission Requirements
The app requires these manual approvals in Android Settings to function:
1. **Location:** Must be set to "Allow all the time".
2. **Display over other apps:** MUST be enabled for the emergency pop-up to work in the background.
3. **Battery Optimization:** Should be "Unrestricted" to prevent service death.

## 5. Future Roadmap
- Implementation of a "Safe-Ride" timer.
- Connection to external Bluetooth OBD-II car scanners for airbag deployment triggers.
- Cloud syncing for real-time tracking of the user by family.

---
*Generated for handover on 2026-03-17.*
