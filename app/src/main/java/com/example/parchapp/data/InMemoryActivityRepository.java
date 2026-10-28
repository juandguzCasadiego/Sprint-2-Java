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

    @Override
    public void getActivity(String activityId, Callback<PlannedActivity> callback) {
        for (PlannedActivity activity : activities) {
            if (activity.getId().equals(activityId)) {
                MainThread.post(() -> callback.onSuccess(activity));
                return;
            }
        }
        MainThread.post(() -> callback.onError(new IllegalStateException("Activity not found")));
    }

    @Override
    public void confirmActivity(String activityId, Callback<PlannedActivity> callback) {
        for (int i = 0; i < activities.size(); i++) {
            if (activities.get(i).getId().equals(activityId)) {
                PlannedActivity confirmed = activities.get(i).withStatus(PlannedActivity.STATUS_CONFIRMED);
                activities.set(i, confirmed);
                MainThread.post(() -> callback.onSuccess(confirmed));
                return;
            }
        }
        MainThread.post(() -> callback.onError(new IllegalStateException("Activity not found")));
    }
}
