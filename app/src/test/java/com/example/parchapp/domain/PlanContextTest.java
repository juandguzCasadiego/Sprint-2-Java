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
        // About 1.15 km apart: 1.3 detour factor at 80 m/min is about 19 minutes.
        int far = PlanContext.walkingMinutes(4.6100, -74.0720, 4.6021, -74.0654);
        int near = PlanContext.walkingMinutes(4.6025, -74.0656, 4.6021, -74.0654);
        assertEquals(19, far);
        assertTrue(near >= 1 && near < far);
    }

    private static com.example.parchapp.domain.model.PlannedActivity plan(String title, String date, String time) {
        return new com.example.parchapp.domain.model.PlannedActivity("id-" + title, "g", title, "other", date, time,
                "", "PROPOSED", 0);
    }

    @Test
    public void picksTheNextPlanAndCountsToday() {
        Calendar now = day(2026, Calendar.OCTOBER, 27);
        java.util.List<com.example.parchapp.domain.model.PlannedActivity> plans = java.util.Arrays.asList(
                plan("over", "Tue, Oct 27, 2026", "8:00 AM - 9:00 AM"),
                plan("later", "Tue, Oct 27, 2026", "5:00 PM - 6:00 PM"),
                plan("soon", "Tue, Oct 27, 2026", "1:30 PM - 2:30 PM"),
                plan("tomorrow", "2026-10-28", "9:00 AM"),
                plan("yesterday", "2026-10-26", "9:00 AM"),
                plan("undated", "", "9:00 AM"));
        assertEquals(3, PlanContext.countToday(plans, now));
        assertEquals("soon", PlanContext.nextPlan(plans, now, 13 * 60).getTitle());
        assertEquals("tomorrow", PlanContext.nextPlan(plans, now, 19 * 60).getTitle());
        assertEquals(Integer.valueOf(1), PlanContext.dayOffset("2026-10-28", now));
        assertEquals(null, PlanContext.dayOffset("Friday", now));
    }

    @Test
    public void freeWindowFromBusyBlocks() {
        java.util.List<com.example.parchapp.domain.model.TimeBlock> busy = java.util.Arrays.asList(
                new com.example.parchapp.domain.model.TimeBlock(9 * 60, 10 * 60),
                new com.example.parchapp.domain.model.TimeBlock(10 * 60, 11 * 60),
                new com.example.parchapp.domain.model.TimeBlock(14 * 60, 15 * 60));
        int[] busyNow = PlanContext.freeWindow(busy, 9 * 60 + 30);
        assertEquals(PlanContext.BUSY_NOW, busyNow[0]);
        assertEquals(11 * 60, busyNow[1]); // back-to-back blocks merge
        int[] freeNow = PlanContext.freeWindow(busy, 12 * 60);
        assertEquals(PlanContext.FREE_NOW, freeNow[0]);
        assertEquals(14 * 60, freeNow[1]);
        assertEquals(1440, PlanContext.freeWindow(busy, 16 * 60)[1]);
    }
}
