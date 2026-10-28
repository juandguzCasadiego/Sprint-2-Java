package com.example.parchapp.domain;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import com.example.parchapp.domain.model.PlannedActivity;
import com.example.parchapp.domain.model.TimeBlock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* F3: the context of a plan card: when the plan starts, how long it takes to walk to its place when Google Maps cannot answer, and whether the user will arrive late. Places and their coordinates come from campusAreas in Firestore (CampusPlaceRepository), never from code. */
public final class PlanContext {
    /* About 4.8 km/h, plus 30% because streets are not straight lines. */
    static final double WALKING_METERS_PER_MINUTE = 80;
    static final double DETOUR_FACTOR = 1.3;

    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?");
    private static final String[] DATE_FORMATS = {"EEE, MMM d, yyyy", "yyyy-MM-dd", "MMM d, yyyy", "EEE, MMM d"};

    private PlanContext() {
    }

    /* Walking estimate used when Google Maps does not answer (timeout and fallback). */
    public static int walkingMinutes(double fromLat, double fromLng, double toLat, double toLng) {
        double meters = distanceMeters(fromLat, fromLng, toLat, toLng) * DETOUR_FACTOR;
        return Math.max(1, (int) Math.ceil(meters / WALKING_METERS_PER_MINUTE));
    }

    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earth = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * earth * Math.asin(Math.sqrt(a));
    }

    /* Start minute of "5:00 PM – 6:00 PM", "7:30 PM" or "17:30", or -1 if there is no time. */
    public static int startMinute(String time) {
        return nthMinute(time, 0);
    }

    /* End minute of a range, or -1 when the time has no end. */
    public static int endMinute(String time) {
        return nthMinute(time, 1);
    }

    private static int nthMinute(String time, int index) {
        if (time == null) {
            return -1;
        }
        Matcher matcher = TIME.matcher(time);
        for (int i = 0; matcher.find(); i++) {
            if (i == index) {
                int hour = Integer.parseInt(matcher.group(1));
                int minute = Integer.parseInt(matcher.group(2));
                String period = matcher.group(3);
                if (period != null) {
                    boolean pm = period.equalsIgnoreCase("pm");
                    hour = hour % 12 + (pm ? 12 : 0);
                }
                return hour * 60 + minute;
            }
        }
        return -1;
    }

    /* True when the plan's date is today ("Today", "Tonight" and empty dates count as today). */
    public static boolean isToday(String date, Calendar now) {
        if (date == null || date.trim().isEmpty()) {
            return true;
        }
        String text = date.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("today") || text.startsWith("tonight")) {
            return true;
        }
        for (String pattern : DATE_FORMATS) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(now.getTimeZone());
            format.setLenient(false);
            try {
                Calendar parsed = Calendar.getInstance(now.getTimeZone());
                parsed.setTime(format.parse(date.trim()));
                boolean hasYear = pattern.contains("yyyy");
                return parsed.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)
                        && parsed.get(Calendar.MONTH) == now.get(Calendar.MONTH)
                        && (!hasYear || parsed.get(Calendar.YEAR) == now.get(Calendar.YEAR));
            } catch (ParseException ignored) {
                // try the next format
            }
        }
        return false;
    }

    /* The user cannot arrive before the start, and the plan is not over yet. */
    public static boolean isLate(int nowMinute, int travelMinutes, int startMinute, int endMinute) {
        if (startMinute < 0) {
            return false;
        }
        int end = endMinute > startMinute ? endMinute : startMinute + 60;
        return nowMinute < end && nowMinute + travelMinutes > startMinute;
    }

    public static final int FREE_NOW = 0;
    public static final int BUSY_NOW = 1;

    /* {FREE_NOW, minute the next busy block starts (1440 if none)} or {BUSY_NOW, minute i am free again}; back to back blocks count as one */
    public static int[] freeWindow(List<TimeBlock> busy, int nowMinute) {
        List<TimeBlock> sorted = new ArrayList<>(busy);
        sorted.sort((a, b) -> Integer.compare(a.getStartMinute(), b.getStartMinute()));
        int nextStart = 24 * 60;
        for (int i = 0; i < sorted.size(); i++) {
            TimeBlock block = sorted.get(i);
            if (block.getStartMinute() <= nowMinute && nowMinute < block.getEndMinute()) {
                int end = block.getEndMinute();
                for (int j = i + 1; j < sorted.size() && sorted.get(j).getStartMinute() <= end; j++) {
                    end = Math.max(end, sorted.get(j).getEndMinute());
                }
                return new int[]{BUSY_NOW, end};
            }
            if (block.getStartMinute() > nowMinute) {
                nextStart = Math.min(nextStart, block.getStartMinute());
            }
        }
        return new int[]{FREE_NOW, nextStart};
    }

    /* days from today to the plan date (0 today, 1 tomorrow, -1 yesterday), or null when there is no date we can read */
    public static Integer dayOffset(String date, Calendar now) {
        if (date == null || date.trim().isEmpty()) {
            return null;
        }
        String text = date.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("today") || text.startsWith("tonight")) {
            return 0;
        }
        for (String pattern : DATE_FORMATS) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(now.getTimeZone());
            format.setLenient(false);
            try {
                Calendar parsed = Calendar.getInstance(now.getTimeZone());
                parsed.setTime(format.parse(date.trim()));
                if (!pattern.contains("yyyy")) {
                    parsed.set(Calendar.YEAR, now.get(Calendar.YEAR));
                }
                Calendar today = (Calendar) now.clone();
                for (Calendar c : new Calendar[]{parsed, today}) {
                    c.set(Calendar.HOUR_OF_DAY, 12);
                    c.set(Calendar.MINUTE, 0);
                    c.set(Calendar.SECOND, 0);
                    c.set(Calendar.MILLISECOND, 0);
                }
                return (int) Math.round((parsed.getTimeInMillis() - today.getTimeInMillis()) / 86_400_000.0);
            } catch (ParseException ignored) {
                // try the next format
            }
        }
        return null;
    }

    /* plans dated today, for "You have N active plans today" */
    public static int countToday(List<PlannedActivity> plans, Calendar now) {
        int count = 0;
        for (PlannedActivity plan : plans) {
            Integer offset = dayOffset(plan.getDate(), now);
            if (offset != null && offset == 0) {
                count++;
            }
        }
        return count;
    }

    /* the next plan that has not ended: today ones still running or coming, otherwise the closest future day */
    public static PlannedActivity nextPlan(List<PlannedActivity> plans, Calendar now, int nowMinute) {
        PlannedActivity best = null;
        int bestDay = Integer.MAX_VALUE;
        int bestStart = Integer.MAX_VALUE;
        for (PlannedActivity plan : plans) {
            Integer offset = dayOffset(plan.getDate(), now);
            if (offset == null || offset < 0) {
                continue;
            }
            int start = startMinute(plan.getTime());
            if (offset == 0) {
                int end = endMinute(plan.getTime());
                int finish = end > start ? end : start >= 0 ? start + 60 : 24 * 60;
                if (finish <= nowMinute) {
                    continue;
                }
            }
            int sortStart = start < 0 ? 24 * 60 : start;
            if (offset < bestDay || (offset == bestDay && sortStart < bestStart)) {
                best = plan;
                bestDay = offset;
                bestStart = sortStart;
            }
        }
        return best;
    }

    public static TimeZone bogota() {
        return TimeZone.getTimeZone("America/Bogota");
    }
}
