package com.example.parchapp.data;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/* F7: travel minutes per activity, kept for a short time so reopening the Dashboard does not call Google Maps again. The time to a place changes as the user moves, so entries expire. */
class TravelTimeCache {
    static final long TTL_MS = 15 * 60 * 1000L;

    private final Map<String, long[]> entries = new HashMap<>();
    private final Clock clock;

    interface Clock {
        long now();
    }

    TravelTimeCache() {
        this(System::currentTimeMillis);
    }

    TravelTimeCache(Clock clock) {
        this.clock = clock;
    }

    @Nullable
    synchronized Integer get(String activityId) {
        long[] entry = entries.get(activityId);
        if (entry == null) {
            return null;
        }
        if (clock.now() - entry[1] > TTL_MS) {
            entries.remove(activityId);
            return null;
        }
        return (int) entry[0];
    }

    synchronized void put(String activityId, int minutes) {
        entries.put(activityId, new long[]{minutes, clock.now()});
    }
}
