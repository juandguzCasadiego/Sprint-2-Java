package com.example.parchapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.parchapp.analytics.AnalyticsEvents;
import com.example.parchapp.analytics.AnalyticsTracker;
import com.example.parchapp.data.DistanceMatrixClient;
import com.example.parchapp.data.GroupListener;
import com.example.parchapp.data.Repositories;
import com.example.parchapp.domain.CampusArea;
import com.example.parchapp.domain.availability.AvailabilityEngine;
import com.example.parchapp.domain.model.Group;
import com.example.parchapp.domain.model.Member;
import com.example.parchapp.domain.model.TimeSlot;
import com.example.parchapp.util.Callback;
import com.example.parchapp.util.ExternalActions;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class GroupDetailActivity extends AppCompatActivity implements GroupListener {

    public static final String EXTRA_GROUP_ID = "extra_group_id";
    private static final String SCREEN = "group_detail";
    private static final int LOCATION_REQUEST = 10;

    // demo data of the plan in the card: it starts today at 8:00 pm in main st apartment
    private static final int PLAN_START_MINUTE = 20 * 60;
    private static final double VENUE_LAT = 4.6280;
    private static final double VENUE_LNG = -74.0640;

    private final AvailabilityEngine engine = new AvailabilityEngine();
    private final AnalyticsTracker tracker = AnalyticsTracker.getInstance();
    private final DistanceMatrixClient distanceMatrix = new DistanceMatrixClient(BuildConfig.MAPS_API_KEY);
    private String groupId;
    private Group loadedGroup;
    private TextView campusText;
    private TextView travelText;
    private TextView lateWarning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_detail);

        groupId = getIntent().getStringExtra(EXTRA_GROUP_ID);

        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        findViewById(R.id.share_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_INVITATIONS, SCREEN);
            toast("Share group link");
        });
        findViewById(R.id.more_options_button).setOnClickListener(v -> toast("More options"));

        findViewById(R.id.compare_roommates_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_COMPARE_AVAILABILITY, SCREEN);
            CompareAvailabilitySheet.newInstance(groupId).show(getSupportFragmentManager(), "compare_roommates");
        });
        findViewById(R.id.plan_details_button).setOnClickListener(v -> {
            if (loadedGroup == null || loadedGroup.getPlanTitle().isEmpty()) {
                toast("No activity data is available");
                return;
            }
            PlanDetailsSheet.newInstance(loadedGroup.getPlanTitle(), "", "", "", "")
                    .show(getSupportFragmentManager(), "plan_details");
        });
        findViewById(R.id.add_calendar_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            if (loadedGroup == null || loadedGroup.getPlanTitle().isEmpty()
                    || !ExternalActions.openCalendar(this, loadedGroup.getPlanTitle(), "", "", "")) {
                toast("No calendar application is available");
            }
        });

        findViewById(R.id.refresh_roommates_button).setOnClickListener(v -> refreshGroup());

        findViewById(R.id.propose_plan_button).setOnClickListener(v ->
                startActivity(new Intent(this, CreateGroupActivity.class)));
        findViewById(R.id.sync_schedules_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            refreshGroup();
        });
        findViewById(R.id.house_chat_button).setOnClickListener(v -> toast("Opening house chat"));
        findViewById(R.id.view_all_activities).setOnClickListener(v -> toast("Viewing all house activities"));

        findViewById(R.id.create_plan_button).setOnClickListener(v ->
                new CreateActivitySheet().show(getSupportFragmentManager(), "create_activity"));

        findViewById(R.id.nav_home_group).setOnClickListener(v ->
                startActivity(new Intent(this, DashboardActivity.class)));
        findViewById(R.id.nav_schedule_group).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            startActivity(new Intent(this, ScheduleActivity.class));
        });
        findViewById(R.id.nav_history_group).setOnClickListener(v -> toast("History coming soon"));

        campusText = findViewById(R.id.campus_status_text);
        travelText = findViewById(R.id.travel_time_text);
        lateWarning = findViewById(R.id.late_warning_text);
        loadPlanContext();
    }

    // f3: if we have the location permission we read the location, if not we ask the user for it
    private void loadPlanContext() {
        if (hasLocationPermission()) {
            readLocation();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // here we get the answer of the user after we ask for the permission
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_REQUEST) {
            return;
        }
        if (hasLocationPermission()) {
            readLocation();
        } else {
            campusText.setText("◎  Location permission denied");
            travelText.setText("➜  Allow location to see your travel time");
        }
    }

    // asks the fused location provider for the current location
    @SuppressLint("MissingPermission")
    private void readLocation() {
        LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, new CancellationTokenSource().getToken())
                .addOnSuccessListener(this, location -> {
                    if (location == null) {
                        campusText.setText("◎  Could not get your location");
                        travelText.setText("➜  Travel time not available right now");
                        return;
                    }
                    showCampusStatus(location);
                    showTravelTime(location);
                })
                .addOnFailureListener(this, e -> {
                    campusText.setText("◎  Could not get your location");
                    travelText.setText("➜  Travel time not available right now");
                });
    }

    // f1: shows if the user is inside or outside the campus
    private void showCampusStatus(Location location) {
        boolean onCampus = CampusArea.isOnCampus(location.getLatitude(), location.getLongitude());
        campusText.setText(onCampus ? "◎  You are on campus" : "◎  You are off campus");
    }

    // f3 and f7: asks for the travel time and shows a warning if you cannot arrive before the plan starts
    private void showTravelTime(Location location) {
        distanceMatrix.getTravelMinutes(location.getLatitude(), location.getLongitude(), VENUE_LAT, VENUE_LNG,
                new Callback<Integer>() {
                    @Override
                    public void onSuccess(Integer minutes) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        travelText.setText("➜  " + minutes + " min to the venue");
                        boolean late = currentMinute() + minutes > PLAN_START_MINUTE;
                        lateWarning.setVisibility(late ? View.VISIBLE : View.GONE);
                    }

                    @Override
                    public void onError(Exception error) {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        // fallback: if maps does not answer, the card still works and only shows this message
                        travelText.setText("➜  Travel time not available right now");
                        lateWarning.setVisibility(View.GONE);
                    }
                });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (groupId != null) {
            Repositories.groups().observeGroup(groupId, this);
        } else {
            Repositories.groups().getGroups(UserSession.getCurrentUser().getId(), new Callback<List<Group>>() {
                @Override
                public void onSuccess(List<Group> groups) {
                    if (!groups.isEmpty()) {
                        groupId = groups.get(0).getId();
                        Repositories.groups().observeGroup(groupId, GroupDetailActivity.this);
                    } else {
                        toast("No groups are available");
                    }
                }

                @Override
                public void onError(Exception error) {
                    toast("Could not load your groups");
                }
            });
        }
    }

    @Override
    protected void onStop() {
        if (groupId != null) {
            Repositories.groups().removeGroupListener(groupId, this);
        }
        super.onStop();
    }

    @Override
    public void onGroupChanged(Group group) {
        loadedGroup = group;
        ((TextView) findViewById(R.id.group_name_text)).setText(group.getName());
        ((TextView) findViewById(R.id.group_description_text)).setText(group.getSize() + " members");
        ((TextView) findViewById(R.id.top_group_name_text)).setText(group.getName());
        ((TextView) findViewById(R.id.top_group_subtitle_text)).setText(group.getSize() + " members");
        ((TextView) findViewById(R.id.next_plan_header)).setText(
                group.getPlanTitle().isEmpty() ? "No active group plan" : "Next Group Plan");
        ((TextView) findViewById(R.id.next_plan_status)).setText(
                group.getPlanTitle().isEmpty() ? "No data" : group.getGoingCount() + "/" + group.getRsvpTotal() + " responses");
        ((TextView) findViewById(R.id.next_plan_time)).setText("Schedule not specified");
        ((TextView) findViewById(R.id.next_plan_location)).setText("Location not specified");
        renderRoster(group);
        ((TextView) findViewById(R.id.plan_title_text)).setText(group.getPlanTitle());

        int going = group.getGoingCount();
        int total = Math.max(1, group.getRsvpTotal());
        ((TextView) findViewById(R.id.rsvp_ready_text)).setText(String.format(Locale.US,
                "%d%% Ready (%d of %d)", going * 100 / total, going, group.getRsvpTotal()));
        View fill = findViewById(R.id.rsvp_progress_fill);
        View container = (View) fill.getParent();
        container.post(() -> {
            ViewGroup.LayoutParams params = fill.getLayoutParams();
            int available = container.getWidth() - container.getPaddingStart() - container.getPaddingEnd();
            params.width = available * going / total;
            fill.setLayoutParams(params);
        });

        engine.findCommonSlotsAsync(group.getMembers(), SCREEN, new Callback<List<TimeSlot>>() {
            @Override
            public void onSuccess(List<TimeSlot> slots) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                renderCommonFree(slots, group.getSize());
            }

            @Override
            public void onError(Exception error) {
            }
        });
    }

    // f6: the roster is made with the members of the group, so it changes when firestore changes
    private void renderRoster(Group group) {
        LinearLayout list = findViewById(R.id.roster_list);
        list.removeAllViews();
        int nowMinute = currentMinute();
        int freeNow = 0;
        for (int i = 0; i < group.getMembers().size(); i++) {
            Member member = group.getMembers().get(i);
            boolean going = group.getGoingIds().contains(member.getId());
            // free now means free for the next 30 minutes
            boolean free = member.isFree(nowMinute, nowMinute + 30);
            if (free) {
                freeNow++;
            }

            View row = getLayoutInflater().inflate(R.layout.item_roster_member, list, false);
            ((TextView) row.findViewById(R.id.member_initials)).setText(member.getInitials());
            ((TextView) row.findViewById(R.id.member_name)).setText(member.getName());
            // the first member is the one who made the group
            row.findViewById(R.id.member_leader_badge).setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            row.findViewById(R.id.member_going_badge).setVisibility(going ? View.VISIBLE : View.GONE);
            ((TextView) row.findViewById(R.id.member_rsvp_text)).setText(going ? "Going" : "No answer yet");
            TextView status = row.findViewById(R.id.member_status_text);
            status.setText(free ? "●  Free now" : "●  Busy now");
            status.setTextColor(getColor(free ? R.color.brand_success_dark : R.color.brand_rose));
            row.findViewById(R.id.member_chat_button).setOnClickListener(v ->
                    toast("Chat is not available for this group"));
            list.addView(row);
        }
        ((TextView) findViewById(R.id.member_summary_text)).setText(String.format(Locale.US,
                "%d/%d Ready • %d free now", group.getGoingCount(), group.getSize(), freeNow));
    }

    private int currentMinute() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }

    private void renderCommonFree(List<TimeSlot> slots, int groupSize) {
        TextView commonFree = findViewById(R.id.common_free_text);
        TextView badge = findViewById(R.id.common_free_badge);
        TextView synced = findViewById(R.id.group_synced_badge);
        synced.setText(String.format(Locale.US, "● %d/%d Synced", groupSize, groupSize));
        if (slots.isEmpty()) {
            commonFree.setText("No common free time today");
            badge.setVisibility(View.GONE);
            return;
        }
        TimeSlot best = slots.get(0);
        commonFree.setText("Common Free: " + best.formatRange());
        badge.setVisibility(View.VISIBLE);
        badge.setText(best.isEveryoneFree()
                ? "All " + groupSize + " free"
                : best.getFreeCount() + " of " + groupSize + " free");
    }

    private void refreshGroup() {
        if (groupId == null) {
            toast("No group is loaded yet");
            return;
        }
        Repositories.groups().getGroup(groupId, new Callback<Group>() {
            @Override
            public void onSuccess(Group group) {
                onGroupChanged(group);
                toast("Roommate statuses refreshed");
            }

            @Override
            public void onError(Exception error) {
                toast("Could not refresh. Showing saved data.");
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
