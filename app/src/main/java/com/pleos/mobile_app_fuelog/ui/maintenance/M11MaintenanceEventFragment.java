package com.pleos.mobile_app_fuelog.ui.maintenance;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.home.ExpenseRecord;
import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.home.VehicleProfile;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.databinding.FragmentM11MaintenanceEventBinding;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * M11. 정비 이력 직접 등록 및 상세/수정/삭제 Fragment
 */
public class M11MaintenanceEventFragment extends Fragment {

    public static final String ARG_MODE = "arg_mode";             // "CREATE" or "DETAIL"
    public static final String ARG_EVENT_ID = "arg_event_id";
    public static final String ARG_RECORD_ID = "arg_record_id";
    public static final String ARG_VEHICLE_ID = "arg_vehicle_id";
    public static final String ARG_ITEM_NAME = "arg_item_name";

    public static final String MODE_CREATE = "CREATE";
    public static final String MODE_DETAIL = "DETAIL";

    private FragmentM11MaintenanceEventBinding binding;
    private AuthRepository authRepository;
    private M03HomeRepository homeRepository;
    private FuelogBackendApiClient apiClient;

    private String mode = MODE_CREATE;
    @Nullable private String vehicleId;
    @Nullable private String eventId;
    @Nullable private String recordId;
    @Nullable private String initialItemName;

    private String selectedServiceDate;
    private long eventRevision = 1L;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authRepository = AuthRepository.getInstance(requireContext());
        homeRepository = M03HomeRepository.getInstance();
        apiClient = FuelogBackendApiClient.getInstance(authRepository);

        if (getArguments() != null) {
            mode = getArguments().getString(ARG_MODE, MODE_CREATE);
            vehicleId = getArguments().getString(ARG_VEHICLE_ID);
            eventId = getArguments().getString(ARG_EVENT_ID);
            recordId = getArguments().getString(ARG_RECORD_ID);
            initialItemName = getArguments().getString(ARG_ITEM_NAME);
        }

        if (vehicleId == null || vehicleId.trim().isEmpty()) {
            VehicleProfile selected = homeRepository.getSelectedVehicle();
            if (selected != null) {
                vehicleId = selected.getVehicleId();
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentM11MaintenanceEventBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initHeaderAndSubtitle();
        setupDatePicker();
        setupItemChips();
        setupOdometerAndCost();
        setupFreeServiceCheckbox();
        setupActions();

        if (MODE_DETAIL.equalsIgnoreCase(mode)) {
            setupDetailMode();
        } else {
            setupCreateMode();
        }
    }

    private void initHeaderAndSubtitle() {
        if (MODE_DETAIL.equalsIgnoreCase(mode)) {
            binding.tvM11Title.setText("정비 상세 내역");
            binding.btnM11Save.setText("수정 내용 저장");
            binding.btnM11Delete.setVisibility(View.VISIBLE);
        } else {
            binding.tvM11Title.setText("정비 내역 등록");
            binding.btnM11Save.setText("정비 기록 저장");
            binding.btnM11Delete.setVisibility(View.GONE);
        }

        VehicleProfile v = homeRepository.getSelectedVehicle();
        if (v != null) {
            binding.tvM11VehicleSubtitle.setText(v.getDisplayName() + " (" + v.getPlateNumber() + ")");
        } else {
            binding.tvM11VehicleSubtitle.setText("등록 차량");
        }
    }

    private void setupDatePicker() {
        // 기본값: 오늘
        LocalDate today = LocalDate.now();
        selectedServiceDate = today.toString();
        binding.tvM11ServiceDate.setText(selectedServiceDate);

        binding.layoutM11DatePicker.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            if (selectedServiceDate != null && selectedServiceDate.length() >= 10) {
                try {
                    int y = Integer.parseInt(selectedServiceDate.substring(0, 4));
                    int m = Integer.parseInt(selectedServiceDate.substring(5, 7)) - 1;
                    int d = Integer.parseInt(selectedServiceDate.substring(8, 10));
                    cal.set(y, m, d);
                } catch (Exception ignored) {}
            }

            DatePickerDialog picker = new DatePickerDialog(
                    requireContext(),
                    (view, year, month, dayOfMonth) -> {
                        selectedServiceDate = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                        binding.tvM11ServiceDate.setText(selectedServiceDate);
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
            );
            picker.show();
        });
    }

    private void setupItemChips() {
        binding.chipGroupM11Items.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chip_item_custom)) {
                binding.tilM11CustomItem.setVisibility(View.VISIBLE);
            } else {
                binding.tilM11CustomItem.setVisibility(View.GONE);
            }
        });

        if (initialItemName != null && !initialItemName.trim().isEmpty()) {
            selectChipByItemName(initialItemName.trim());
        }
    }

    private void selectChipByItemName(@NonNull String name) {
        if ("엔진오일".equals(name)) binding.chipItemEngineOil.setChecked(true);
        else if ("에어컨 필터".equals(name) || "에어컨필터".equals(name)) binding.chipItemAirconFilter.setChecked(true);
        else if ("와이퍼".equals(name)) binding.chipItemWiper.setChecked(true);
        else if ("브레이크 패드".equals(name) || "브레이크패드".equals(name)) binding.chipItemBrakePad.setChecked(true);
        else if ("타이어".equals(name)) binding.chipItemTire.setChecked(true);
        else if ("배터리".equals(name)) binding.chipItemBattery.setChecked(true);
        else if ("미션오일".equals(name)) binding.chipItemMissionOil.setChecked(true);
        else {
            binding.chipItemCustom.setChecked(true);
            binding.tilM11CustomItem.setVisibility(View.VISIBLE);
            binding.etM11CustomItem.setText(name);
        }
    }

    @NonNull
    private String getSelectedItemName() {
        int checkedId = binding.chipGroupM11Items.getCheckedChipId();
        if (checkedId == R.id.chip_item_engine_oil) return "엔진오일";
        if (checkedId == R.id.chip_item_aircon_filter) return "에어컨 필터";
        if (checkedId == R.id.chip_item_wiper) return "와이퍼";
        if (checkedId == R.id.chip_item_brake_pad) return "브레이크 패드";
        if (checkedId == R.id.chip_item_tire) return "타이어";
        if (checkedId == R.id.chip_item_battery) return "배터리";
        if (checkedId == R.id.chip_item_mission_oil) return "미션오일";
        if (checkedId == R.id.chip_item_custom) {
            String custom = binding.etM11CustomItem.getText() != null ? binding.etM11CustomItem.getText().toString().trim() : "";
            return !custom.isEmpty() ? custom : "기타 정비";
        }
        return "정비 소모품";
    }

    private void setupOdometerAndCost() {
        double latestOdo = homeRepository.getLatestOdometerKm();
        if (latestOdo > 0) {
            binding.tvM11OdoHint.setText(String.format(Locale.KOREA,
                    "💡 현재 차량 주행거리: 약 %,d km (과거 정비라면 당시 계기판 거리를 입력해 주세요)", Math.round(latestOdo)));
        } else {
            binding.tvM11OdoHint.setText("💡 계기판의 누적 주행거리를 입력하면 다음 정비 주기가 정확히 계산됩니다.");
        }
    }

    private void setupFreeServiceCheckbox() {
        binding.cbM11FreeService.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                binding.etM11Cost.setText("0");
                binding.etM11Cost.setEnabled(false);
            } else {
                binding.etM11Cost.setEnabled(true);
            }
        });
    }

    private void setupCreateMode() {
        double latestOdo = homeRepository.getLatestOdometerKm();
        if (latestOdo > 0 && (binding.etM11Odometer.getText() == null || binding.etM11Odometer.getText().toString().isEmpty())) {
            binding.etM11Odometer.setText(String.valueOf(Math.round(latestOdo)));
        }
    }

    private void setupDetailMode() {
        if (vehicleId == null) {
            return;
        }

        // 1. 이벤트 ID로 캐시된 정비 이력 찾기
        FuelogBackendApiClient.MaintenanceEventItem cached = null;
        if (eventId != null) {
            cached = homeRepository.getMaintenanceEventById(vehicleId, eventId);
        }
        if (cached != null) {
            bindEventData(cached);
        } else if (recordId != null) {
            // 2. 차계부 레코드(ExpenseRecord) 기반 바인딩
            ExpenseRecord expense = homeRepository.getExpenseRecordById(recordId);
            if (expense != null) {
                bindExpenseData(expense);
            }
        }

        // 3. 백엔드에서 최신 정보 재확인
        if (eventId != null) {
            binding.progressM11Loading.setVisibility(View.VISIBLE);
            Executors.newSingleThreadExecutor().execute(() -> {
                List<FuelogBackendApiClient.MaintenanceEventItem> events = apiClient.fetchMaintenanceEvents(vehicleId);
                if (!events.isEmpty()) {
                    homeRepository.applyBackendMaintenanceEvents(vehicleId, events);
                    FuelogBackendApiClient.MaintenanceEventItem found = null;
                    for (FuelogBackendApiClient.MaintenanceEventItem item : events) {
                        if (eventId.equals(item.id)) {
                            found = item;
                            break;
                        }
                    }
                    final FuelogBackendApiClient.MaintenanceEventItem finalFound = found;
                    if (isAdded() && getActivity() != null) {
                        requireActivity().runOnUiThread(() -> {
                            if (binding == null) return;
                            binding.progressM11Loading.setVisibility(View.GONE);
                            if (finalFound != null) {
                                bindEventData(finalFound);
                            }
                        });
                    }
                } else {
                    if (isAdded() && getActivity() != null) {
                        requireActivity().runOnUiThread(() -> {
                            if (binding == null) return;
                            binding.progressM11Loading.setVisibility(View.GONE);
                        });
                    }
                }
            });
        }
    }

    private void bindExpenseData(@NonNull ExpenseRecord exp) {
        if (exp.getAmountWon() == 0L) {
            binding.cbM11FreeService.setChecked(true);
            binding.etM11Cost.setText("0");
            binding.etM11Cost.setEnabled(false);
        } else {
            binding.cbM11FreeService.setChecked(false);
            binding.etM11Cost.setText(String.valueOf(exp.getAmountWon()));
            binding.etM11Cost.setEnabled(true);
        }
        String title = exp.getTitle();
        if (title.contains(" - ")) {
            String[] parts = title.split(" - ", 2);
            binding.etM11Shop.setText(parts[0].trim());
            selectChipByItemName(parts[1].trim());
        } else {
            selectChipByItemName(title);
        }
    }

    private void bindEventData(@NonNull FuelogBackendApiClient.MaintenanceEventItem ev) {
        eventRevision = ev.revision;

        if (ev.serviceDate != null && !ev.serviceDate.isEmpty()) {
            selectedServiceDate = ev.serviceDate;
            binding.tvM11ServiceDate.setText(selectedServiceDate);
        }
        if (ev.shop != null) {
            binding.etM11Shop.setText(ev.shop);
        }
        if (ev.odometerKm != null && ev.odometerKm > 0) {
            binding.etM11Odometer.setText(String.valueOf(Math.round(ev.odometerKm)));
        }
        if (ev.cost == 0L) {
            binding.cbM11FreeService.setChecked(true);
            binding.etM11Cost.setText("0");
            binding.etM11Cost.setEnabled(false);
        } else {
            binding.cbM11FreeService.setChecked(false);
            binding.etM11Cost.setText(String.valueOf(ev.cost));
            binding.etM11Cost.setEnabled(true);
        }
        if (ev.memo != null) {
            binding.etM11Memo.setText(ev.memo);
        }
        binding.cbM11ShareMember.setChecked(ev.shared);
        binding.cbM11ShareDetails.setChecked(ev.detailsShared);

        if (initialItemName != null && !initialItemName.isEmpty()) {
            selectChipByItemName(initialItemName);
        } else if (ev.memo != null && !ev.memo.isEmpty()) {
            selectChipByItemName(ev.memo);
        }
    }

    private void setupActions() {
        binding.btnM11Back.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack()
        );

        binding.btnM11Delete.setOnClickListener(v -> showDeleteConfirmDialog());

        binding.btnM11Save.setOnClickListener(v -> submitForm());
    }

    private void showDeleteConfirmDialog() {
        if (vehicleId == null || (eventId == null && recordId == null)) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("정비 기록 삭제")
                .setMessage("이 정비 기록을 삭제하시겠습니까?\n삭제된 기록은 지출 합계에서 제외되며, 다음 정비 일정이 이전 기록 기준으로 자동 재계산됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    performDelete();
                })
                .show();
    }

    private void performDelete() {
        if (vehicleId == null || (eventId == null && recordId == null)) return;

        binding.progressM11Loading.setVisibility(View.VISIBLE);
        binding.btnM11Save.setEnabled(false);
        binding.btnM11Delete.setEnabled(false);

        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success = true;
            if (eventId != null) {
                success = apiClient.deleteMaintenanceEvent(vehicleId, eventId, eventRevision);
            }
            if (recordId != null) {
                homeRepository.removeExpenseRecordById(recordId);
            }
            if (eventId != null) {
                homeRepository.removeMaintenanceEvent(vehicleId, eventId);
            }

            // 최신 정비 이력 동기화
            List<FuelogBackendApiClient.MaintenanceEventItem> updated = apiClient.fetchMaintenanceEvents(vehicleId);
            if (!updated.isEmpty()) {
                homeRepository.applyBackendMaintenanceEvents(vehicleId, updated);
            }

            final boolean finalSuccess = success;
            if (isAdded() && getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    if (binding == null) return;
                    binding.progressM11Loading.setVisibility(View.GONE);
                    if (finalSuccess) {
                        Toast.makeText(requireContext(), "정비 기록이 삭제되었습니다.", Toast.LENGTH_SHORT).show();
                        NavHostFragment.findNavController(this).popBackStack();
                    } else {
                        binding.btnM11Save.setEnabled(true);
                        binding.btnM11Delete.setEnabled(true);
                        Toast.makeText(requireContext(), "정비 기록 삭제에 실패했습니다. (완료 확인 건은 삭제 불가)", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void submitForm() {
        if (vehicleId == null) {
            Toast.makeText(requireContext(), "선택된 차량 정보가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        final String itemName = getSelectedItemName();
        final String serviceDate = selectedServiceDate != null ? selectedServiceDate : LocalDate.now().toString();

        String odoStr = binding.etM11Odometer.getText() != null ? binding.etM11Odometer.getText().toString().trim() : "";
        Double odoVal = null;
        try {
            if (!odoStr.isEmpty()) odoVal = Double.parseDouble(odoStr);
        } catch (Exception ignored) {}

        String shopName = binding.etM11Shop.getText() != null ? binding.etM11Shop.getText().toString().trim() : "";

        String costStr = binding.etM11Cost.getText() != null ? binding.etM11Cost.getText().toString().trim().replace(",", "") : "0";
        long costVal = 0L;
        try {
            if (!costStr.isEmpty()) costVal = Long.parseLong(costStr);
        } catch (Exception ignored) {}
        if (binding.cbM11FreeService.isChecked()) {
            costVal = 0L;
        }

        String memoText = binding.etM11Memo.getText() != null ? binding.etM11Memo.getText().toString().trim() : "";
        boolean shared = binding.cbM11ShareMember.isChecked();
        boolean detailsShared = binding.cbM11ShareDetails.isChecked();

        binding.progressM11Loading.setVisibility(View.VISIBLE);
        binding.btnM11Save.setEnabled(false);

        final Double finalOdo = odoVal;
        final long finalCost = costVal;
        final String finalShop = shopName;
        final String finalMemo = memoText;

        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success = false;
            if (MODE_DETAIL.equalsIgnoreCase(mode) && eventId != null) {
                success = apiClient.patchMaintenanceEvent(
                        vehicleId, eventId, eventRevision,
                        serviceDate, finalOdo, finalCost, finalShop, finalMemo, shared, detailsShared
                );
            } else if (MODE_CREATE.equalsIgnoreCase(mode)) {
                success = apiClient.createMaintenanceEvent(
                        vehicleId, itemName, serviceDate, finalOdo, finalCost, finalShop, finalMemo, shared, detailsShared
                );
            }

            // 차계부(ExpenseRecord) 즉시 동기화
            List<FuelogBackendApiClient.MaintenanceEventItem> updated = apiClient.fetchMaintenanceEvents(vehicleId);
            if (!updated.isEmpty()) {
                homeRepository.applyBackendMaintenanceEvents(vehicleId, updated);
            } else {
                // 서버 응답이 없거나 로컬/오프라인 모드일 때도 로컬 차계부 레코드 즉시 반영/수정
                String targetRecordId = (recordId != null && !recordId.isEmpty())
                        ? recordId
                        : (eventId != null ? "maint_db_" + eventId : "maint_manual_" + System.currentTimeMillis());

                String displayTitle = (finalShop.isEmpty() ? "" : finalShop + " - ") + itemName;
                String displayDate = serviceDate.length() >= 10
                        ? serviceDate.substring(5, 7) + "월 " + serviceDate.substring(8, 10) + "일"
                        : "오늘";

                homeRepository.addExpenseRecord(new ExpenseRecord(
                        targetRecordId,
                        vehicleId,
                        ExpenseRecord.Category.MAINTENANCE,
                        ExpenseRecord.Ownership.MY_RECORD,
                        ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                        displayTitle,
                        displayDate,
                        finalCost
                ));
            }

            if (isAdded() && getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    if (binding == null) return;
                    binding.progressM11Loading.setVisibility(View.GONE);
                    binding.btnM11Save.setEnabled(true);
                    String msg = MODE_DETAIL.equalsIgnoreCase(mode) ? "정비 기록이 수정되었습니다." : "정비 기록이 저장되었습니다.";
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                    NavHostFragment.findNavController(this).popBackStack();
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
