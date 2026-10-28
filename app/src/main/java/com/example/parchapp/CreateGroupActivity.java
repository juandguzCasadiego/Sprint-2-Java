package com.example.parchapp;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.parchapp.analytics.ActivityCreationTelemetry;
import com.example.parchapp.analytics.AnalyticsEvents;
import com.example.parchapp.analytics.AnalyticsTracker;
import com.example.parchapp.data.Repositories;
import com.example.parchapp.domain.model.Group;
import com.example.parchapp.domain.model.Member;
import com.example.parchapp.domain.model.PlannedActivity;
import com.example.parchapp.util.Callback;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class CreateGroupActivity extends AppCompatActivity {
    private static final String SCREEN = "create_group";
    private static final int QR_SIZE = 400;
    // the creator likes plans between 6 pm and 9 pm, like the demo users
    private static final int DEFAULT_PREFERRED_START = 18 * 60;
    private static final int DEFAULT_PREFERRED_END = 21 * 60;

    private final AnalyticsTracker tracker = AnalyticsTracker.getInstance();
    // the invitations stay here until the user taps create group
    private final List<String> invites = new ArrayList<>();
    private EditText nameInput;
    private EditText dateInput;
    private EditText timeInput;
    private EditText emailInput;
    private TextView groupLinkText;
    private LinearLayout pendingList;
    private TextView pendingCount;
    private String category;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_group);

        nameInput = findViewById(R.id.group_name_input);
        dateInput = findViewById(R.id.activity_date_input);
        timeInput = findViewById(R.id.activity_time_input);
        emailInput = findViewById(R.id.email_input_group);
        groupLinkText = findViewById(R.id.group_link_text);
        pendingList = findViewById(R.id.pending_list);
        pendingCount = findViewById(R.id.pending_count_text);

        findViewById(R.id.header_search_btn).setOnClickListener(v -> toast("Search coming soon"));
        findViewById(R.id.header_notif_btn).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_ALERTS, SCREEN);
            toast("No new notifications");
        });
        findViewById(R.id.header_profile_btn).setOnClickListener(v -> toast("Profile coming soon"));

        findViewById(R.id.back_group_button).setOnClickListener(v -> finish());
        dateInput.setOnClickListener(v -> pickDate());
        timeInput.setOnClickListener(v -> pickTime());
        // A join link only exists once the backend has created the group (shareLinks); the group screen's Share button creates it. Nothing here pretends a link already exists.
        groupLinkText.setText("Your join link is created with the group");
        findViewById(R.id.copy_group_link_button).setOnClickListener(v -> explainLink());
        findViewById(R.id.share_group_link_button).setOnClickListener(v -> explainLink());
        findViewById(R.id.show_qr_button).setOnClickListener(v -> explainLink());
        addCategoryChips();
        findViewById(R.id.add_email_button).setOnClickListener(v -> addEmail());
        findViewById(R.id.create_group_button).setOnClickListener(v -> createGroup());
        findViewById(R.id.cancel_create_group).setOnClickListener(v -> finish());
        findViewById(R.id.nav_home_create).setOnClickListener(v ->
                startActivity(new Intent(this, DashboardActivity.class)));
        findViewById(R.id.nav_schedule_create).setOnClickListener(v -> {
            tracker.trackFeature(AnalyticsEvents.FEATURE_SCHEDULE, SCREEN);
            startActivity(new Intent(this, ScheduleActivity.class));
        });

        renderInvites();
    }

    // opens the android calendar. past days can not be chosen the date is saved as yyyy-MM-dd because ExternalActions reads it like that for the calendar
    private void pickDate() {
        Calendar today = Calendar.getInstance();
        DatePickerDialog dialog = new DatePickerDialog(this, (picker, year, month, day) -> {
            Calendar chosen = Calendar.getInstance();
            chosen.set(year, month, day);
            dateInput.setText(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(chosen.getTime()));
        }, today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        dialog.show();
    }

    // opens the android clock to choose the hour of the plan
    private void pickTime() {
        Calendar now = Calendar.getInstance();
        new TimePickerDialog(this, (picker, hour, minute) -> {
            Calendar chosen = Calendar.getInstance();
            chosen.set(Calendar.HOUR_OF_DAY, hour);
            chosen.set(Calendar.MINUTE, minute);
            timeInput.setText(new SimpleDateFormat("h:mm a", Locale.US).format(chosen.getTime()));
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show();
    }

    private void addEmail() {
        String email = emailInput.getText().toString().trim();
        if (email.isEmpty()) {
            emailInput.setError("Enter an email");
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError("Enter a valid email");
            return;
        }
        if (invites.contains(email)) {
            emailInput.setError("This email is already in the list");
            return;
        }
        tracker.trackFeature(AnalyticsEvents.FEATURE_INVITATIONS, SCREEN);
        invites.add(email);
        emailInput.setText("");
        renderInvites();
    }

    // draws one row per invitation. tapping a row removes it
    private void renderInvites() {
        pendingList.removeAllViews();
        for (String email : invites) {
            TextView row = new TextView(this);
            row.setText(email + "   •   Pending   ×");
            row.setTextSize(11);
            row.setTextColor(getColor(R.color.brand_primary));
            row.setBackgroundResource(R.drawable.bg_light_blue);
            row.setPadding(24, 20, 24, 20);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = 8;
            row.setLayoutParams(params);
            row.setOnClickListener(v -> {
                invites.remove(email);
                renderInvites();
            });
            pendingList.addView(row);
        }
        pendingCount.setText(invites.size() + " added");
    }

    // f6: saves the new group in firestore with the creator as leader and the invitations
    private void createGroup() {
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            nameInput.setError("Enter a name");
            return;
        }
        String date = dateInput.getText().toString().trim();
        String time = timeInput.getText().toString().trim();

        UserProfile user = UserSession.getCurrentUser();
        Member creator = new Member(user.getId(), user.getFirstName(), user.getInitials(), new ArrayList<>(),
                DEFAULT_PREFERRED_START, DEFAULT_PREFERRED_END);
        Group group = new Group(null, name, "♧", name, Collections.singletonList(creator),
                Collections.singletonList(user.getId()), Collections.emptyList(), invites);

        View createButton = findViewById(R.id.create_group_button);
        createButton.setEnabled(false);
        if (category == null) {
            toast("Choose a category for the group");
            return;
        }
        Repositories.groups().createGroup(group, category, new Callback<Group>() {
            @Override
            public void onSuccess(Group saved) {
                tracker.trackFeature(AnalyticsEvents.FEATURE_GROUPS, SCREEN);
                createFirstPlan(saved, date, time);
            }

            @Override
            public void onError(Exception error) {
                createButton.setEnabled(true);
                toast("Could not create the group. Try again.");
            }
        });
    }

    // the date and time of the form become the first plan of the new group
    private void createFirstPlan(Group group, String date, String time) {
        PlannedActivity activity = new PlannedActivity(null, group.getId(), group.getName(), "other", date, time, "",
                PlannedActivity.STATUS_PROPOSED, System.currentTimeMillis());
        String creationSessionId = UUID.randomUUID().toString();
        long creationStartedAt = System.currentTimeMillis();
        ActivityCreationTelemetry.attempt(creationSessionId, "propose_plan", group.getSize());
        Repositories.activities().createActivity(activity, new Callback<PlannedActivity>() {
            @Override
            public void onSuccess(PlannedActivity saved) {
                ActivityCreationTelemetry.result(creationSessionId, "propose_plan",
                        System.currentTimeMillis() - creationStartedAt, true, saved.getId(), null);
                Map<String, Object> params = new HashMap<>();
                params.put(AnalyticsEvents.PARAM_CATEGORY, "other");
                params.put(AnalyticsEvents.PARAM_SOURCE, "propose_plan");
                params.put(AnalyticsEvents.PARAM_CREATION_SESSION_ID, creationSessionId);
                params.put(AnalyticsEvents.PARAM_ACTIVITY_ID, saved.getId());
                tracker.track(AnalyticsEvents.ACTIVITY_CREATED, params);
                toast("Group created");
                startActivity(new Intent(CreateGroupActivity.this, ActivityCreatedActivity.class)
                        .putExtra(ActivityCreatedActivity.EXTRA_ACTIVITY_ID, saved.getId())
                            .putExtra(ActivityCreatedActivity.EXTRA_TITLE, saved.getTitle())
                        .putExtra(ActivityCreatedActivity.EXTRA_DATE, saved.getDate())
                        .putExtra(ActivityCreatedActivity.EXTRA_TIME, saved.getTime())
                        .putExtra(ActivityCreatedActivity.EXTRA_LOCATION, saved.getLocation()));
                // we close the form so the same group is not created two times
                finish();
            }

            @Override
            public void onError(Exception error) {
                ActivityCreationTelemetry.result(creationSessionId, "propose_plan",
                        System.currentTimeMillis() - creationStartedAt, false, null,
                        error.getClass().getSimpleName());
                // the group is already saved, so we open it even if the plan failed
                toast("The group was created, but the plan could not be saved");
                startActivity(new Intent(CreateGroupActivity.this, GroupDetailActivity.class)
                        .putExtra(GroupDetailActivity.EXTRA_GROUP_ID, group.getId()));
                finish();
            }
        });
    }

    private void explainLink() {
        tracker.trackFeature(AnalyticsEvents.FEATURE_INVITATIONS, SCREEN);
        toast("Create the group first. Then share its join link from the group screen.");
    }

    /* The backend needs a category for every group (study, social, sports, living). */
    private void addCategoryChips() {
        ViewGroup parent = (ViewGroup) nameInput.getParent();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 18, 0, 0);
        String[][] options = {{"study", "Study"}, {"social", "Social"}, {"sports", "Sports"}, {"living", "Living"}};
        List<TextView> chips = new ArrayList<>();
        for (String[] option : options) {
            TextView chip = new TextView(this);
            chip.setText(option[1]);
            chip.setTextSize(12);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(30, 16, 30, 16);
            chip.setBackgroundResource(R.drawable.bg_chip_unselected);
            chip.setTextColor(getColor(R.color.brand_dark));
            chip.setOnClickListener(v -> {
                category = option[0];
                for (TextView other : chips) {
                    other.setBackgroundResource(R.drawable.bg_chip_unselected);
                    other.setTextColor(getColor(R.color.brand_dark));
                }
                chip.setBackgroundResource(R.drawable.bg_chip_selected);
                chip.setTextColor(getColor(R.color.brand_primary));
            });
            chips.add(chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMarginEnd(10);
            row.addView(chip, params);
        }
        parent.addView(row, parent.indexOfChild(nameInput) + 1);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
