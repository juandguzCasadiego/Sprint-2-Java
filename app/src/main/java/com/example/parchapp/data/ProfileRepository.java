package com.example.parchapp.data;

import com.example.parchapp.util.Callback;

/* The user's backend profile: presence status (what groups see), program and verification, plus share links. Same callables the Flutter app uses (getProfile, updatePresence, createShareLink). */
public interface ProfileRepository {
    // presence codes accepted by the backend (communityService statuses)
    String ACTIVE = "active";
    String BUSY = "busy";
    String IN_CLASS = "inClass";
    String FOCUSED = "focused";
    String IN_TRANSIT = "inTransit";
    String OFFLINE = "offline";

    final class Profile {
        public final String status;
        public final String program;
        public final boolean verified;

        public Profile(String status, String program, boolean verified) {
            this.status = status == null ? ACTIVE : status;
            this.program = program == null ? "" : program;
            this.verified = verified;
        }
    }

    void getProfile(Callback<Profile> callback);

    /* expiresAtIso: UTC like 2026-10-27T19:58:42.558Z, at most 24 hours from now (backend rule) */
    void updatePresence(String status, String expiresAtIso, Callback<Profile> callback);

    /* kind: group, activity or profile. Returns the token of the link the backend stored in shareLinks */
    void createShareLink(String kind, String entityId, Callback<String> callback);

    static String linkFor(String token) {
        return "parchapp.me/join/" + token;
    }
}
