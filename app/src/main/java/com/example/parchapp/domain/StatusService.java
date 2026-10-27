package com.example.parchapp.domain;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.ArrayList;
import java.util.List;

/* The scenario owns the availability the user broadcasts to their groups: the quick status shown on the dashboard and the status chosen in the availability sheet. Kept on the device so it survives a process restart. */
public class StatusService implements SensorEventListener {

    public interface Listener {
        void onStatusChanged(StatusService status);

        default void onCommuteSuggested(StatusService status) {
        }
    }

    private static final String PREFS = "parchapp_status";
    private static final String KEY_FREE_UNTIL = "free_until";
    private static final String KEY_NEXT_ACTIVITY = "next_activity";
    private static final String KEY_AVAILABILITY = "availability";
    private static final String KEY_DURATION = "availability_duration";
    private static final String KEY_FROM_MOTION = "availability_from_motion";
    public static final String COMMUTING = "Commute / In Transit";
    private static final String KEY_LAST_MOTION_SUGGESTION = "last_motion_suggestion";
    private static final long MOTION_SUGGESTION_COOLDOWN_MS = 10 * 60 * 1000L;

    private final SharedPreferences prefs;
    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final List<Listener> listeners = new ArrayList<>();
    private int movementSamples;
    private boolean detectingMotion;

    public StatusService(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sensorManager = (SensorManager) context.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public String getFreeUntil() {
        return prefs.getString(KEY_FREE_UNTIL, "4:00 PM");
    }

    public String getNextActivity() {
        return prefs.getString(KEY_NEXT_ACTIVITY, "Library Study");
    }

    public String getAvailability() {
        return prefs.getString(KEY_AVAILABILITY, "Available now");
    }

    public String getAvailabilityDuration() {
        return prefs.getString(KEY_DURATION, "For 1 hour");
    }

    /** In the following code the empty values keep the current one, matching the quick status sheet. */
    public void updateQuickStatus(String freeUntil, String nextActivity) {
        SharedPreferences.Editor editor = prefs.edit();
        if (!freeUntil.isEmpty()) {
            editor.putString(KEY_FREE_UNTIL, freeUntil);
        }
        if (!nextActivity.isEmpty()) {
            editor.putString(KEY_NEXT_ACTIVITY, nextActivity);
        }
        editor.apply();
        notifyListeners();
    }

    public void setAvailability(String availability, String duration) {
        saveAvailability(availability, duration, false);
    }

    /* F1: the user accepted the commuting suggestion that came from the motion sensor. */
    public void acceptCommuteSuggestion() {
        saveAvailability(COMMUTING, "For 1 hour", true);
    }

    /* True when the current status was set from the motion sensor suggestion. */
    public boolean isFromMotion() {
        return prefs.getBoolean(KEY_FROM_MOTION, false);
    }

    private void saveAvailability(String availability, String duration, boolean fromMotion) {
        prefs.edit()
                .putString(KEY_AVAILABILITY, availability)
                .putString(KEY_DURATION, duration)
                .putBoolean(KEY_FROM_MOTION, fromMotion)
                .apply();
        notifyListeners();
    }

    /** Starts a foreground-only accelerometer detector; no background location permission is needed. */
    public void startMotionDetection() {
        if (detectingMotion || accelerometer == null) {
            return;
        }
        detectingMotion = sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        movementSamples = 0;
    }

    public void stopMotionDetection() {
        if (!detectingMotion || sensorManager == null) {
            return;
        }
        sensorManager.unregisterListener(this);
        detectingMotion = false;
        movementSamples = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }
        double magnitude = Math.sqrt(event.values[0] * event.values[0]
                + event.values[1] * event.values[1]
                + event.values[2] * event.values[2]);
        double linearAcceleration = Math.abs(magnitude - SensorManager.GRAVITY_EARTH);
        if (linearAcceleration >= 1.8) {
            movementSamples++;
        } else {
            movementSamples = Math.max(0, movementSamples - 1);
        }
        if (movementSamples >= 6) {
            movementSamples = 0;
            suggestCommute();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void suggestCommute() {
        if (COMMUTING.equals(getAvailability())) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_MOTION_SUGGESTION, 0L);
        if (now - last < MOTION_SUGGESTION_COOLDOWN_MS) {
            return;
        }
        prefs.edit().putLong(KEY_LAST_MOTION_SUGGESTION, now).apply();
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onCommuteSuggested(this);
        }
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onStatusChanged(this);
        }
    }
}
