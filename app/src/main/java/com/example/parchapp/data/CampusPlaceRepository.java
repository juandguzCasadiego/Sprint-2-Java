package com.example.parchapp.data;

import androidx.annotation.Nullable;

import com.example.parchapp.domain.model.CampusPlace;
import com.example.parchapp.domain.model.PlannedActivity;
import com.example.parchapp.util.Callback;

import java.util.List;

/* The campusAreas collection: places where activities happen, with their coordinates. */
public interface CampusPlaceRepository {
    void getPlaces(Callback<List<CampusPlace>> callback);

    /* The place of an activity: its locationAreaId, or for older activities a place whose name or alias appears in the free-text location. Null when no place matches. */
    @Nullable
    static CampusPlace placeOf(PlannedActivity activity, List<CampusPlace> places) {
        for (CampusPlace place : places) {
            if (place.getId().equals(activity.getLocationAreaId())) {
                return place;
            }
        }
        // old activities dont have the area id, they only have the text the user wrote, so we try to find the place by its name or alias (like "library")
        for (CampusPlace place : places) {
            if (place.matches(activity.getLocation())) {
                return place;
            }
        }
        return null;
    }
}
