package com.example.app;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class SensorContextManager {
    private static final String TAG = "SensorContextManager";

    private static SensorContextManager instance;

    public static synchronized SensorContextManager getInstance() {
        if (instance == null) {
            instance = new SensorContextManager();
        }
        return instance;
    }

    private SensorContextManager() {
        super();
    }

    // Device context flags
    public static class DeviceContext {
        public DeviceContext() {
            super();
        }
        public boolean isInPocket;           // Based on proximity + light
        public boolean isStationary;         // Based on stationary sensor
        public boolean isMoving;             // Based on significant motion
        public boolean isWalking;            // Based on step detector
        public boolean isFlatOnGround;       // Based on gravity + rotation
        public float gravityX, gravityY, gravityZ;
        public long lastSignificantMotion;
        public int stepCount;
        public float lux;                    // Light level
        public boolean proximityBlocked;     // True = near user, False = away

        @Override
        public String toString() {
            return String.format(
                "Context{pocket:%b, stationary:%b, moving:%b, walking:%b, flat:%b}",
                isInPocket, isStationary, isMoving, isWalking, isFlatOnGround
            );
        }
    }

    private DeviceContext context = new DeviceContext();
    private List<ContextChangeListener> listeners = new ArrayList<>();

    public interface ContextChangeListener {
        void onContextChanged(DeviceContext newContext);
    }

    public void addContextListener(ContextChangeListener listener) {
        listeners.add(listener);
    }

    public DeviceContext getContext() {
        return context;
    }

    public void updateGravity(float x, float y, float z) {
        context.gravityX = x;
        context.gravityY = y;
        context.gravityZ = z;
        
        // Determine if flat on ground (gravity pointing down)
        updateFlatOnGround();
    }

    public void updateProximity(float distance, float maxRange) {
        context.proximityBlocked = (distance < maxRange);
    }

    public void updateLight(float lux) {
        context.lux = lux;
        updatePocketDetection();
    }

    public void updateStationary(boolean stationary) {
        context.isStationary = stationary;
        notifyListeners();
    }

    public void updateSteps(int steps) {
        context.stepCount = steps;
    }

    public void updateSignificantMotion() {
        context.isMoving = true;
        context.lastSignificantMotion = System.currentTimeMillis();
        notifyListeners();
    }

    private void updatePocketDetection() {
        // Phone in pocket if proximity blocked AND light is very low
        boolean wasPocket = context.isInPocket;
        context.isInPocket = context.proximityBlocked && context.lux < 5.0f;
        
        if (wasPocket != context.isInPocket) {
            Log.d(TAG, "Pocket detection: " + context.isInPocket);
            notifyListeners();
        }
    }

    private void updateFlatOnGround() {
        // Check if gravity vector points downward (phone flat on ground)
        // If gravity Z is dominant (pointing down), phone is likely flat
        float magnitude = (float) Math.sqrt(
            context.gravityX * context.gravityX +
            context.gravityY * context.gravityY +
            context.gravityZ * context.gravityZ
        );

        if (magnitude > 0) {
            float normalizedZ = context.gravityZ / magnitude;
            // If normalizedZ > 0.8, phone is likely flat on ground
            boolean wasFlat = context.isFlatOnGround;
            context.isFlatOnGround = Math.abs(normalizedZ) > 0.8f;
            
            if (wasFlat != context.isFlatOnGround) {
                Log.d(TAG, "Flat on ground: " + context.isFlatOnGround);
                notifyListeners();
            }
        }
    }

    private void notifyListeners() {
        for (ContextChangeListener listener : listeners) {
            listener.onContextChanged(context);
        }
    }
}
