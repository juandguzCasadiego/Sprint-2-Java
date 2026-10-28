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
import com.example.parchapp.data.ScheduleRepository;
import com.example.parchapp.data.TravelTimeService;
import com.example.parchapp.domain.PlanContext;
import com.example.parchapp.domain.availability.AvailabilityEngine;
import com.example.parchapp.domain.model.DaySchedule;
import com.example.parchapp.domain.model.PlannedActivity;
import com.example.parchapp.domain.model.Group;
import com.example.parchapp.domain.model.Member;
import com.example.parchapp.domain.model.TimeSlot;
import com.example.parchapp.util.Callback;
import com.example.parchapp.util.ExternalActions;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class GroupDetailActivity extends AppCompatActivity implements GroupListener {

    public static final String EXTRA_GROUP_ID = "extra_group_id";
    private static final String SCREEN = "group_detail";
    private static final int LOCATION_REQUEST = 10;

    private final AvailabilityEngine engine = new AvailabilityEngine();
    private final AnalyticsTracker tracker = AnalyticsTracker.getInstance();
    private TravelTimeService travelTime;
    /* This group's activities from Firestore: the next one fills the plan card, past ones the history. */
    private final List<PlannedActivity> groupActivities = new ArrayList<>();
    private PlannedActivity nextPlan;
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
        travelTime = new TravelTimeService(this);

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
            if (nextPlan == null) {
                toast("This group has no upcoming plan");
                return;
            }
            PlanDetailsSheet.newInstance(nextPlan.getTitle(), nextPlan.getStatus(), nextPlan.getDate(),
                    nextPlan.getTime(), nextPlan.getLocation()).show(getSupportFragmentManager(), "plan_details");
        });
        findViewById(R.id.add_calendar_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            if (nextPlan == null) {
                toast("This group has no upcoming plan");
            } else if (!ExternalActions.openCalendar(this, nextPlan.getTitle(), nextPlan.getDate(),
                    nextPlan.getTime(), nextPlan.getLocation())) {
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

    // f3: ask for the location permission once; the card works without it and says so
    private void loadPlanContext() {
        if (!travelTime.hasLocationPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST) {
            showTravel();
        }
    }

    /* This group's activities, from Firestore through ActivityRepository. */
    private void loadGroupActivities() {
        Repositories.activities().getActivities(UserSession.getCurrentUser().getId(),
                new Callback<List<PlannedActivity>>() {
                    @Override
                    public void onSuccess(List<PlannedActivity> activities) {
                        groupActivities.clear();
                        for (PlannedActivity activity : activities) {
                            if (activity.getGroupId().equals(groupId)) {
                                groupActivities.add(activity);
                            }
                        }
                        bindPlans();
                    }

                    @Override
                    public void onError(Exception error) {
                        bindPlans();
                    }
                });
    }

    private void bindPlans() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Calendar now = Calendar.getInstance(PlanContext.bogota());
        int nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        nextPlan = PlanContext.nextPlan(groupActivities, now, nowMinute);
        ((TextView) findViewById(R.id.next_plan_header)).setText(nextPlan == null ? "No upcoming group plan" : "Next Group Plan");
        ((TextView) findViewById(R.id.plan_title_text)).setText(nextPlan == null
                ? "Propose a plan to get the group together" : nextPlan.getTitle());
        ((TextView) findViewById(R.id.next_plan_time)).setText(nextPlan == null ? ""
                : (nextPlan.getDate() + " " + nextPlan.getTime()).trim());
        ((TextView) findViewById(R.id.next_plan_location)).setText(nextPlan == null ? ""
                : nextPlan.getLocation().isEmpty() ? "No place set yet" : nextPlan.getLocation());
        showTravel();
        bindRecentActivities(now);
    }

    // f1, f3 and f7: campus status, travel time and late warning for the group's next plan
    private void showTravel() {
        if (nextPlan == null) {
            campusText.setText("");
            travelText.setText("");
            lateWarning.setVisibility(View.GONE);
            return;
        }
        PlannedActivity plan = nextPlan;
        travelText.setText("\u279C  Finding your travel time\u2026");
        travelTime.travelTo(plan, new TravelTimeService.Listener() {
            @Override
            public void onTravel(TravelTimeService.Result result) {
                if (isFinishing() || isDestroyed() || plan != nextPlan) {
                    return;
                }
                campusText.setText(result.userOnCampus ? "\u25CE  You are on campus" : "\u25CE  You are off campus");
                travelText.setText(TravelTimeService.describe(result));
                lateWarning.setVisibility(result.late ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onProblem(TravelTimeService.Problem problem) {
                if (isFinishing() || isDestroyed() || plan != nextPlan) {
                    return;
                }
                campusText.setText(problem == TravelTimeService.Problem.NO_PERMISSION
                        ? "\u25CE  Location permission denied" : "");
                travelText.setText(TravelTimeService.describe(problem));
                lateWarning.setVisibility(View.GONE);
            }
        });
    }

    /* Past activities of the group, newest first, from Firestore. */
    private void bindRecentActivities(Calendar now) {
        LinearLayout list = findViewById(R.id.recent_activities_list);
        list.removeAllViews();
        List<PlannedActivity> past = new ArrayList<>();
        for (PlannedActivity activity : groupActivities) {
            Integer offset = PlanContext.dayOffset(activity.getDate(), now);
            if (offset != null && offset < 0) {
                past.add(activity);
            }
        }
        past.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        for (int i = 0; i < past.size() && i < 3; i++) {
            PlannedActivity activity = past.get(i);
            TextView row = new TextView(this);
            row.setText(activity.getTitle() + "\n" + activity.getDate()
                    + (activity.getLocation().isEmpty() ? "" : " \u2022 " + activity.getLocation())
                    + " \u2022 " + (PlannedActivity.STATUS_CONFIRMED.equals(activity.getStatus()) ? "Confirmed" : "Proposed"));
            row.setTextSize(11);
            row.setTextColor(getColor(R.color.brand_dark));
            row.setPadding(0, 16, 0, 0);
            list.addView(row);
        }
        findViewById(R.id.recent_activities_empty).setVisibility(past.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (groupId != null) {
            Repositories.groups().observeGroup(groupId, this);
            loadGroupActivities();
        } else {
            Repositories.groups().getGroups(UserSession.getCurrentUser().getId(), new Callback<List<Group>>() {
                @Override
                public void onSuccess(List<Group> groups) {
                    if (!groups.isEmpty()) {
                        groupId = groups.get(0).getId();
                        Repositories.groups().observeGroup(groupId, GroupDetailActivity.this);
                        loadGroupActivities();
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
        ((TextView) findViewById(R.id.next_plan_status)).setText(
                group.getGoingCount() + "/" + group.getRsvpTotal() + " responses");
        renderRoster(group);
        loadSyncedCount(group);

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
            // the leader is the group's owner in Firestore; seeded groups have none
            row.findViewById(R.id.member_leader_badge).setVisibility(
                    member.getId().equals(group.getOwnerId()) ? View.VISIBLE : View.GONE);
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

    /* "N/M Synced": members whose calendar for today is known to the backend (getScheduleDay). */
    private void loadSyncedCount(Group group) {
        TextView synced = findViewById(R.id.group_synced_badge);
        String me = UserSession.getCurrentUser().getId();
        List<String> others = new ArrayList<>();
        for (Member member : group.getMembers()) {
            if (!member.getId().equals(me)) {
                others.add(member.getId());
            }
        }
        java.text.SimpleDateFormat key = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        key.setTimeZone(PlanContext.bogota());
        String today = key.format(new java.util.Date());
        // The backend compares at most 10 friends per call, so the group is checked in batches.
        List<List<String>> batches = new ArrayList<>();
        for (int start = 0; start < others.size(); start += ScheduleRepository.MAX_FRIENDS) {
            batches.add(others.subList(start, Math.min(others.size(), start + ScheduleRepository.MAX_FRIENDS)));
        }
        if (batches.isEmpty()) {
            batches.add(new ArrayList<>());
        }
        int[] known = {0};
        int[] pending = {batches.size()};
        boolean[] failed = {false};
        for (int b = 0; b < batches.size(); b++) {
            boolean countMe = b == 0;
            Repositories.schedules().getDay(today, batches.get(b), new Callback<List<DaySchedule>>() {
                @Override
                public void onSuccess(List<DaySchedule> schedules) {
                    for (DaySchedule schedule : schedules) {
                        // every call also returns my own schedule; count it once
                        if (schedule.isKnown() && (countMe || !schedule.getOwnerId().equals(me))) {
                            known[0]++;
                        }
                    }
                    done();
                }

                @Override
                public void onError(Exception error) {
                    failed[0] = true;
                    done();
                }

                private void done() {
                    if (--pending[0] == 0 && !isFinishing()) {
                        synced.setText(failed[0] ? "\u25CF Sync unknown"
                                : String.format(Locale.US, "\u25CF %d/%d Synced", known[0], group.getSize()));
                    }
                }
            });
        }
    }

    private int currentMinute() {
        Calendar now = Calendar.getInstance(PlanContext.bogota());
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }

    private void renderCommonFree(List<TimeSlot> slots, int groupSize) {
        TextView commonFree = findViewById(R.id.common_free_text);
        TextView badge = findViewById(R.id.common_free_badge);
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
