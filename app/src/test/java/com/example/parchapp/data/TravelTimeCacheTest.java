package com.example.parchapp.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TravelTimeCacheTest {

    @Test
    public void keepsMinutesPerActivityUntilTheyExpire() {
        long[] now = {1_000};
        TravelTimeCache cache = new TravelTimeCache(() -> now[0]);

        cache.put("study", 12);
        assertEquals(Integer.valueOf(12), cache.get("study"));
        assertNull(cache.get("soccer"));

        now[0] += TravelTimeCache.TTL_MS + 1;
        assertNull(cache.get("study"));
    }
}
