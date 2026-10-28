package com.example.parchapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.parchapp.analytics.AnalyticsEvents;
import com.example.parchapp.analytics.AnalyticsTracker;
import com.example.parchapp.analytics.ActivityCreationTelemetry;
import com.example.parchapp.data.CampusPlaceRepository;
import com.example.parchapp.data.Repositories;
import com.example.parchapp.domain.model.CampusPlace;
import com.example.parchapp.domain.model.Group;
import com.example.parchapp.domain.model.PlannedActivity;
import com.example.parchapp.util.Callback;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CreateActivitySheet extends BottomSheetDialogFragment {

    private static final String ARG_GROUP_ID = "arg_group_id";
    private static final String ARG_GROUP_SIZE = "arg_group_size";
    private static final String ARG_SLOT_TIME = "arg_slot_time";
    private static final String ARG_SLOT_POSITION = "arg_slot_position";
    private static final String ARG_RECOMMENDED_POSITION = "arg_recommended_position";
    private static final String ARG_RECOMMENDATION_ID = "arg_recommendation_id";
    private static final String ARG_SLOT_DATE = "arg_slot_date";
    private static final int NO_SLOT = -1;

    private TextView selectedGroupChip;
    private java.util.List<CampusPlace> places = new java.util.ArrayList<>();
    private String selectedGroupId;

    /** In this examples, it opens the sheet with a slot chosen in Compare Availability, so BQ3 can be measured in this case. */
    public static CreateActivitySheet newInstance(String groupId, int groupSize, String slotTime,
                                                  int slotPosition, int recommendedPosition) {
        return newInstance(groupId, groupSize, slotTime, slotPosition, recommendedPosition, null);
    }

    public static CreateActivitySheet newInstance(String groupId, int groupSize, String slotTime,
                                                  int slotPosition, int recommendedPosition,
                                                  String recommendationId) {
        CreateActivitySheet sheet = new CreateActivitySheet();
        Bundle args = new Bundle();
        args.putString(ARG_GROUP_ID, groupId);
        args.putInt(ARG_GROUP_SIZE, groupSize);
        args.putString(ARG_SLOT_TIME, slotTime);
        args.putInt(ARG_SLOT_POSITION, slotPosition);
        args.putInt(ARG_RECOMMENDED_POSITION, recommendedPosition);
        args.putString(ARG_RECOMMENDATION_ID, recommendationId);
        sheet.setArguments(args);
        return sheet;
    }

    /* Pre-fills the date field, for example with the day picked in the Schedule screen. */
    public CreateActivitySheet withSuggestedDate(String date) {
        Bundle args = getArguments() != null ? getArguments() : new Bundle();
        args.putString(ARG_SLOT_DATE, date);
        setArguments(args);
        return this;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_create_activity, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments() != null ? getArguments() : new Bundle();
        String suggestedGroupId = args.getString(ARG_GROUP_ID);
        String suggestedTime = args.getString(ARG_SLOT_TIME);
        int slotPosition = args.getInt(ARG_SLOT_POSITION, NO_SLOT);
        String recommendationId = args.getString(ARG_RECOMMENDATION_ID);

        EditText titleInput = view.findViewById(R.id.input_activity_title);
        EditText dateInput = view.findViewById(R.id.input_activity_date);
        EditText timeInput = view.findViewById(R.id.input_activity_time);
        EditText locationInput = view.findViewById(R.id.input_activity_location);
        loadPlaceChips(view.findViewById(R.id.place_chips), locationInput);
        if (suggestedTime != null) {
            timeInput.setText(suggestedTime);
        }
        if (args.getString(ARG_SLOT_DATE) != null) {
            dateInput.setText(args.getString(ARG_SLOT_DATE));
        }

        TextView engChip = view.findViewById(R.id.chip_group_eng);
        TextView roomiesChip = view.findViewById(R.id.chip_group_roomies);

        view.findViewById(R.id.sheet_close_btn).setOnClickListener(v -> dismiss());
        View createButton = view.findViewById(R.id.btn_create_activity);
        createButton.setEnabled(false);
        loadGroups(engChip, roomiesChip, createButton, suggestedGroupId);
        createButton.setOnClickListener(v -> {
            if (selectedGroupId == null) {
                Toast.makeText(requireContext(), "No group is available", Toast.LENGTH_SHORT).show();
                return;
            }
            String title = titleInput.getText().toString().trim();
            if (title.isEmpty()) {
                titleInput.setError("Enter an activity title");
                return;
            }
            String time = timeInput.getText().toString().trim();
            String location = locationInput.getText().toString().trim();
            PlannedActivity activity = new PlannedActivity(null, selectedGroupId, title, "other",
                    dateInput.getText().toString().trim(), time,
                    location, PlannedActivity.STATUS_PROPOSED,
                    System.currentTimeMillis(), args.getInt(ARG_GROUP_SIZE, 0), slotPosition != NO_SLOT);
            // if the place is one of campusAreas we save its id, so travel time knows the coordinates
            CampusPlace place = CampusPlaceRepository.placeOf(activity, places);
            if (place != null) {
                activity = activity.withLocationArea(place.getId());
            }

            createButton.setEnabled(false);
            String creationSessionId = UUID.randomUUID().toString();
            String creationSource = slotPosition == NO_SLOT ? "manual" : "suggested_slot";
            long creationStartedAt = System.currentTimeMillis();
            ActivityCreationTelemetry.attempt(creationSessionId, creationSource, args.getInt(ARG_GROUP_SIZE, 0));
            Activity host = requireActivity();
            Repositories.activities().createActivity(activity, new Callback<PlannedActivity>() {
                @Override
                public void onSuccess(PlannedActivity saved) {
                    ActivityCreationTelemetry.result(creationSessionId, creationSource,
                            System.currentTimeMillis() - creationStartedAt, true, saved.getId(), null);
                    trackCreation(slotPosition, args, !time.equals(suggestedTime)
                            || !selectedGroupId.equals(suggestedGroupId), creationSessionId,
                            saved.getId(), recommendationId);
                    if (isAdded()) {
                        dismiss();
                    }
                    host.startActivity(new Intent(host, ActivityCreatedActivity.class)
                            .putExtra(ActivityCreatedActivity.EXTRA_ACTIVITY_ID, saved.getId())
                            .putExtra(ActivityCreatedActivity.EXTRA_TITLE, saved.getTitle())
                            .putExtra(ActivityCreatedActivity.EXTRA_DATE, saved.getDate())
                            .putExtra(ActivityCreatedActivity.EXTRA_TIME, saved.getTime())
                            .putExtra(ActivityCreatedActivity.EXTRA_LOCATION, saved.getLocation()));
                }

                @Override
                public void onError(Exception error) {
                    ActivityCreationTelemetry.result(creationSessionId, creationSource,
                            System.currentTimeMillis() - creationStartedAt, false, null,
                            error.getClass().getSimpleName());
                    createButton.setEnabled(true);
                    Toast.makeText(host, "Could not create the activity. Try again.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void trackCreation(int slotPosition, Bundle args, boolean modified,
                               String creationSessionId, String activityId,
                               String recommendationId) {
        AnalyticsTracker tracker = AnalyticsTracker.getInstance();
        Map<String, Object> created = new HashMap<>();
        created.put(AnalyticsEvents.PARAM_CATEGORY, "other");
        created.put(AnalyticsEvents.PARAM_SOURCE, slotPosition == NO_SLOT ? "manual" : "suggested_slot");
        created.put(AnalyticsEvents.PARAM_CREATION_SESSION_ID, creationSessionId);
        created.put(AnalyticsEvents.PARAM_ACTIVITY_ID, activityId);
        created.put(AnalyticsEvents.PARAM_GROUP_SIZE, args.getInt(ARG_GROUP_SIZE, 0));
        tracker.track(AnalyticsEvents.ACTIVITY_CREATED, created);

        if (slotPosition == NO_SLOT) {
            return;
        }
        // BQ3: was the suggested slot kept exactly as proposed (really??)?
        Map<String, Object> accepted = new HashMap<>();
        accepted.put(AnalyticsEvents.PARAM_GROUP_SIZE, args.getInt(ARG_GROUP_SIZE));
        accepted.put(AnalyticsEvents.PARAM_SLOT_POSITION, slotPosition);
        accepted.put(AnalyticsEvents.PARAM_RECOMMENDED_POSITION, args.getInt(ARG_RECOMMENDED_POSITION));
        // bq3: we send 1 or 0 (not true/false) because the sql query reads this value as a number
        accepted.put(AnalyticsEvents.PARAM_MODIFIED, modified ? 1 : 0);
        if (recommendationId != null) {
            accepted.put(AnalyticsEvents.PARAM_RECOMMENDATION_ID, recommendationId);
        }
        tracker.track(AnalyticsEvents.SLOT_ACCEPTED, accepted);
    }

    private void selectGroup(TextView chip, String groupId) {
        if (selectedGroupChip != null) {
            selectedGroupChip.setBackgroundResource(R.drawable.bg_chip_unselected);
            selectedGroupChip.setTextColor(requireContext().getColor(R.color.brand_dark));
        }
        selectedGroupChip = chip;
        selectedGroupId = groupId;
        chip.setBackgroundResource(R.drawable.bg_chip_selected);
        chip.setTextColor(requireContext().getColor(R.color.brand_primary));
    }

    private void loadGroups(TextView firstChip, TextView secondChip, View createButton,
                             String suggestedGroupId) {
        Repositories.groups().getGroups(UserSession.getCurrentUser().getId(), new Callback<List<Group>>() {
            @Override
            public void onSuccess(List<Group> groups) {
                bindGroupChip(firstChip, groups, 0);
                bindGroupChip(secondChip, groups, 1);
                if (groups.isEmpty()) {
                    return;
                }
                int selectedIndex = 0;
                for (int i = 0; i < groups.size(); i++) {
                    if (groups.get(i).getId().equals(suggestedGroupId)) {
                        selectedIndex = i;
                        break;
                    }
                }
                TextView chip = selectedIndex == 1 && groups.size() > 1 ? secondChip : firstChip;
                selectGroup(chip, groups.get(selectedIndex).getId());
                createButton.setEnabled(true);
            }

            @Override
            public void onError(Exception error) {
                firstChip.setVisibility(View.GONE);
                secondChip.setVisibility(View.GONE);
            }
        });
    }

    private void bindGroupChip(TextView chip, List<Group> groups, int index) {
        if (index >= groups.size()) {
            chip.setVisibility(View.GONE);
            return;
        }
        Group group = groups.get(index);
        chip.setVisibility(View.VISIBLE);
        chip.setText(group.getName());
        chip.setOnClickListener(v -> selectGroup(chip, group.getId()));
    }

    /* Places come from campusAreas in Firestore; tapping one writes its name in the location field. */
    private void loadPlaceChips(android.widget.LinearLayout chips, EditText locationInput) {
        Repositories.places().getPlaces(new Callback<java.util.List<CampusPlace>>() {
            @Override
            public void onSuccess(java.util.List<CampusPlace> loaded) {
                places = loaded;
                if (!isAdded()) {
                    return;
                }
                chips.removeAllViews();
                for (CampusPlace place : loaded) {
                    TextView chip = new TextView(requireContext());
                    chip.setText(place.getName());
                    chip.setTextSize(11);
                    chip.setTextColor(requireContext().getColor(R.color.brand_primary));
                    chip.setBackgroundResource(R.drawable.bg_chip_unselected);
                    chip.setPadding(28, 14, 28, 14);
                    android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.setMarginEnd(12);
                    chip.setOnClickListener(v -> locationInput.setText(place.getName()));
                    chips.addView(chip, params);
                }
            }

            @Override
            public void onError(Exception error) {
                places = new java.util.ArrayList<>();
            }
        });
    }
}
