package com.example.parchapp.data;

import com.example.parchapp.util.Callback;
import com.example.parchapp.util.MainThread;

/* Without a backend there is no profile, presence or share link; screens hide those parts. */
class UnavailableProfileRepository implements ProfileRepository {
    private static Exception unavailable() {
        return new IllegalStateException("No backend configured");
    }

    @Override
    public void getProfile(Callback<Profile> callback) {
        MainThread.post(() -> callback.onError(unavailable()));
    }

    @Override
    public void updatePresence(String status, String expiresAtIso, Callback<Profile> callback) {
        MainThread.post(() -> callback.onError(unavailable()));
    }

    @Override
    public void createShareLink(String kind, String entityId, Callback<String> callback) {
        MainThread.post(() -> callback.onError(unavailable()));
    }
}
