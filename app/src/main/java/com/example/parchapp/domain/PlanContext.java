package com.example.parchapp.domain;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* F3: the context of a plan card. Where the venue is, when the plan starts, how long it takes to walk there when Google Maps cannot answer, and whether the user will arrive late. */
public final class PlanContext {
    /* Center of the campus, the same point CampusArea uses. */
    public static final double CAMPUS_LAT = 4.6015;
    public static final double CAMPUS_LNG = -74.0661;
    /* About 4.8 km/h, plus 30% because streets are not straight lines. */
    static final double WALKING_METERS_PER_MINUTE = 80;
    static final double DETOUR_FACTOR = 1.3;

    private static final String[][] VENUES = {
            // keyword, lat, lng (approximate points on and around campus)
            {"library", "4.6020", "-74.0655"}, {"biblioteca", "4.6020", "-74.0655"},
            {"sports", "4.6033", "-74.0648"}, {"deport", "4.6033", "-74.0648"},
            {"math", "4.6012", "-74.0651"}, {"student union", "4.6008", "-74.0667"},
            {"cafeter", "4.6010", "-74.0660"}, {"main st", "4.6120", "-74.0700"},
    };
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?");
    private static final String[] DATE_FORMATS = {"EEE, MMM d, yyyy", "yyyy-MM-dd", "MMM d, yyyy", "EEE, MMM d"};

    private PlanContext() {
    }

    /* Coordinates of a venue by name; unknown places fall back to the campus center. */
    public static double[] venueCoordinates(String location) {
        String text = location == null ? "" : location.toLowerCase(Locale.ROOT);
        for (String[] venue : VENUES) {
            if (text.contains(venue[0])) {
                return new double[]{Double.parseDouble(venue[1]), Double.parseDouble(venue[2])};
            }
        }
        return new double[]{CAMPUS_LAT, CAMPUS_LNG};
    }

    public static boolean isKnownVenue(String location) {
        String text = location == null ? "" : location.toLowerCase(Locale.ROOT);
        for (String[] venue : VENUES) {
            if (text.contains(venue[0])) {
                return true;
            }
        }
        return false;
    }

    /* Walking estimate used when Google Maps does not answer (timeout and fallback). */
    public static int walkingMinutes(double fromLat, double fromLng, double toLat, double toLng) {
        double meters = distanceMeters(fromLat, fromLng, toLat, toLng) * DETOUR_FACTOR;
        return Math.max(1, (int) Math.ceil(meters / WALKING_METERS_PER_MINUTE));
    }

    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
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

    public static TimeZone bogota() {
        return TimeZone.getTimeZone("America/Bogota");
    }
}
