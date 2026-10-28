package com.example.parchapp.domain;

import com.example.parchapp.domain.model.CampusPlace;

import java.util.List;

// f1: tells if a location is inside the campus. the campus is not hardcoded anymore, it is the places of campusAreas in firestore: you are on campus if you are close to any of them
public final class CampusArea {
    // radius in meters around each campus place
    private static final double PLACE_RADIUS_METERS = 600;

    private CampusArea() {
    }

    public static boolean isOnCampus(double lat, double lng, List<CampusPlace> places) {
        for (CampusPlace place : places) {
            if (place.hasCoordinates()
                    && PlanContext.distanceMeters(lat, lng, place.getLat(), place.getLng()) <= PLACE_RADIUS_METERS) {
                return true;
            }
        }
        return false;
    }
}
