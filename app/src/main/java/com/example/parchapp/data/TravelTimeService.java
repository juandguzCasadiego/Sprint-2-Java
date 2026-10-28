package com.example.parchapp.data;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.parchapp.BuildConfig;
import com.example.parchapp.domain.CampusArea;
import com.example.parchapp.domain.PlanContext;
import com.example.parchapp.domain.model.CampusPlace;
import com.example.parchapp.domain.model.PlannedActivity;
import com.example.parchapp.util.Callback;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.Calendar;
import java.util.List;

/* F3 and F7: travel time from the user's location to an activity's place, shared by Dashboard, Group Detail and Activity Created. Every input comes from the backend: the place and its coordinates from campusAreas, the plan time from the activity document. Order: cached minutes (ActivityRepository, F7) -> Google Maps Distance Matrix (8 s timeout) -> walking estimate (timeout and fallback tactic). */
public class TravelTimeService {

    public enum Problem {NO_PERMISSION, NO_LOCATION, NO_PLACE, PLACES_UNAVAILABLE}

    public static final class Result {
        public final int minutes;
        /* "Google Maps", or null for the walking estimate. */
        @Nullable
        public final String source;
        public final CampusPlace place;
        public final boolean late;
        public final boolean userOnCampus;

        Result(int minutes, @Nullable String source, CampusPlace place, boolean late, boolean userOnCampus) {
            this.minutes = minutes;
            this.source = source;
            this.place = place;
            this.late = late;
            this.userOnCampus = userOnCampus;
        }
    }

    public interface Listener {
        void onTravel(Result result);

        void onProblem(Problem problem);
    }

    private final Context context;
    private final DistanceMatrixClient distanceMatrix = new DistanceMatrixClient(BuildConfig.MAPS_API_KEY);

    public TravelTimeService(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void travelTo(PlannedActivity activity, Listener listener) {
        if (!hasLocationPermission()) {
            listener.onProblem(Problem.NO_PERMISSION);
            return;
        }
        Repositories.places().getPlaces(new Callback<List<CampusPlace>>() {
            @Override
            public void onSuccess(List<CampusPlace> places) {
                CampusPlace place = CampusPlaceRepository.placeOf(activity, places);
                if (place == null || !place.hasCoordinates()) {
                    listener.onProblem(Problem.NO_PLACE);
                    return;
                }
                readLocation(location -> {
                    if (location == null) {
                        listener.onProblem(Problem.NO_LOCATION);
                    } else {
                        measure(activity, place, places, location, listener);
                    }
                });
            }

            @Override
            public void onError(Exception error) {
                listener.onProblem(Problem.PLACES_UNAVAILABLE);
            }
        });
    }

    private void measure(PlannedActivity activity, CampusPlace place, List<CampusPlace> places, Location from,
                         Listener listener) {
        boolean onCampus = CampusArea.isOnCampus(from.getLatitude(), from.getLongitude(), places);
        // first the cache (F7), so we dont call google maps again if we already asked a few minutes ago
        Integer cached = activity.getId() == null ? null
                : Repositories.activities().getCachedTravelMinutes(activity.getId());
        if (cached != null) {
            listener.onTravel(result(activity, cached, "Google Maps", place, onCampus));
            return;
        }
        distanceMatrix.getTravelMinutes(from.getLatitude(), from.getLongitude(), place.getLat(), place.getLng(),
                new Callback<Integer>() {
                    @Override
                    public void onSuccess(Integer minutes) {
                        if (activity.getId() != null) {
                            Repositories.activities().cacheTravelMinutes(activity.getId(), minutes);
                        }
                        listener.onTravel(result(activity, minutes, "Google Maps", place, onCampus));
                    }

                    @Override
                    public void onError(Exception error) {
                        // maps failed or took more than 8 seconds -> we dont leave the card empty, we estimate walking time instead (timeout and fallback tactic)
                        int walking = PlanContext.walkingMinutes(from.getLatitude(), from.getLongitude(),
                                place.getLat(), place.getLng());
                        listener.onTravel(result(activity, walking, null, place, onCampus));
                    }
                });
    }

    private static Result result(PlannedActivity activity, int minutes, @Nullable String source, CampusPlace place,
                                 boolean onCampus) {
        Calendar now = Calendar.getInstance(PlanContext.bogota());
        int nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        boolean late = PlanContext.isToday(activity.getDate(), now)
                && PlanContext.isLate(nowMinute, minutes, PlanContext.startMinute(activity.getTime()),
                PlanContext.endMinute(activity.getTime()));
        return new Result(minutes, source, place, late, onCampus);
    }

    private interface LocationCallback {
        void onLocation(@Nullable Location location);
    }

    /* A fresh fix, or the last known position when there is none (indoors, GPS warming up). */
    @SuppressLint("MissingPermission")
    private void readLocation(LocationCallback callback) {
        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(context);
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, new CancellationTokenSource().getToken())
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        callback.onLocation(location);
                    } else {
                        client.getLastLocation()
                                .addOnSuccessListener(callback::onLocation)
                                .addOnFailureListener(e -> callback.onLocation(null));
                    }
                })
                .addOnFailureListener(e -> client.getLastLocation()
                        .addOnSuccessListener(callback::onLocation)
                        .addOnFailureListener(e2 -> callback.onLocation(null)));
    }

    /* The text a plan card shows for a result. */
    public static String describe(Result result) {
        return result.source != null
                ? "➜  " + result.minutes + " min to " + result.place.getName() + " · " + result.source
                : "➜  ≈ " + result.minutes + " min walking to " + result.place.getName()
                + " · estimate, Maps unavailable";
    }

    public static String describe(Problem problem) {
        switch (problem) {
            case NO_PERMISSION:
                return "➜  Allow location to see your travel time";
            case NO_PLACE:
                return "➜  No campus place set for this plan";
            default:
                return "➜  Travel time not available right now";
        }
    }
}
