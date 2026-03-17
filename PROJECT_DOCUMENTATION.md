# 🛡️ Guardian: Advanced Accident Detection System
**Technical Whitepaper & System Architecture**

## 1. Project Overview
Guardian is an Android-based emergency response system designed to detect high-impact accidents and automatically trigger a life-saving SOS sequence.

## 2. Sensor Intelligence Logic
### Phase 1: Impact Trigger
- **Sensor:** `TYPE_LINEAR_ACCELERATION`
- **Logic:** Monitors G-force excluding gravity. Trigger: >25m/s².

### Phase 2: False-Positive Filtering
- **Walking Check:** If `STEP_DETECTOR` triggers > 2 times post-impact, the alert is cancelled.
- **Stationary Check:** Uses `STATIONARY_DETECT` to confirm the device has come to a rest.
- **Pocket Mode:** Uses `PROXIMITY` and `LIGHT` sensors to adjust sensitivity.

## 3. Emergency SOS Protocol
1. **Voice Warning:** Instant Text-to-Speech announcement.
2. **Haptic/Visual Alert:** SOS Flashlight patterns and aggressive vibration.
3. **Audio Siren:** 100% volume alarm on `STREAM_ALARM` channel.
4. **Instant SOS:** 
   - SMS with Google Maps coordinates.
   - Recursive phone call loop.

## 4. Technical Roadmap (Brainstorming)
* **AI Impact Profiling:** Distinguishing between drop types using ML.
* **Smartwatch Heart-Rate Sync:** Triggering SOS based on biological data.
* **Blackbox Recording:** Capturing audio/video evidence during the countdown.

## 5. Development Specs
- **Threading:** Synchronized sensor locks for sub-ms accuracy.
- **Service:** High-priority Foreground Service (location-type).
- **UI:** Overlays lock screen using `FULL_SCREEN_INTENT`.
