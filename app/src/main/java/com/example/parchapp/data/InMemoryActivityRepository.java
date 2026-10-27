package com.example.parchapp.data;

import com.example.parchapp.domain.model.PlannedActivity;
import com.example.parchapp.util.Callback;
import com.example.parchapp.util.MainThread;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collections;

class InMemoryActivityRepository implements ActivityRepository {
    private final TravelTimeCache travelCache = new TravelTimeCache();
    private final List<PlannedActivity> activities = new ArrayList<>();

    @Override
    public void createActivity(PlannedActivity activity, Callback<PlannedActivity> callback) {
        PlannedActivity saved = activity.withId(UUID.randomUUID().toString());
        activities.add(saved);
        MainThread.post(() -> callback.onSuccess(saved));
    }

    @Override
    public void getActivities(String userId, Callback<List<PlannedActivity>> callback) {
        MainThread.post(() -> callback.onSuccess(Collections.unmodifiableList(new ArrayList<>(activities))));
    }

    @Override
    public Integer getCachedTravelMinutes(String activityId) {
        return travelCache.get(activityId);
    }

    @Override
    public void cacheTravelMinutes(String activityId, int minutes) {
        travelCache.put(activityId, minutes);
    }
}
