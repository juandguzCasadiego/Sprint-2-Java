package com.example.parchapp.domain;

import android.location.Location;

// f1: tells if a location is inside the campus.
// the campus is a circle around the center of the university.
public final class CampusArea {
    // more or less the center of the campus (bogota) and the radius in meters
    private static final double CAMPUS_LAT = 4.6015;
    private static final double CAMPUS_LNG = -74.0661;
    private static final float CAMPUS_RADIUS_METERS = 600;

    private CampusArea() {
    }

    public static boolean isOnCampus(double lat, double lng) {
        float[] distance = new float[1];
        Location.distanceBetween(lat, lng, CAMPUS_LAT, CAMPUS_LNG, distance);
        return distance[0] <= CAMPUS_RADIUS_METERS;
    }
}
