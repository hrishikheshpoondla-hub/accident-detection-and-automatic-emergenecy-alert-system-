package com.example.app;

import android.util.Log;

public class AdaptiveThresholdManager {
    private static final String TAG = "AdaptiveThreshold";

    // Base thresholds
    private static final float IMPACT_THRESHOLD_BASE = 25.0f;
    private static final float IMPACT_THRESHOLD_IN_POCKET = 20.0f;
    private static final float IMPACT_THRESHOLD_IN_BAG = 18.0f;
    private static final float IMPACT_THRESHOLD_IN_HAND = 28.0f;

    // Speed thresholds
    private static final float SPEED_THRESHOLD_CYCLING = 8.0f;
    private static final float SPEED_THRESHOLD_RIDING = 15.0f;
    private static final float SPEED_THRESHOLD_MOTORIZED = 25.0f;

    public static float getAdaptiveThreshold(SensorContextManager.DeviceContext context) {
        float threshold = IMPACT_THRESHOLD_BASE;

        // Adjust based on pocket detection
        if (context.isInPocket) {
            threshold = IMPACT_THRESHOLD_IN_POCKET;
        }
        // Could add bag detection in future
        // else if (context.isInBag) { threshold = IMPACT_THRESHOLD_IN_BAG; }

        // Adjust based on movement
        if (context.isWalking) {
            // Walking users might hold phone loosely - lower threshold
            threshold *= 0.95f;
        }

        Log.d(TAG, String.format("Adaptive threshold: %.1f (pocket: %b, walking: %b)",
            threshold, context.isInPocket, context.isWalking));

        return threshold;
    }

    public static float getAdaptiveSpeedThreshold(float currentSpeed) {
        // Return appropriate stationary threshold based on activity
        if (currentSpeed < SPEED_THRESHOLD_CYCLING) {
            return SPEED_THRESHOLD_CYCLING;
        } else if (currentSpeed < SPEED_THRESHOLD_RIDING) {
            return SPEED_THRESHOLD_RIDING;
        } else if (currentSpeed < SPEED_THRESHOLD_MOTORIZED) {
            return SPEED_THRESHOLD_MOTORIZED;
        }
        return 0; // Only low speeds count as "stationary"
    }

    public static String getActivityType(float speed) {
        if (speed < 2.0f) return "STATIONARY";
        if (speed < 8.0f) return "WALKING";
        if (speed < 15.0f) return "CYCLING";
        if (speed < 25.0f) return "MOTORCYCLE";
        return "MOTORIZED";
    }
}
