package com.example.parchapp.data;

import android.util.Log;

import com.example.parchapp.domain.model.PlannedActivity;
import com.example.parchapp.util.Callback;
import com.example.parchapp.util.MainThread;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.Timestamp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class FirestoreActivityRepository implements ActivityRepository {
    private final TravelTimeCache travelCache = new TravelTimeCache();
    private static final String TAG = "FirestoreActivityRepo";

    private final FirebaseFirestore db;

    FirestoreActivityRepository(FirebaseFirestore db) {
        this.db = db;
    }

    @Override
    public void createActivity(PlannedActivity activity, Callback<PlannedActivity> callback) {
        DocumentReference ref = db.collection("activities").document();
        Map<String, Object> map = new HashMap<>();
        map.put("groupId", activity.getGroupId());
        map.put("title", activity.getTitle());
        map.put("category", activity.getCategory());
        map.put("date", activity.getDate());
        map.put("time", activity.getTime());
        map.put("location", activity.getLocation());
        map.put("status", activity.getStatus());
        map.put("createdBy", FirebaseAuth.getInstance().getUid());
        map.put("createdAt", FieldValue.serverTimestamp());
        // the campus place picked in New Activity travels as locationAreaId, so travel time and BQ11 know where it is
        Map<String, Object> planningContext = new HashMap<>();
        planningContext.put("groupSizeAtCreation", activity.getGroupSizeAtCreation());
        planningContext.put("comparisonUsed", activity.isComparisonUsed());
        planningContext.put("locationAreaId", activity.getLocationAreaId());
        planningContext.put("timeBucket", "unknown");
        map.put("planningContext", planningContext);

        // Firestore acknowledges an offline write locally and queues it for the server. Surface validation or permission failures instead of reporting success unconditionally.
        ref.set(map)
                .addOnSuccessListener(unused -> {
                    PlannedActivity saved = activity.withId(ref.getId());
                    MainThread.post(() -> callback.onSuccess(saved));
                })
                .addOnFailureListener(error -> {
                    Log.w(TAG, "Activity " + ref.getId() + " was rejected", error);
                    MainThread.post(() -> callback.onError(error));
                });
    }

    @Override
    public void getActivities(String userId, Callback<List<PlannedActivity>> callback) {
        db.collection("groups").whereArrayContains("memberIds", userId).get()
                .addOnSuccessListener(groups -> {
                    List<Task<QuerySnapshot>> queries = new ArrayList<>();
                    for (DocumentSnapshot group : groups.getDocuments()) {
                        queries.add(db.collection("activities")
                                .whereEqualTo("groupId", group.getId())
                                .orderBy("createdAt")
                                .get());
                    }
                    if (queries.isEmpty()) {
                        MainThread.post(() -> callback.onSuccess(Collections.emptyList()));
                        return;
                    }
                    Tasks.whenAllSuccess(queries).addOnSuccessListener(results -> {
                        List<PlannedActivity> activities = new ArrayList<>();
                        for (Object result : results) {
                            for (DocumentSnapshot activity : ((QuerySnapshot) result).getDocuments()) {
                                activities.add(fromSnapshot(activity));
                            }
                        }
                        activities.sort((left, right) -> Long.compare(right.getCreatedAt(), left.getCreatedAt()));
                        MainThread.post(() -> callback.onSuccess(activities));
                    }).addOnFailureListener(error -> MainThread.post(() -> callback.onError(error)));
                })
                .addOnFailureListener(error -> MainThread.post(() -> callback.onError(error)));
    }

    private static PlannedActivity fromSnapshot(DocumentSnapshot snapshot) {
        // read it as a plain object: asking firestore for Map.class crashes because Map has generic types
        Object rawContext = snapshot.get("planningContext");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = rawContext instanceof Map ? (Map<String, Object>) rawContext : null;
        int groupSize = context != null && context.get("groupSizeAtCreation") instanceof Number
                ? ((Number) context.get("groupSizeAtCreation")).intValue() : 0;
        boolean comparisonUsed = context != null && Boolean.TRUE.equals(context.get("comparisonUsed"));
        String areaId = context != null && context.get("locationAreaId") != null
                ? context.get("locationAreaId").toString() : PlannedActivity.UNKNOWN_AREA;
        return new PlannedActivity(snapshot.getId(),
                stringValue(snapshot.get("groupId")), stringValue(snapshot.get("title")),
                stringValue(snapshot.get("category")), stringValue(snapshot.get("date")),
                stringValue(snapshot.get("time")), stringValue(snapshot.get("location")),
                stringValue(snapshot.get("status")), timestampMillis(snapshot.get("createdAt")),
                groupSize, comparisonUsed, areaId, stringValue(snapshot.get("createdBy")));
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static long timestampMillis(Object value) {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toDate().getTime();
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
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
        db.collection("activities").document(activityId).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        callback.onSuccess(fromSnapshot(snapshot));
                    } else {
                        callback.onError(new IllegalStateException("Activity " + activityId + " not found"));
                    }
                })
                .addOnFailureListener(callback::onError);
    }

    // after updating we read the activity again so the screen shows what firestore really has
    @Override
    public void confirmActivity(String activityId, Callback<PlannedActivity> callback) {
        // the rules let only the organiser change status, and only to PROPOSED or CONFIRMED
        db.collection("activities").document(activityId)
                .update("status", PlannedActivity.STATUS_CONFIRMED)
                .addOnSuccessListener(unused -> getActivity(activityId, callback))
                .addOnFailureListener(callback::onError);
    }
}
