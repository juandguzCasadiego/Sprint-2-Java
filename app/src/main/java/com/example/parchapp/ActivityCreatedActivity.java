package com.example.parchapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.SwitchCompat;

import com.example.parchapp.analytics.AnalyticsEvents;
import com.example.parchapp.analytics.AnalyticsTracker;
import com.example.parchapp.data.ProfileRepository;
import com.example.parchapp.data.Repositories;
import com.example.parchapp.data.TravelTimeService;
import com.example.parchapp.domain.PlanContext;
import com.example.parchapp.domain.model.Group;
import com.example.parchapp.domain.model.Member;
import com.example.parchapp.domain.model.PlannedActivity;
import com.example.parchapp.util.Callback;
import com.example.parchapp.util.ExternalActions;

public class ActivityCreatedActivity extends AppCompatActivity {

    public static final String EXTRA_ACTIVITY_ID = "extra_activity_id";
    public static final String EXTRA_TITLE = "extra_activity_title";
    public static final String EXTRA_DATE = "extra_activity_date";
    public static final String EXTRA_TIME = "extra_activity_time";
    public static final String EXTRA_LOCATION = "extra_activity_location";

    private static final String[] REMINDER_OPTIONS = {"15 min before", "30 min before", "1 hour before", "None"};
    private static final String SCREEN = "activity_created";
    private static final String REMINDER_PREFS = "parchapp_reminders";
    private final AnalyticsTracker tracker = AnalyticsTracker.getInstance();
    private int reminderIndex = 0;
    private PlannedActivity activity;
    private String inviteLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_created);

        // The recap shows the activity as stored in Firestore; the extras only fill it while it loads.
        bindSummary(getIntent().getStringExtra(EXTRA_TITLE), getIntent().getStringExtra(EXTRA_DATE),
                getIntent().getStringExtra(EXTRA_TIME), getIntent().getStringExtra(EXTRA_LOCATION));
        findViewById(R.id.attendees_card).setVisibility(android.view.View.GONE);
        String activityId = getIntent().getStringExtra(EXTRA_ACTIVITY_ID);
        if (activityId != null) {
            loadActivity(activityId);
        }

        findViewById(R.id.close_created_button).setOnClickListener(v -> finish());
        findViewById(R.id.copy_invite_button).setOnClickListener(v -> copyInviteLink());
        findViewById(R.id.whatsapp_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_INVITATIONS, SCREEN);
            withInviteLink(link -> {
                Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Join my plan on ParchApp: " + link);
                share.setPackage("com.whatsapp");
                if (share.resolveActivity(getPackageManager()) == null) {
                    share.setPackage(null);
                }
                startActivity(Intent.createChooser(share, "Share invite"));
            });
        });
        findViewById(R.id.confirm_plan_button).setOnClickListener(v -> confirmPlan());

        SwitchCompat calendarToggle = findViewById(R.id.calendar_toggle);
        calendarToggle.setOnCheckedChangeListener((CompoundButton button, boolean isChecked) -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            if (!isChecked) {
                toast("Calendar action paused");
                return;
            }
            boolean opened = activity != null && ExternalActions.openCalendar(this, activity.getTitle(),
                    activity.getDate(), activity.getTime(), activity.getLocation());
            if (!opened) {
                button.setChecked(false);
                toast("No calendar application is available");
            } else {
                findViewById(R.id.calendar_added_badge).setVisibility(android.view.View.VISIBLE);
            }
        });

        AppCompatButton reminderButton = findViewById(R.id.reminder_button);
        reminderButton.setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_ALERTS, SCREEN);
            reminderIndex = (reminderIndex + 1) % REMINDER_OPTIONS.length;
            String option = REMINDER_OPTIONS[reminderIndex];
            reminderButton.setText(option + " \u2304");
            if (activity != null && activity.getId() != null) {
                // The reminder is this user's own choice for this activity, kept on the device.
                getSharedPreferences(REMINDER_PREFS, MODE_PRIVATE).edit()
                        .putInt(activity.getId(), reminderIndex).apply();
            }
            toast("Reminder set to: " + option);
        });

        findViewById(R.id.view_schedule_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            startActivity(new Intent(this, ScheduleActivity.class));
        });
        // Back to the Dashboard, closing the screens opened on top of it (finishAffinity closed the app).
        findViewById(R.id.back_home_button).setOnClickListener(v -> {
            startActivity(new Intent(this, DashboardActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
    }

    private void loadActivity(String activityId) {
        Repositories.activities().getActivity(activityId, new Callback<PlannedActivity>() {
            @Override
            public void onSuccess(PlannedActivity loaded) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                activity = loaded;
                bindSummary(loaded.getTitle(), loaded.getDate(), loaded.getTime(), loaded.getLocation());
                TextView category = findViewById(R.id.created_category_chip);
                category.setText(categoryLabel(loaded.getCategory()));
                category.setVisibility(android.view.View.VISIBLE);
                bindStatus();
                bindTravel();
                loadAttendees();
                reminderIndex = getSharedPreferences(REMINDER_PREFS, MODE_PRIVATE).getInt(loaded.getId(), 0);
                ((AppCompatButton) findViewById(R.id.reminder_button)).setText(
                        REMINDER_OPTIONS[reminderIndex] + " \u2304");
            }

            @Override
            public void onError(Exception error) {
                toast("Could not load the activity");
            }
        });
    }

    private void bindSummary(String title, String date, String time, String location) {
        if (title == null || title.trim().isEmpty()) {
            findViewById(R.id.activity_summary_card).setVisibility(android.view.View.GONE);
            return;
        }
        findViewById(R.id.activity_summary_card).setVisibility(android.view.View.VISIBLE);
        ((TextView) findViewById(R.id.created_activity_title)).setText(title);
        ((TextView) findViewById(R.id.created_activity_time)).setText(joinNonEmpty(date, time));
        int start = PlanContext.startMinute(time);
        int end = PlanContext.endMinute(time);
        ((TextView) findViewById(R.id.created_activity_duration)).setText(start >= 0 && end > start
                ? formatDuration(end - start) : "No end time");
        ((TextView) findViewById(R.id.created_activity_location)).setText(
                location == null || location.trim().isEmpty() ? "No place set" : location);
        ((TextView) findViewById(R.id.created_activity_location_detail)).setText("");
    }

    private void bindStatus() {
        boolean organiser = activity.getCreatedBy().equals(UserSession.getCurrentUser().getId());
        boolean confirmed = PlannedActivity.STATUS_CONFIRMED.equals(activity.getStatus());
        findViewById(R.id.confirm_plan_button).setVisibility(organiser && !confirmed
                ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /* F3: how far the plan's place is, from campusAreas and the user's location. */
    private void bindTravel() {
        TextView detail = findViewById(R.id.created_activity_location_detail);
        new TravelTimeService(this).travelTo(activity, new TravelTimeService.Listener() {
            @Override
            public void onTravel(TravelTimeService.Result result) {
                if (!isFinishing() && !isDestroyed()) {
                    detail.setText(result.place.getName());
                    TextView walk = findViewById(R.id.created_walk_chip);
                    walk.setText(result.source != null ? result.minutes + " min \u00B7 " + result.source
                            : "\u2248 " + result.minutes + " min walk");
                    walk.setVisibility(android.view.View.VISIBLE);
                }
            }

            @Override
            public void onProblem(TravelTimeService.Problem problem) {
                if (!isFinishing() && !isDestroyed()) {
                    detail.setText("");
                }
            }
        });
    }

    /* The group's members, from the group document: who organises it and who has answered. */
    private void loadAttendees() {
        Repositories.groups().getGroup(activity.getGroupId(), new Callback<Group>() {
            @Override
            public void onSuccess(Group group) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                android.widget.LinearLayout list = findViewById(R.id.attendees_list);
                list.removeAllViews();
                int answered = 0;
                for (Member member : group.getMembers()) {
                    boolean organiser = member.getId().equals(activity.getCreatedBy());
                    boolean going = group.getGoingIds().contains(member.getId());
                    boolean maybe = group.getMaybeIds().contains(member.getId());
                    if (organiser || going || maybe) {
                        answered++;
                    }
                    boolean me = member.getId().equals(UserSession.getCurrentUser().getId());
                    TextView row = new TextView(ActivityCreatedActivity.this);
                    row.setText(member.getName() + (me ? " (You)" : "") + "\n"
                            + (organiser ? "Organizer" : going ? "Going" : maybe ? "Maybe" : "Invited"));
                    row.setTextSize(12);
                    row.setTextColor(getColor(R.color.brand_dark));
                    row.setPadding(0, 18, 0, 0);
                    list.addView(row);
                }
                TextView groupChip = findViewById(R.id.created_group_chip);
                groupChip.setText(group.getName());
                groupChip.setVisibility(android.view.View.VISIBLE);
                ((TextView) findViewById(R.id.attendees_title)).setText(
                        "INVITED FRIENDS (" + group.getMembers().size() + ")");
                ((TextView) findViewById(R.id.attendees_summary)).setText(
                        answered + " answered \u2022 " + (group.getMembers().size() - answered) + " pending");
                findViewById(R.id.attendees_card).setVisibility(android.view.View.VISIBLE);
            }

            @Override
            public void onError(Exception error) {
                findViewById(R.id.attendees_card).setVisibility(android.view.View.GONE);
            }
        });
    }

    /* F6: the organiser confirms the plan; Firestore stores status CONFIRMED. */
    private void confirmPlan() {
        if (activity == null) {
            return;
        }
        findViewById(R.id.confirm_plan_button).setEnabled(false);
        Repositories.activities().confirmActivity(activity.getId(), new Callback<PlannedActivity>() {
            @Override
            public void onSuccess(PlannedActivity confirmed) {
                activity = confirmed;
                bindStatus();
                toast("Plan confirmed");
            }

            @Override
            public void onError(Exception error) {
                findViewById(R.id.confirm_plan_button).setEnabled(true);
                toast("Could not confirm the plan");
            }
        });
    }

    private interface LinkReady {
        void onLink(String link);
    }

    /* A real share link for this activity, created once by the backend (shareLinks). */
    private void withInviteLink(LinkReady ready) {
        if (inviteLink != null) {
            ready.onLink(inviteLink);
            return;
        }
        if (activity == null || activity.getId() == null) {
            toast("The activity is still loading");
            return;
        }
        Repositories.profiles().createShareLink("activity", activity.getId(), new Callback<String>() {
            @Override
            public void onSuccess(String token) {
                inviteLink = ProfileRepository.linkFor(token);
                ready.onLink(inviteLink);
            }

            @Override
            public void onError(Exception error) {
                toast("Could not create the invite link");
            }
        });
    }

    private void copyInviteLink() {
        tracker.trackFeature(AnalyticsEvents.FEATURE_INVITATIONS, SCREEN);
        withInviteLink(link -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Invite link", link));
            toast("Invite link copied to clipboard");
        });
    }

    private static String categoryLabel(String category) {
        switch (category == null ? "" : category) {
            case "study":
                return "\uD83D\uDCDA Study";
            case "sports":
                return "\u26BD Sports";
            case "social":
                return "\uD83C\uDF89 Social";
            default:
                return "\uD83D\uDCCC Activity";
        }
    }

    private static String formatDuration(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        return hours == 0 ? rest + " min" : rest == 0 ? hours + " h" : hours + " h " + rest + " min";
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String joinNonEmpty(String first, String second) {
        String value = ((first == null ? "" : first) + " " + (second == null ? "" : second)).trim();
        return value.isEmpty() ? "Schedule not specified" : value;
    }
}
