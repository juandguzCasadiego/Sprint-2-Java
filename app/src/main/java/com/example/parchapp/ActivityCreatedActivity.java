package com.example.parchapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.SwitchCompat;

import com.example.parchapp.analytics.AnalyticsEvents;
import com.example.parchapp.analytics.AnalyticsTracker;
import com.example.parchapp.util.ExternalActions;

public class ActivityCreatedActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "extra_activity_title";
    public static final String EXTRA_DATE = "extra_activity_date";
    public static final String EXTRA_TIME = "extra_activity_time";
    public static final String EXTRA_LOCATION = "extra_activity_location";

    private static final String[] REMINDER_OPTIONS = {"15 min before", "30 min before", "1 hour before", "None"};
    private static final String SCREEN = "activity_created";
    private final AnalyticsTracker tracker = AnalyticsTracker.getInstance();
    private int reminderIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_created);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String date = getIntent().getStringExtra(EXTRA_DATE);
        String time = getIntent().getStringExtra(EXTRA_TIME);
        String location = getIntent().getStringExtra(EXTRA_LOCATION);
        if (title == null || title.trim().isEmpty()) {
            findViewById(R.id.activity_summary_card).setVisibility(android.view.View.GONE);
        } else {
            ((android.widget.TextView) findViewById(R.id.created_activity_title)).setText(title);
            ((android.widget.TextView) findViewById(R.id.created_activity_time)).setText(
                    joinNonEmpty(date, time));
            ((android.widget.TextView) findViewById(R.id.created_activity_duration)).setText(
                    "Duration not specified");
            ((android.widget.TextView) findViewById(R.id.created_activity_location)).setText(
                    location == null || location.trim().isEmpty() ? "Location not specified" : location);
            ((android.widget.TextView) findViewById(R.id.created_activity_location_detail)).setText("");
        }
        // Participant records are not part of the Java activity read contract yet.
        findViewById(R.id.attendees_card).setVisibility(android.view.View.GONE);

        findViewById(R.id.close_created_button).setOnClickListener(v -> finish());
        findViewById(R.id.copy_invite_button).setOnClickListener(v -> copyInviteLink());
        findViewById(R.id.whatsapp_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_INVITATIONS, SCREEN);
            toast("Opening WhatsApp share");
        });

        SwitchCompat calendarToggle = findViewById(R.id.calendar_toggle);
        calendarToggle.setOnCheckedChangeListener((CompoundButton button, boolean isChecked) -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            if (!isChecked) {
                toast("Calendar action paused");
                return;
            }
            boolean opened = ExternalActions.openCalendar(this,
                    getIntent().getStringExtra(EXTRA_TITLE),
                    getIntent().getStringExtra(EXTRA_DATE),
                    getIntent().getStringExtra(EXTRA_TIME),
                    getIntent().getStringExtra(EXTRA_LOCATION));
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
            reminderButton.setText(option + " ⌄");
            toast("Reminder set to: " + option);
        });

        findViewById(R.id.view_schedule_button).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            toast("Opening schedule");
        });
        // Back to the Dashboard, closing the screens opened on top of it (finishAffinity closed the app).
        findViewById(R.id.back_home_button).setOnClickListener(v -> {
            startActivity(new Intent(this, DashboardActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
    }

    private void copyInviteLink() {
        tracker.trackFeature(AnalyticsEvents.FEATURE_INVITATIONS, SCREEN);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Invite link", "parchapp.co/p/algebra-calc-session-24"));
        toast("Invite link copied to clipboard");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String joinNonEmpty(String first, String second) {
        String value = ((first == null ? "" : first) + " " + (second == null ? "" : second)).trim();
        return value.isEmpty() ? "Schedule not specified" : value;
    }
}
