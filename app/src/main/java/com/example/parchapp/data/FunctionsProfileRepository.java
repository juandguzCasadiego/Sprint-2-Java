package com.example.parchapp.data;

import com.example.parchapp.util.Callback;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/* Calls the profile callables of the backend. The backend checks the presence values and the 24 hour expiry, and who may share what. */
class FunctionsProfileRepository implements ProfileRepository {
    private static final int SHARE_LINK_HOURS = 168;

    private final FirebaseFunctions functions;

    FunctionsProfileRepository(FirebaseFunctions functions) {
        this.functions = functions;
    }

    @Override
    public void getProfile(Callback<Profile> callback) {
        functions.getHttpsCallable("getProfile").call(new HashMap<String, Object>())
                .addOnSuccessListener(result -> callback.onSuccess(toProfile(result.getData())))
                .addOnFailureListener(callback::onError);
    }

    @Override
    public void updatePresence(String status, String expiresAtIso, Callback<Profile> callback) {
        Map<String, Object> request = new HashMap<>();
        request.put("status", status);
        // who can see it stays the default (my groups); the Flutter profile screen is where you change that
        request.put("visibility", "groups");
        request.put("expiresAt", expiresAtIso);
        functions.getHttpsCallable("updatePresence").call(request)
                .addOnSuccessListener(result -> callback.onSuccess(toProfile(result.getData())))
                .addOnFailureListener(callback::onError);
    }

    @Override
    public void createShareLink(String kind, String entityId, Callback<String> callback) {
        Map<String, Object> request = new HashMap<>();
        // a new request id every time, the backend uses it to not create the same link twice on retries
        request.put("requestId", UUID.randomUUID().toString().replace("-", ""));
        request.put("kind", kind);
        request.put("entityId", entityId);
        request.put("hours", SHARE_LINK_HOURS);
        functions.getHttpsCallable("createShareLink").call(request)
                .addOnSuccessListener(result -> {
                    Object token = asMap(result.getData()).get("token");
                    if (token == null) {
                        callback.onError(new IllegalStateException("The backend did not return a link"));
                    } else {
                        callback.onSuccess(token.toString());
                    }
                })
                .addOnFailureListener(callback::onError);
    }

    private static Profile toProfile(Object data) {
        Map<String, Object> map = asMap(data);
        Object status = map.get("status");
        Object program = map.get("program");
        return new Profile(status == null ? null : status.toString(), program == null ? "" : program.toString(),
                Boolean.TRUE.equals(map.get("verified")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }
}
