package com.example.parchapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.parchapp.data.Repositories;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class AvailabilityStatusSheet extends BottomSheetDialogFragment {

    private View selectedStatusRow;
    private ImageView selectedStatusCheck;
    private String selectedStatusName = "Commute / In Transit";

    private TextView selectedDurationChip;
    private String selectedDurationName = "For 1 hour";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_availability_status, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.sheet_close_btn).setOnClickListener(v -> dismiss());

        setupStatusRow(view, R.id.status_available, R.id.check_available, "Available now");
        setupStatusRow(view, R.id.status_class, R.id.check_class, "In Class");
        setupStatusRow(view, R.id.status_focus, R.id.check_focus, "Deep Focus");
        setupStatusRow(view, R.id.status_busy, R.id.check_busy, "Busy / Personal");
        setupStatusRow(view, R.id.status_transit, R.id.check_transit, "Commute / In Transit");

        // the sheet opens on the status the user really has (from the backend profile), not always on commute
        String current = Repositories.status().getAvailability();
        View currentRow = view.findViewById(rowFor(current.isEmpty() ? "Available now" : current));
        // the xml starts with the commute row highlighted, clicking the real one moves the highlight
        selectedStatusRow = view.findViewById(R.id.status_transit);
        selectedStatusCheck = view.findViewById(R.id.check_transit);
        currentRow.performClick();

        TextView[] durationChips = {
                view.findViewById(R.id.duration_1h),
                view.findViewById(R.id.duration_6pm),
                view.findViewById(R.id.duration_tomorrow),
                view.findViewById(R.id.duration_manual)
        };
        String[] durationShortLabels = {"1h", "Until 6PM", "Until tomorrow", "Manual"};
        selectedDurationChip = durationChips[0];
        TextView durationSelectedLabel = view.findViewById(R.id.duration_selected_label);
        for (int i = 0; i < durationChips.length; i++) {
            TextView chip = durationChips[i];
            String shortLabel = durationShortLabels[i];
            chip.setOnClickListener(v -> {
                selectDurationChip(chip);
                durationSelectedLabel.setText("◷ " + shortLabel + " selected");
                selectedDurationName = chip.getText().toString();
            });
        }

        view.findViewById(R.id.save_status_button).setOnClickListener(v -> {
            Repositories.status().setAvailability(selectedStatusName, selectedDurationName);
            Toast.makeText(requireContext(), selectedStatusName + " · " + selectedDurationName, Toast.LENGTH_SHORT).show();
            dismiss();
        });
    }

    private static int rowFor(String label) {
        switch (com.example.parchapp.domain.StatusService.codeFor(label)) {
            case com.example.parchapp.data.ProfileRepository.IN_CLASS:
                return R.id.status_class;
            case com.example.parchapp.data.ProfileRepository.FOCUSED:
                return R.id.status_focus;
            case com.example.parchapp.data.ProfileRepository.BUSY:
                return R.id.status_busy;
            case com.example.parchapp.data.ProfileRepository.IN_TRANSIT:
                return R.id.status_transit;
            default:
                return R.id.status_available;
        }
    }

    private void setupStatusRow(View root, int rowId, int checkId, String statusName) {
        View row = root.findViewById(rowId);
        ImageView check = root.findViewById(checkId);
        row.setOnClickListener(v -> {
            if (selectedStatusRow != null) {
                selectedStatusRow.setBackgroundResource(R.drawable.bg_option_row_unselected);
                selectedStatusCheck.setVisibility(View.GONE);
                FrameLayout previousFrame = (FrameLayout) selectedStatusCheck.getParent();
                previousFrame.getChildAt(0).setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F1F5F9")));
            }
            row.setBackgroundResource(R.drawable.bg_option_row_selected);
            check.setVisibility(View.VISIBLE);
            FrameLayout frame = (FrameLayout) check.getParent();
            frame.getChildAt(0).setBackgroundTintList(
                    androidx.core.content.ContextCompat.getColorStateList(requireContext(), R.color.brand_primary));

            selectedStatusRow = row;
            selectedStatusCheck = check;
            selectedStatusName = statusName;
        });
    }

    private void selectDurationChip(TextView chip) {
        if (selectedDurationChip != null) {
            selectedDurationChip.setBackgroundResource(R.drawable.bg_chip_unselected);
            selectedDurationChip.setTextColor(requireContext().getColor(R.color.brand_dark));
        }
        chip.setBackgroundResource(R.drawable.bg_chip_selected);
        chip.setTextColor(requireContext().getColor(R.color.brand_primary));
        selectedDurationChip = chip;
    }
}
