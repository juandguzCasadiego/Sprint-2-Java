package com.example.parchapp.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class PlanContextTest {

    private static Calendar day(int year, int month, int dayOfMonth) {
        Calendar calendar = Calendar.getInstance(PlanContext.bogota());
        calendar.clear();
        calendar.set(year, month, dayOfMonth, 12, 0);
        return calendar;
    }

    @Test
    public void readsStartAndEndOfATimeRange() {
        assertEquals(17 * 60, PlanContext.startMinute("5:00 PM – 6:00 PM"));
        assertEquals(18 * 60, PlanContext.endMinute("5:00 PM – 6:00 PM"));
        assertEquals(12 * 60 + 30, PlanContext.startMinute("12:30 PM"));
        assertEquals(30, PlanContext.startMinute("12:30 AM"));
        assertEquals(17 * 60 + 30, PlanContext.startMinute("17:30"));
        assertEquals(-1, PlanContext.startMinute("Friday"));
        assertEquals(-1, PlanContext.endMinute("7:30 PM"));
    }

    @Test
    public void recognisesTodayInTheFormatsTheAppWrites() {
        Calendar now = day(2026, Calendar.OCTOBER, 27);
        assertTrue(PlanContext.isToday("Tue, Oct 27, 2026", now));
        assertTrue(PlanContext.isToday("2026-10-27", now));
        assertTrue(PlanContext.isToday("Tonight", now));
        assertFalse(PlanContext.isToday("Wed, Oct 28, 2026", now));
        assertFalse(PlanContext.isToday("2025-10-27", now));
        assertFalse(PlanContext.isToday("Friday", now));
    }

    @Test
    public void lateWhenTheTripEndsAfterTheStart() {
        int start = 17 * 60;
        assertTrue(PlanContext.isLate(16 * 60 + 50, 15, start, 18 * 60));
        assertFalse(PlanContext.isLate(16 * 60 + 30, 15, start, 18 * 60));
        assertFalse(PlanContext.isLate(18 * 60 + 5, 15, start, 18 * 60)); // already over
        assertFalse(PlanContext.isLate(16 * 60, 15, -1, -1)); // no start time
    }

    @Test
    public void walkingEstimateGrowsWithDistance() {
        double[] library = PlanContext.venueCoordinates("Central Library • Room 302");
        double[] mainStreet = PlanContext.venueCoordinates("Main St Apartment");
        int near = PlanContext.walkingMinutes(PlanContext.CAMPUS_LAT, PlanContext.CAMPUS_LNG, library[0], library[1]);
        int far = PlanContext.walkingMinutes(PlanContext.CAMPUS_LAT, PlanContext.CAMPUS_LNG, mainStreet[0], mainStreet[1]);
        assertTrue(near >= 1 && near < 5);
        assertTrue(far > near);
        assertTrue(PlanContext.isKnownVenue("Campus Sports Center"));
        assertFalse(PlanContext.isKnownVenue("Somewhere else"));
    }
}
