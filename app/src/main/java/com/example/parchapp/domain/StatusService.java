package com.example.parchapp.domain;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.example.parchapp.data.ProfileRepository;
import com.example.parchapp.util.Callback;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/* The availability the user broadcasts to their groups. The backend profile (updatePresence / getProfile) is the source of truth; the device keeps a copy so the card shows instantly and offline. Also runs the accelerometer detector for the commuting suggestion (F1). */
public class StatusService implements SensorEventListener {

    public interface Listener {
        void onStatusChanged(StatusService status);

        default void onCommuteSuggested(StatusService status) {
        }
    }

    private static final String PREFS = "parchapp_status";
    private static final String KEY_AVAILABILITY = "availability";
    private static final String KEY_DURATION = "availability_duration";
    private static final String KEY_FROM_MOTION = "availability_from_motion";
    public static final String COMMUTING = "Commute / In Transit";
    private static final String KEY_LAST_MOTION_SUGGESTION = "last_motion_suggestion";
    private static final long MOTION_SUGGESTION_COOLDOWN_MS = 10 * 60 * 1000L;

    private final SharedPreferences prefs;
    private final ProfileRepository profiles;
    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final List<Listener> listeners = new ArrayList<>();
    private int movementSamples;
    private boolean detectingMotion;

    public StatusService(Context context, ProfileRepository profiles) {
        this.profiles = profiles;
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sensorManager = (SensorManager) context.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    /* Empty until the backend profile has been read once. */
    public String getAvailability() {
        return prefs.getString(KEY_AVAILABILITY, "");
    }

    public String getAvailabilityDuration() {
        return prefs.getString(KEY_DURATION, "For 1 hour");
    }

    /* Reads the status saved in the backend profile; the card then shows what groups see. */
    public void refreshFromBackend() {
        profiles.getProfile(new Callback<ProfileRepository.Profile>() {
            @Override
            public void onSuccess(ProfileRepository.Profile profile) {
                String label = labelFor(profile.status);
                if (!label.equals(getAvailability())) {
                    prefs.edit().putString(KEY_AVAILABILITY, label).putBoolean(KEY_FROM_MOTION, false).apply();
                }
                notifyListeners();
            }

            @Override
            public void onError(Exception error) {
                notifyListeners();
            }
        });
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
        // Groups see the status through the backend profile; the local copy is only for display.
        profiles.updatePresence(codeFor(availability), expiryFor(duration, System.currentTimeMillis()),
                new Callback<ProfileRepository.Profile>() {
                    @Override
                    public void onSuccess(ProfileRepository.Profile profile) {
                    }

                    @Override
                    public void onError(Exception error) {
                        android.util.Log.w("StatusService", "Could not save presence", error);
                    }
                });
    }

    /* Labels shown in Set Availability Status, mapped to the backend presence codes. */
    public static String codeFor(String label) {
        String text = label.toLowerCase(Locale.ROOT);
        if (text.startsWith("in class")) {
            return ProfileRepository.IN_CLASS;
        } else if (text.startsWith("deep focus")) {
            return ProfileRepository.FOCUSED;
        } else if (text.startsWith("busy")) {
            return ProfileRepository.BUSY;
        } else if (text.startsWith("commute")) {
            return ProfileRepository.IN_TRANSIT;
        } else if (text.startsWith("offline")) {
            return ProfileRepository.OFFLINE;
        }
        return ProfileRepository.ACTIVE;
    }

    public static String labelFor(String code) {
        switch (code == null ? "" : code) {
            case ProfileRepository.IN_CLASS:
                return "In Class";
            case ProfileRepository.FOCUSED:
                return "Deep Focus";
            case ProfileRepository.BUSY:
                return "Busy / Personal";
            case ProfileRepository.IN_TRANSIT:
                return COMMUTING;
            case ProfileRepository.OFFLINE:
                return "Offline";
            default:
                return "Available now";
        }
    }

    /* The backend only accepts statuses that expire within 24 hours. */
    public static String expiryFor(String duration, long nowMillis) {
        TimeZone bogota = TimeZone.getTimeZone("America/Bogota");
        Calendar expiry = Calendar.getInstance(bogota);
        expiry.setTimeInMillis(nowMillis);
        String text = duration == null ? "" : duration.toLowerCase(Locale.ROOT);
        if (text.contains("6:00 pm")) {
            expiry.set(Calendar.HOUR_OF_DAY, 18);
            expiry.set(Calendar.MINUTE, 0);
            if (expiry.getTimeInMillis() <= nowMillis) {
                expiry.setTimeInMillis(nowMillis + 3600_000L);
            }
        } else if (text.contains("tomorrow")) {
            expiry.add(Calendar.DAY_OF_MONTH, 1);
            expiry.set(Calendar.HOUR_OF_DAY, 7);
            expiry.set(Calendar.MINUTE, 0);
        } else if (text.contains("indefinite") || text.contains("manual")) {
            expiry.setTimeInMillis(nowMillis + 24 * 3600_000L - 60_000L);
        } else {
            expiry.setTimeInMillis(nowMillis + 3600_000L);
        }
        if (expiry.getTimeInMillis() - nowMillis > 24 * 3600_000L - 60_000L) {
            expiry.setTimeInMillis(nowMillis + 24 * 3600_000L - 60_000L);
        }
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        return iso.format(new Date(expiry.getTimeInMillis()));
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
