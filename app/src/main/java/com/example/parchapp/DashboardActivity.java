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
import com.example.parchapp.data.ProfileRepository;
import com.example.parchapp.data.TravelTimeService;
import com.example.parchapp.domain.PlanContext;
import com.example.parchapp.domain.model.DaySchedule;
import com.example.parchapp.domain.model.Member;
import com.example.parchapp.domain.model.TimeSlot;
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
import java.util.Locale;

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
    private final List<PlannedActivity> loadedActivities = new ArrayList<>();
    private TravelTimeService travelTime;
    private String inviteLink;
    private ConnectivityMonitor connectivity;
    private boolean askedLocation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        bindUser(UserSession.getCurrentUser());
        travelTime = new TravelTimeService(this);

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
        // Both values come from the backend (availability and activities); the Schedule screen shows the details.
        findViewById(R.id.btn_edit_free_until).setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
        findViewById(R.id.btn_edit_next_activity).setOnClickListener(v -> startActivity(new Intent(this, ScheduleActivity.class)));
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
        status.refreshFromBackend();
        loadFreeUntil();
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
        // F1: the live status card shows the presence saved in the backend profile, and its source.
        String availability = status.getAvailability();
        ((TextView) findViewById(R.id.status_title)).setText(availability.isEmpty() ? "Checking your status\u2026"
                : "Available now".equalsIgnoreCase(availability) ? getString(R.string.available_now) : availability);
        ((TextView) findViewById(R.id.status_source_chip)).setText(
                status.isFromMotion() ? "\u25CE Motion sensor" : "\u25CF Live Sync");
        String code = StatusService.codeFor(availability);
        ((TextView) findViewById(R.id.status_header_chip)).setText(availability.isEmpty() ? "\u25CF \u2026"
                : ProfileRepository.ACTIVE.equals(code) ? "\u25CF FREE" : "\u25CF " + availability.toUpperCase(Locale.ROOT));
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
        if (!travelTime.hasLocationPermission() && !askedLocation) {
            askedLocation = true;
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
        }
        travel.setText("\u279C  Finding your travel time\u2026");
        travelTime.travelTo(activity, new TravelTimeService.Listener() {
            @Override
            public void onTravel(TravelTimeService.Result result) {
                if (isFinishing() || isDestroyed() || activity != latestActivity) {
                    return;
                }
                travel.setText(TravelTimeService.describe(result));
                findViewById(R.id.plan_late_warning).setVisibility(result.late ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onProblem(TravelTimeService.Problem problem) {
                if (!isFinishing() && !isDestroyed() && activity == latestActivity) {
                    travel.setText(TravelTimeService.describe(problem));
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST && latestActivity != null) {
            updatePlanContext(latestActivity);
        }
    }

    // ---------- Quick status values, from the backend ----------

    /* Free until: the start of my next busy block today, from my availability snapshot. */
    private void loadFreeUntil() {
        Calendar now = Calendar.getInstance(PlanContext.bogota());
        int nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        java.text.SimpleDateFormat key = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        key.setTimeZone(PlanContext.bogota());
        TextView label = findViewById(R.id.free_until_label);
        Repositories.schedules().getDay(key.format(now.getTime()), new ArrayList<>(),
                new Callback<List<DaySchedule>>() {
                    @Override
                    public void onSuccess(List<DaySchedule> schedules) {
                        String me = UserSession.getCurrentUser().getId();
                        DaySchedule mine = null;
                        for (DaySchedule schedule : schedules) {
                            if (schedule.getOwnerId().equals(me)) {
                                mine = schedule;
                            }
                        }
                        if (mine == null && !schedules.isEmpty()) {
                            mine = schedules.get(0);
                        }
                        if (mine == null || !mine.isKnown()) {
                            label.setText("FREE UNTIL");
                            displayFreeUntil.setText("Calendar not synced");
                            return;
                        }
                        int[] window = PlanContext.freeWindow(mine.getBusy(), nowMinute);
                        if (window[0] == PlanContext.BUSY_NOW) {
                            label.setText("BUSY UNTIL");
                            displayFreeUntil.setText(TimeSlot.formatMinute(window[1]));
                        } else {
                            label.setText("FREE UNTIL");
                            displayFreeUntil.setText(window[1] >= 24 * 60 ? "End of day" : TimeSlot.formatMinute(window[1]));
                        }
                    }

                    @Override
                    public void onError(Exception error) {
                        displayFreeUntil.setText("Unavailable");
                    }
                });
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
                        loadedActivities.clear();
                        loadedActivities.addAll(activities);
                        bindActivities();
                    }

                    @Override
                    public void onError(Exception error) {
                        findViewById(R.id.plan_card_study).setVisibility(View.GONE);
                    }
                });
    }

    private void bindActivities() {
        Calendar now = Calendar.getInstance(PlanContext.bogota());
        int nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int today = PlanContext.countToday(loadedActivities, now);
        ((TextView) findViewById(R.id.active_plans_text)).setText(today == 1
                ? "You have 1 active plan today" : "You have " + today + " active plans today");

        PlannedActivity next = PlanContext.nextPlan(loadedActivities, now, nowMinute);
        displayNextActivity.setText(next != null ? next.getTitle() : "No upcoming plans");

        PlannedActivity shown = next != null ? next : loadedActivities.isEmpty() ? null : loadedActivities.get(0);
        if (shown == null) {
            latestActivity = null;
            findViewById(R.id.plan_card_study).setVisibility(View.GONE);
            bindRsvpPlan();
            return;
        }
        latestActivity = shown;
        findViewById(R.id.plan_card_study).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.plan_status_text)).setText(shown.getStatus());
        ((TextView) findViewById(R.id.plan_title_text)).setText(shown.getTitle());
        ((TextView) findViewById(R.id.plan_time_text)).setText(joinNonEmpty(shown.getDate(), shown.getTime()));
        ((TextView) findViewById(R.id.plan_location_text)).setText(
                shown.getLocation().isEmpty() ? "Location not specified" : shown.getLocation());
        bindPlanAvatars(shown);
        bindRsvpPlan();
        updatePlanContext(shown);
    }

    /* The people of the plan's group, from the group document: up to three initials and "+N". */
    private void bindPlanAvatars(PlannedActivity activity) {
        int[] ids = {R.id.plan_avatar_1, R.id.plan_avatar_2, R.id.plan_avatar_3};
        Group group = groupById(activity.getGroupId());
        List<Member> members = group == null ? new ArrayList<>() : group.getMembers();
        for (int i = 0; i < ids.length; i++) {
            TextView avatar = findViewById(ids[i]);
            if (i < members.size()) {
                avatar.setVisibility(View.VISIBLE);
                avatar.setText(members.get(i).getInitials());
            } else {
                avatar.setVisibility(View.GONE);
            }
        }
        TextView more = findViewById(R.id.plan_avatar_more);
        more.setVisibility(members.size() > ids.length ? View.VISIBLE : View.GONE);
        more.setText("+" + (members.size() - ids.length));
    }

    /* The RSVP card shows the first group's latest activity: its real time and place. */
    private void bindRsvpPlan() {
        if (loadedGroups.isEmpty()) {
            return;
        }
        Group group = loadedGroups.get(0);
        PlannedActivity latest = null;
        for (PlannedActivity activity : loadedActivities) {
            if (activity.getGroupId().equals(group.getId())
                    && (latest == null || activity.getCreatedAt() > latest.getCreatedAt())) {
                latest = activity;
            }
        }
        String title = latest != null ? latest.getTitle() : group.getPlanTitle();
        ((TextView) findViewById(R.id.rsvp_plan_title)).setText(title);
        ((TextView) findViewById(R.id.rsvp_plan_time)).setText(latest != null
                ? joinNonEmpty(latest.getDate(), latest.getTime()) : "No time set yet");
        ((TextView) findViewById(R.id.rsvp_plan_location)).setText(latest != null && !latest.getLocation().isEmpty()
                ? latest.getLocation() : "No place set yet");
        findViewById(R.id.plan_card_rsvp).setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private Group groupById(String id) {
        for (Group group : loadedGroups) {
            if (group.getId().equals(id)) {
                return group;
            }
        }
        return null;
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
        bindRsvpPlan();
        if (latestActivity != null) {
            bindPlanAvatars(latestActivity);
        }
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

    /* The invite link is a real share link stored by the backend (shareLinks), created on demand. */
    private void copyInviteLink() {
        tracker.trackFeature(AnalyticsEvents.FEATURE_INVITATIONS, SCREEN);
        if (inviteLink != null) {
            copyToClipboard(inviteLink);
            return;
        }
        ((TextView) findViewById(R.id.invite_link_text)).setText("Creating your link\u2026");
        Repositories.profiles().createShareLink("profile", UserSession.getCurrentUser().getId(), new Callback<String>() {
            @Override
            public void onSuccess(String token) {
                inviteLink = ProfileRepository.linkFor(token);
                ((TextView) findViewById(R.id.invite_link_text)).setText(inviteLink);
                copyToClipboard(inviteLink);
            }

            @Override
            public void onError(Exception error) {
                ((TextView) findViewById(R.id.invite_link_text)).setText("Could not create a link. Try again.");
            }
        });
    }

    private void copyToClipboard(String link) {
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
        ((TextView) findViewById(R.id.active_plans_text)).setText("Loading your plans\u2026");
        ((TextView) findViewById(R.id.invite_link_text)).setText("Tap Copy Link to create your invite link");
    }
}
