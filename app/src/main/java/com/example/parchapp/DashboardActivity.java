package com.example.parchapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.parchapp.analytics.AnalyticsEvents;
import com.example.parchapp.analytics.AnalyticsTracker;
import com.example.parchapp.analytics.ConnectivityMonitor;
import com.example.parchapp.data.DistanceMatrixClient;
import com.example.parchapp.data.DemoData;
import com.example.parchapp.data.GroupListener;
import com.example.parchapp.data.Repositories;
import com.example.parchapp.domain.PlanContext;
import com.example.parchapp.domain.StatusService;
import com.example.parchapp.domain.model.Group;
import com.example.parchapp.domain.model.PlannedActivity;
import com.example.parchapp.util.Callback;
import com.example.parchapp.util.MainThread;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class DashboardActivity extends AppCompatActivity implements GroupListener, StatusService.Listener {

    private static final String SCREEN = "dashboard";
    private static final int LOCATION_REQUEST = 41;
    private TextView displayFreeUntil;
    private TextView displayNextActivity;
    private TextView soccerVoteBadge;
    private View soccerProgressBar;
    private Button goingButton;
    private Button maybeButton;
    private final AnalyticsTracker tracker = AnalyticsTracker.getInstance();
    private final List<Group> loadedGroups = new ArrayList<>();
    private String rsvpGroupId;
    private PlannedActivity latestActivity;
    private final DistanceMatrixClient distanceMatrix = new DistanceMatrixClient(BuildConfig.MAPS_API_KEY);
    private ConnectivityMonitor connectivity;
    private boolean askedLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        bindUser(UserSession.getCurrentUser());

        displayFreeUntil = findViewById(R.id.display_free_until);
        displayNextActivity = findViewById(R.id.display_next_activity);
        soccerVoteBadge = findViewById(R.id.soccer_vote_badge);
        soccerProgressBar = findViewById(R.id.soccer_progress_bar);
        goingButton = findViewById(R.id.going_button);
        maybeButton = findViewById(R.id.maybe_button);

        findViewById(R.id.header_search_btn).setOnClickListener(v -> toast("Search coming soon"));
        findViewById(R.id.header_notif_btn).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_ALERTS, SCREEN);
            toast("No new notifications");
        });
        findViewById(R.id.header_user_initials).setOnClickListener(this::openAccountMenu);

        findViewById(R.id.copy_link_button).setOnClickListener(v -> copyInviteLink());
        findViewById(R.id.btn_edit_free_until).setOnClickListener(v -> openQuickStatusSheet());
        findViewById(R.id.btn_edit_next_activity).setOnClickListener(v -> openQuickStatusSheet());
        findViewById(R.id.compare_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_COMPARE_AVAILABILITY, SCREEN);
            openCompareAvailability();
        });
        findViewById(R.id.calendar_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            startActivity(new Intent(this, ScheduleActivity.class));
        });
        findViewById(R.id.new_activity_button).setOnClickListener(v ->
                new CreateActivitySheet().show(getSupportFragmentManager(), "create_activity"));

        findViewById(R.id.plan_card_study).setOnClickListener(v -> openPlanDetails());
        findViewById(R.id.plan_details_button).setOnClickListener(v -> openPlanDetails());

        goingButton.setOnClickListener(v -> setRsvp(true));
        maybeButton.setOnClickListener(v -> setRsvp(false));

        findViewById(R.id.group_card_eng).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_GROUPS, SCREEN);
            openGroupByIndex(0);
        });
        findViewById(R.id.roomies_group_card).setOnClickListener(v -> openGroupByIndex(1));
        findViewById(R.id.view_all_groups_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_GROUPS, SCREEN);
            openAllGroupsSheet();
        });

        findViewById(R.id.nav_groups).setOnClickListener(v -> openGroupDetail());
        findViewById(R.id.nav_schedule).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            startActivity(new Intent(this, ScheduleActivity.class));
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        loadGroups();
        loadActivities();
        connectivity = new ConnectivityMonitor(this, new ConnectivityMonitor.Listener() {
            @Override
            public void onOnline() {
                MainThread.post(() -> showOffline(false));
            }

            @Override
            public void onOffline() {
                MainThread.post(() -> showOffline(true));
            }
        });
        showOffline(!connectivity.isOnline());
        StatusService status = Repositories.status();
        status.addListener(this);
        status.startMotionDetection();
        onStatusChanged(status);
    }

    @Override
    protected void onStop() {
        if (rsvpGroupId != null) {
            Repositories.groups().removeGroupListener(rsvpGroupId, this);
        }
        if (connectivity != null) {
            connectivity.stop();
        }
        Repositories.status().stopMotionDetection();
        Repositories.status().removeListener(this);
        super.onStop();
    }

    @Override
    public void onGroupChanged(Group group) {
        String userId = UserSession.getCurrentUser().getId();
        boolean going = group.getGoingIds().contains(userId);
        boolean maybe = group.getMaybeIds().contains(userId);
        String count = group.getGoingCount() + "/" + group.getRsvpTotal();

        if (going) {
            soccerVoteBadge.setText("✓  You're going • " + count);
        } else if (maybe) {
            soccerVoteBadge.setText("◷  Maybe • " + count);
        } else {
            soccerVoteBadge.setText("◷  Voting pending • " + count);
        }
        renderRsvpButtons(going, maybe);

        View track = (View) soccerProgressBar.getParent();
        track.post(() -> {
            ViewGroup.LayoutParams params = soccerProgressBar.getLayoutParams();
            params.width = track.getWidth() * group.getGoingCount() / Math.max(1, group.getRsvpTotal());
            soccerProgressBar.setLayoutParams(params);
        });
    }

    @Override
    public void onStatusChanged(StatusService status) {
        displayFreeUntil.setText(status.getFreeUntil());
        displayNextActivity.setText(status.getNextActivity());
        // F1: the live status card shows the status the user broadcasts, and where it came from.
        String availability = status.getAvailability();
        ((TextView) findViewById(R.id.status_title)).setText(
                "Available now".equalsIgnoreCase(availability) ? getString(R.string.available_now) : availability);
        ((TextView) findViewById(R.id.status_source_chip)).setText(
                status.isFromMotion() ? "\u25CE Motion sensor" : "\u25CF Live Sync");
    }

    /* F1: the accelerometer saw sustained movement. Suggest the status; the user decides. */
    @Override
    public void onCommuteSuggested(StatusService status) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Are you on the move?")
                .setMessage("Your phone detected sustained movement. Set your status to Commuting / In transit "
                        + "so your groups know you are not available right now?")
                .setPositiveButton("Set Commuting", (dialog, which) -> {
                    status.acceptCommuteSuggestion();
                    toast("Status set to Commuting / In transit");
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void openAccountMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Set availability status");
        menu.getMenu().add(0, 2, 1, "Log out");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                new AvailabilityStatusSheet().show(getSupportFragmentManager(), "availability_status");
            } else {
                logOut();
            }
            return true;
        });
        menu.show();
    }

    /* F5: ends the Firebase session and clears the local profile, then returns to Welcome. */
    private void logOut() {
        Repositories.auth().signOut();
        UserSession.setCurrentUser(null);
        startActivity(new Intent(this, WelcomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void showOffline(boolean offline) {
        findViewById(R.id.offline_banner).setVisibility(offline ? View.VISIBLE : View.GONE);
    }

    // ---------- F3 and F7: context-aware plan card ----------

    private void updatePlanContext(PlannedActivity activity) {
        TextView travel = findViewById(R.id.plan_travel_text);
        travel.setVisibility(View.VISIBLE);
        findViewById(R.id.plan_late_warning).setVisibility(View.GONE);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            travel.setText("\u279C  Allow location to see your travel time");
            if (!askedLocation) {
                askedLocation = true;
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            }
            return;
        }
        travel.setText("\u279C  Finding your travel time\u2026");
        readLocationFor(activity);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST && latestActivity != null) {
            updatePlanContext(latestActivity);
        }
    }

    @SuppressLint("MissingPermission")
    private void readLocationFor(PlannedActivity activity) {
        com.google.android.gms.location.FusedLocationProviderClient client =
                LocationServices.getFusedLocationProviderClient(this);
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, new CancellationTokenSource().getToken())
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        findTravelMinutes(activity, location);
                    } else {
                        useLastKnownLocation(client, activity);
                    }
                })
                .addOnFailureListener(this, e -> useLastKnownLocation(client, activity));
    }

    /* No fresh fix (indoors, or GPS still warming up): the last known position is good enough. */
    @SuppressLint("MissingPermission")
    private void useLastKnownLocation(com.google.android.gms.location.FusedLocationProviderClient client,
                                      PlannedActivity activity) {
        client.getLastLocation()
                .addOnSuccessListener(this, last -> {
                    if (last != null) {
                        findTravelMinutes(activity, last);
                    } else {
                        showTravel(activity, -1, null);
                    }
                })
                .addOnFailureListener(this, e -> showTravel(activity, -1, null));
    }

    private void findTravelMinutes(PlannedActivity activity, Location from) {
        double[] venue = PlanContext.venueCoordinates(activity.getLocation());
        Integer cached = activity.getId() == null ? null
                : Repositories.activities().getCachedTravelMinutes(activity.getId());
        if (cached != null) {
            showTravel(activity, cached, "Google Maps");
            return;
        }
        distanceMatrix.getTravelMinutes(from.getLatitude(), from.getLongitude(), venue[0], venue[1],
                new Callback<Integer>() {
                    @Override
                    public void onSuccess(Integer minutes) {
                        if (activity.getId() != null) {
                            Repositories.activities().cacheTravelMinutes(activity.getId(), minutes);
                        }
                        showTravel(activity, minutes, "Google Maps");
                    }

                    @Override
                    public void onError(Exception error) {
                        // Timeout and fallback: Maps did not answer in 8 s, so estimate the walking time.
                        showTravel(activity, PlanContext.walkingMinutes(from.getLatitude(), from.getLongitude(),
                                venue[0], venue[1]), null);
                    }
                });
    }

    /* source is "Google Maps", or null for the walking estimate; minutes < 0 means unknown. */
    private void showTravel(PlannedActivity activity, int minutes, String source) {
        if (isFinishing() || isDestroyed() || activity != latestActivity) {
            return;
        }
        TextView travel = findViewById(R.id.plan_travel_text);
        if (minutes < 0) {
            travel.setText("\u279C  Travel time not available right now");
            return;
        }
        String place = PlanContext.isKnownVenue(activity.getLocation()) ? activity.getLocation() : "campus";
        travel.setText(source != null
                ? "\u279C  " + minutes + " min to " + place + " \u00B7 " + source
                : "\u279C  \u2248 " + minutes + " min walking to " + place + " \u00B7 estimate, Maps unavailable");

        Calendar now = Calendar.getInstance(PlanContext.bogota());
        int nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        boolean late = PlanContext.isToday(activity.getDate(), now)
                && PlanContext.isLate(nowMinute, minutes, PlanContext.startMinute(activity.getTime()),
                PlanContext.endMinute(activity.getTime()));
        findViewById(R.id.plan_late_warning).setVisibility(late ? View.VISIBLE : View.GONE);
    }

    private void openQuickStatusSheet() {
        QuickStatusSheet.newInstance((freeUntil, nextActivity) -> {
            Repositories.status().updateQuickStatus(freeUntil, nextActivity);
            toast("Status updated");
        }).show(getSupportFragmentManager(), "quick_status");
    }

    private void openPlanDetails() {
        if (latestActivity == null) {
            toast("No activity data is available");
            return;
        }
        PlanDetailsSheet.newInstance(latestActivity.getTitle(), latestActivity.getStatus(),
                latestActivity.getDate(), latestActivity.getTime(), latestActivity.getLocation())
                .show(getSupportFragmentManager(), "plan_details");
    }

    private void openGroupDetail() {
        tracker.trackFeature(AnalyticsEvents.FEATURE_GROUPS, SCREEN);
        openGroupByIndex(0);
    }

    private void openAllGroupsSheet() {
        AllGroupsSheet.newInstance(groupKey -> {
            for (Group group : loadedGroups) {
                if (group.getId().equals(groupKey)) {
                    startActivity(new Intent(this, GroupDetailActivity.class)
                            .putExtra(GroupDetailActivity.EXTRA_GROUP_ID, group.getId()));
                    return;
                }
            }
            toast("Group is no longer available");
        }, loadedGroups).show(getSupportFragmentManager(), "all_groups");
    }

    private void loadGroups() {
        Repositories.groups().getGroups(UserSession.getCurrentUser().getId(), new Callback<List<Group>>() {
            @Override
            public void onSuccess(List<Group> groups) {
                loadedGroups.clear();
                loadedGroups.addAll(groups);
                bindGroups();
                if (!loadedGroups.isEmpty()) {
                    rsvpGroupId = loadedGroups.get(0).getId();
                    Repositories.groups().observeGroup(rsvpGroupId, DashboardActivity.this);
                }
            }

            @Override
            public void onError(Exception error) {
                hideGroupCards();
                toast("Could not load your groups");
            }
        });
    }

    private void loadActivities() {
        Repositories.activities().getActivities(UserSession.getCurrentUser().getId(),
                new Callback<List<PlannedActivity>>() {
                    @Override
                    public void onSuccess(List<PlannedActivity> activities) {
                        if (activities.isEmpty()) {
                            findViewById(R.id.plan_card_study).setVisibility(View.GONE);
                            return;
                        }
                        PlannedActivity activity = activities.get(0);
                        latestActivity = activity;
                        findViewById(R.id.plan_card_study).setVisibility(View.VISIBLE);
                        ((TextView) findViewById(R.id.plan_status_text)).setText(activity.getStatus());
                        ((TextView) findViewById(R.id.plan_title_text)).setText(activity.getTitle());
                        ((TextView) findViewById(R.id.plan_time_text)).setText(
                                joinNonEmpty(activity.getDate(), activity.getTime()));
                        ((TextView) findViewById(R.id.plan_location_text)).setText(activity.getLocation());
                        updatePlanContext(activity);
                    }

                    @Override
                    public void onError(Exception error) {
                        findViewById(R.id.plan_card_study).setVisibility(View.GONE);
                    }
                });
    }

    private void bindGroups() {
        bindGroupCard(0, R.id.group_card_eng_title, R.id.group_card_eng_subtitle,
                R.id.group_card_eng_icon, R.id.group_card_eng);
        bindGroupCard(1, R.id.group_card_roomies_title, R.id.group_card_roomies_subtitle,
                R.id.group_card_roomies_icon, R.id.roomies_group_card);
        if (loadedGroups.isEmpty()) {
            findViewById(R.id.plan_card_rsvp).setVisibility(View.GONE);
            return;
        }
        Group group = loadedGroups.get(0);
        ((TextView) findViewById(R.id.rsvp_plan_title)).setText(group.getPlanTitle());
        ((TextView) findViewById(R.id.rsvp_plan_time)).setText(
                group.getPlanTitle().isEmpty() ? "No active plan" : "Schedule not specified");
        ((TextView) findViewById(R.id.rsvp_plan_location)).setText("Location not specified");
        findViewById(R.id.plan_card_rsvp).setVisibility(
                group.getPlanTitle().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void bindGroupCard(int index, int titleId, int subtitleId, int iconId, int cardId) {
        View card = findViewById(cardId);
        if (index >= loadedGroups.size()) {
            card.setVisibility(View.GONE);
            return;
        }
        Group group = loadedGroups.get(index);
        card.setVisibility(View.VISIBLE);
        ((TextView) findViewById(titleId)).setText(group.getName());
        ((TextView) findViewById(subtitleId)).setText(group.getSize() + " members");
        ((TextView) findViewById(iconId)).setText(group.getIcon());
    }

    private void hideGroupCards() {
        findViewById(R.id.group_card_eng).setVisibility(View.GONE);
        findViewById(R.id.roomies_group_card).setVisibility(View.GONE);
        findViewById(R.id.plan_card_rsvp).setVisibility(View.GONE);
    }

    private void openGroupByIndex(int index) {
        if (index >= loadedGroups.size()) {
            toast("No group is available");
            return;
        }
        startActivity(new Intent(this, GroupDetailActivity.class)
                .putExtra(GroupDetailActivity.EXTRA_GROUP_ID, loadedGroups.get(index).getId()));
    }

    private void openCompareAvailability() {
        if (loadedGroups.isEmpty()) {
            toast("No group is available");
            return;
        }
        CompareAvailabilitySheet.newInstance(loadedGroups.get(0).getId())
                .show(getSupportFragmentManager(), "compare_availability");
    }

    private String joinNonEmpty(String first, String second) {
        String value = (first + " " + second).trim();
        return value.isEmpty() ? "Schedule not specified" : value;
    }

    /** The badge and buttons update when the repository notifies the change, even offline. */
    private void setRsvp(boolean going) {
        if (rsvpGroupId == null) {
            toast("No group is available for RSVP");
            return;
        }
        Repositories.groups().setRsvp(rsvpGroupId, UserSession.getCurrentUser().getId(), going);
        toast(going ? "RSVP confirmed: Going" : "RSVP changed to Maybe");
    }

    private void renderRsvpButtons(boolean isGoing, boolean isMaybe) {
        goingButton.setBackgroundResource(isGoing ? R.drawable.bg_progress_fill : R.drawable.bg_green_chip);
        goingButton.setBackgroundTintList(isGoing
                ? getColorStateList(R.color.brand_success)
                : null);
        goingButton.setTextColor(getColor(isGoing ? android.R.color.white : R.color.brand_success_dark));

        maybeButton.setBackgroundResource(isMaybe ? R.drawable.bg_progress_fill : R.drawable.bg_light_blue);
        maybeButton.setBackgroundTintList(isMaybe
                ? getColorStateList(R.color.brand_primary)
                : null);
        maybeButton.setTextColor(getColor(isMaybe ? android.R.color.white : R.color.brand_slate));
    }

    private void copyInviteLink() {
        tracker.trackFeature(AnalyticsEvents.FEATURE_INVITATIONS, SCREEN);
        String link = ((TextView) findViewById(R.id.invite_link_text)).getText().toString();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Invite link", link));
        toast(getString(R.string.link_copied));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void bindUser(UserProfile user) {
        ((TextView) findViewById(R.id.header_user_initials)).setText(user.getInitials());
        ((TextView) findViewById(R.id.greeting_text)).setText("Hello, " + user.getFirstName() + "! 👋");
        ((TextView) findViewById(R.id.active_plans_text)).setText("You have " + user.getActivePlans() + " active plans today");
        ((TextView) findViewById(R.id.invite_link_text)).setText(user.getInviteLink());
    }
}
