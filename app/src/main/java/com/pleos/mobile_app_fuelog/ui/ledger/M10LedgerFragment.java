package com.pleos.mobile_app_fuelog.ui.ledger;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.pleos.mobile_app_fuelog.MainActivity;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.home.ExpenseRecord;
import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.home.MonthlyExpenseSummary;
import com.pleos.mobile_app_fuelog.data.home.PendingTaskItem;
import com.pleos.mobile_app_fuelog.data.home.VehicleProfile;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.databinding.FragmentM10LedgerBinding;
import com.pleos.mobile_app_fuelog.ui.vehicle.VehicleSwitcherBottomSheetHelper;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

import com.pleos.mobile_app_fuelog.ui.maintenance.M11MaintenanceEventFragment;

/**
 * M10. 차계부 메인 화면.
 * - 월별 실제 지출 총액, 주유비, 정비비 요약 표시.
 * - 감지된 미확인 주유가 있을 경우 최상단 알림 배너를 통해 M07(주유 확인)으로 유도.
 * - [+ 주유·지출] 버튼을 통해 M07(수동 등록)으로 이동.
 * - 지출 내역 목록 표시.
 */
public class M10LedgerFragment extends Fragment {

    private FragmentM10LedgerBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private M03HomeRepository homeRepository;

    private ExpenseListAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentM10LedgerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authRepository = AuthRepository.getInstance(requireContext());
        userCacheManager = UserCacheManager.getInstance(requireContext());
        homeRepository = M03HomeRepository.getInstance();

        setupViews();
        setupRecyclerView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (homeRepository.getVehicles().isEmpty()) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    FuelogBackendApiClient apiClient = FuelogBackendApiClient.getInstance(authRepository);
                    List<FuelogBackendApiClient.BackendVehicleItem> backendVehicles = apiClient.fetchMobileVehicles();
                    if (backendVehicles != null && !backendVehicles.isEmpty()) {
                        for (FuelogBackendApiClient.BackendVehicleItem bv : backendVehicles) {
                            String vid = bv.vehicleId != null ? bv.vehicleId : bv.id;
                            userCacheManager.setServerVehicleName(vid, bv.getDisplayName());
                        }
                        homeRepository.applyBackendUserVehicles(backendVehicles, userCacheManager::getEffectiveVehicleName);
                    }
                } catch (Exception ignored) {
                }
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        VehicleProfile cur = homeRepository.getSelectedVehicle();
                        if (cur != null) {
                            syncMaintenanceFromBackend(cur.getVehicleId());
                        }
                        renderLedgerData();
                    });
                }
            });
        } else {
            VehicleProfile cur = homeRepository.getSelectedVehicle();
            if (cur != null) {
                syncMaintenanceFromBackend(cur.getVehicleId());
            }
            renderLedgerData();
        }
    }

    private void syncMaintenanceFromBackend(@NonNull String vehicleId) {
        new Thread(() -> {
            try {
                FuelogBackendApiClient apiClient = FuelogBackendApiClient.getInstance(authRepository);
                List<FuelogBackendApiClient.MaintenanceEventItem> events = apiClient.fetchMaintenanceEvents(vehicleId);
                if (!events.isEmpty()) {
                    homeRepository.applyBackendMaintenanceEvents(vehicleId, events);
                    if (isAdded() && getActivity() != null) {
                        getActivity().runOnUiThread(this::renderLedgerData);
                    }
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void setupViews() {
        // 차량명 캡슐 칩 및 차량 변경 버튼 클릭 시 바텀시트 열기
        binding.btnM10VehicleChip.setOnClickListener(v -> showVehicleSwitcherDialog());
        binding.btnM10SwitchVehicle.setOnClickListener(v -> showVehicleSwitcherDialog());
        binding.btnChangeVehicleM10.setOnClickListener(v -> showVehicleSwitcherDialog());

        // [+ 주유·지출] 등록 버튼 -> 주유 / 정비 선택 분기
        binding.btnQuickAddExpense.setOnClickListener(v -> {
            String[] options = new String[]{"⛽ 주유 · 충전 내역 등록", "🔧 정비 · 소모품 내역 등록"};
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("지출 항목 선택")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) {
                            NavHostFragment.findNavController(this).navigate(R.id.nav_m07_fuel_confirm);
                        } else {
                            Bundle args = new Bundle();
                            args.putString(M11MaintenanceEventFragment.ARG_MODE, M11MaintenanceEventFragment.MODE_CREATE);
                            if (homeRepository.getSelectedVehicle() != null) {
                                args.putString(M11MaintenanceEventFragment.ARG_VEHICLE_ID, homeRepository.getSelectedVehicle().getVehicleId());
                            }
                            NavHostFragment.findNavController(this).navigate(R.id.nav_m11_maintenance_event, args);
                        }
                    })
                    .setNegativeButton("취소", null)
                    .show();
        });

        // 감지된 주유 확인 배너 -> M07 자동 감지 확인 모드
        binding.cardPendingFuelBanner.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putString(M07FuelConfirmFragment.ARG_CANDIDATE_ID, "cand-auto-detected-01");
            NavHostFragment.findNavController(this).navigate(R.id.nav_m07_fuel_confirm, args);
        });

        // 정비 주기 관리 화면으로 이동
        binding.btnLedgerGotoMaint.setOnClickListener(v -> {
            NavHostFragment.findNavController(this).navigate(R.id.nav_m12_maintenance_schedules);
        });
    }

    private void showVehicleSwitcherDialog() {
        List<VehicleProfile> vehicles = homeRepository.getVehicles();
        if (vehicles.isEmpty()) {
            Snackbar.make(binding.getRoot(), "차량 목록을 불러오는 중입니다...", Snackbar.LENGTH_SHORT).show();
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    FuelogBackendApiClient apiClient = FuelogBackendApiClient.getInstance(authRepository);
                    List<FuelogBackendApiClient.BackendVehicleItem> backendVehicles = apiClient.fetchMobileVehicles();
                    if (backendVehicles != null && !backendVehicles.isEmpty()) {
                        for (FuelogBackendApiClient.BackendVehicleItem bv : backendVehicles) {
                            String vid = bv.vehicleId != null ? bv.vehicleId : bv.id;
                            userCacheManager.setServerVehicleName(vid, bv.getDisplayName());
                        }
                        homeRepository.applyBackendUserVehicles(backendVehicles, userCacheManager::getEffectiveVehicleName);
                    }
                } catch (Exception ignored) {
                }
                if (isAdded() && getActivity() != null) {
                    requireActivity().runOnUiThread(() -> {
                        List<VehicleProfile> refreshed = homeRepository.getVehicles();
                        String currentVid = homeRepository.getSelectedVehicle() != null
                                ? homeRepository.getSelectedVehicle().getVehicleId()
                                : "";
                        VehicleSwitcherBottomSheetHelper.show(requireContext(), refreshed, currentVid, this::onVehicleSelected);
                    });
                }
            });
            return;
        }

        String currentVid = homeRepository.getSelectedVehicle() != null
                ? homeRepository.getSelectedVehicle().getVehicleId()
                : "";

        VehicleSwitcherBottomSheetHelper.show(requireContext(), vehicles, currentVid, this::onVehicleSelected);
    }

    private void onVehicleSelected(@NonNull VehicleProfile selected) {
        homeRepository.selectVehicle(selected.getVehicleId());

        AuthSession session = authRepository.getCurrentValidSession();
        if (session != null) {
            String uid = session.getUserId();
            userCacheManager.setRegisteredVehicleForUser(uid, selected.getDisplayName());
            userCacheManager.setRegisteredVehicleUuid(uid, selected.getVehicleId());
            if (selected.getInstallationId() != null) {
                userCacheManager.setInstallationCredentials(
                        uid,
                        selected.getInstallationId(),
                        userCacheManager.getInstallationSecret(uid)
                );
            }
        }
        com.pleos.mobile_app_fuelog.data.connection.M04DriverConnectionCoordinator.getInstance()
                .setVehicleAlias(selected.getDisplayName());

        syncMaintenanceFromBackend(selected.getVehicleId());
        renderLedgerData();
        Snackbar.make(binding.getRoot(), "차계부 차량이 변경되었습니다: " + selected.getDisplayName(), Snackbar.LENGTH_SHORT).show();
    }

    private void showMaintenanceRegisterDialog() {
        VehicleProfile current = homeRepository.getSelectedVehicle();
        if (current == null) {
            Snackbar.make(binding.getRoot(), "선택된 차량이 없습니다.", Snackbar.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_register_maintenance, null);
        TextView textVehicleName = dialogView.findViewById(R.id.text_dialog_vehicle_name);
        AutoCompleteTextView autoItem = dialogView.findViewById(R.id.auto_maintenance_item);
        TextInputEditText editDate = dialogView.findViewById(R.id.edit_service_date);
        TextInputEditText editOdo = dialogView.findViewById(R.id.edit_odometer_km);
        TextInputEditText editShop = dialogView.findViewById(R.id.edit_shop_name);
        TextInputEditText editCost = dialogView.findViewById(R.id.edit_cost_won);
        TextInputEditText editMemo = dialogView.findViewById(R.id.edit_memo);

        textVehicleName.setText(current.getDisplayName());

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);
        editDate.setText(sdf.format(new Date()));
        double currentOdo = homeRepository.getOdometerForVehicle(current.getVehicleId());
        if (currentOdo > 0) {
            editOdo.setText(String.format(Locale.KOREA, "%.0f", currentOdo));
        }

        String[] items = new String[]{
                "엔진오일 교환",
                "에어컨 필터 교체",
                "와이퍼 블레이드 교체",
                "타이어 위치 교환 / 교체",
                "브레이크 패드 점검 / 교체",
                "배터리 점검 / 교체",
                "일반 소모품 정비"
        };
        ArrayAdapter<String> itemAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_dropdown_item_1line, items);
        autoItem.setAdapter(itemAdapter);

        new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .setPositiveButton("DB 등록 완료", (dialog, which) -> {
                    String itemName = autoItem.getText().toString().trim();
                    if (itemName.isEmpty()) itemName = "엔진오일 교환";
                    String serviceDate = editDate.getText() != null && !editDate.getText().toString().trim().isEmpty()
                            ? editDate.getText().toString().trim()
                            : sdf.format(new Date());

                    String odoStr = editOdo.getText() != null ? editOdo.getText().toString().trim() : "";
                    Double odoVal = null;
                    try {
                        if (!odoStr.isEmpty()) odoVal = Double.parseDouble(odoStr);
                    } catch (Exception ignored) {}

                    String shopName = editShop.getText() != null ? editShop.getText().toString().trim() : "";
                    String costStr = editCost.getText() != null ? editCost.getText().toString().trim() : "0";
                    long costVal = 0L;
                    try {
                        costVal = Long.parseLong(costStr.replace(",", ""));
                    } catch (Exception ignored) {}

                    String memoText = editMemo.getText() != null ? editMemo.getText().toString().trim() : "";

                    final String finalItemName = itemName;
                    final String finalServiceDate = serviceDate;
                    final Double finalOdo = odoVal;
                    final String finalShop = shopName;
                    final long finalCost = costVal;
                    final String finalMemo = memoText;

                    new Thread(() -> {
                        FuelogBackendApiClient apiClient = FuelogBackendApiClient.getInstance(authRepository);
                        boolean ok = apiClient.createMaintenanceEvent(
                                current.getVehicleId(),
                                finalItemName,
                                finalServiceDate,
                                finalOdo,
                                finalCost,
                                finalShop,
                                finalMemo
                        );

                        // DB 등록 후 최신 정비 이력 다시 조회하여 로컬 저장소 동기화
                        List<FuelogBackendApiClient.MaintenanceEventItem> updatedEvents =
                                apiClient.fetchMaintenanceEvents(current.getVehicleId());
                        if (!updatedEvents.isEmpty()) {
                            homeRepository.applyBackendMaintenanceEvents(current.getVehicleId(), updatedEvents);
                        } else {
                            // 오프라인 fallback
                            homeRepository.addExpenseRecord(new ExpenseRecord(
                                    "maint_manual_" + System.currentTimeMillis(),
                                    current.getVehicleId(),
                                    ExpenseRecord.Category.MAINTENANCE,
                                    ExpenseRecord.Ownership.MY_RECORD,
                                    ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                                    (finalShop.isEmpty() ? "" : finalShop + " - ") + finalItemName,
                                    finalServiceDate.length() >= 10 ? finalServiceDate.substring(5, 7) + "월 " + finalServiceDate.substring(8, 10) + "일" : "오늘",
                                    finalCost
                            ));
                        }

                        if (isAdded() && getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                renderLedgerData();
                                Snackbar.make(binding.getRoot(),
                                        ok ? "정비 내역이 데이터베이스에 등록되었습니다." : "정비 내역이 로컬 차계부에 반영되었습니다.",
                                        Snackbar.LENGTH_SHORT).show();
                            });
                        }
                    }).start();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void setupRecyclerView() {
        adapter = new ExpenseListAdapter(record -> {
            if (record.getCategory() == ExpenseRecord.Category.MAINTENANCE) {
                Bundle args = new Bundle();
                args.putString(M11MaintenanceEventFragment.ARG_MODE, M11MaintenanceEventFragment.MODE_DETAIL);
                args.putString(M11MaintenanceEventFragment.ARG_VEHICLE_ID, record.getVehicleId());
                args.putString(M11MaintenanceEventFragment.ARG_RECORD_ID, record.getRecordId());
                if (record.getRecordId().startsWith("maint_db_")) {
                    args.putString(M11MaintenanceEventFragment.ARG_EVENT_ID, record.getRecordId().substring("maint_db_".length()));
                }
                args.putString(M11MaintenanceEventFragment.ARG_ITEM_NAME, record.getTitle());
                NavHostFragment.findNavController(this).navigate(R.id.nav_m11_maintenance_event, args);
            } else if (record.getCategory() == ExpenseRecord.Category.FUEL) {
                Bundle args = new Bundle();
                args.putString(M07FuelConfirmFragment.ARG_MODE, M07FuelConfirmFragment.MODE_EDIT);
                args.putString(M07FuelConfirmFragment.ARG_RECORD_ID, record.getRecordId());
                NavHostFragment.findNavController(this).navigate(R.id.nav_m07_fuel_confirm, args);
            } else {
                Snackbar.make(binding.getRoot(),
                        record.getTitle() + " (" + NumberFormat.getNumberInstance(Locale.KOREA).format(record.getAmountWon()) + "원)",
                        Snackbar.LENGTH_SHORT).show();
            }
        });
        binding.recyclerExpenses.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerExpenses.setAdapter(adapter);
    }

    private void renderLedgerData() {
        if (binding == null) {
            return;
        }

        VehicleProfile current = homeRepository.getSelectedVehicle();
        if (current != null) {
            binding.textLedgerVehicleName.setText(current.getDisplayName());
        } else {
            String preferredVid = null;
            AuthSession session = authRepository.getCurrentValidSession();
            if (session != null) {
                preferredVid = userCacheManager.getRegisteredVehicleUuid(session.getUserId());
            }
            String name = null;
            if (preferredVid != null) {
                name = userCacheManager.getEffectiveVehicleName(preferredVid);
            }
            if (name == null || name.trim().isEmpty()) {
                name = userCacheManager.getVisibleCachedVehicleName();
            }
            if (name == null || name.trim().isEmpty()) {
                name = "내 차량";
            }
            binding.textLedgerVehicleName.setText(name);
        }

        List<ExpenseRecord> expenses = homeRepository.getConfirmedExpensesForSelectedVehicle();
        if (expenses.isEmpty() && current != null) {
            homeRepository.ensureSampleExpensesAndTasksForVehicle(
                    current.getVehicleId(),
                    current.getDisplayName(),
                    current.getFuelType()
            );
            expenses = homeRepository.getConfirmedExpensesForSelectedVehicle();
        }

        long fuelSum = 0L;
        long maintSum = 0L;
        for (ExpenseRecord e : expenses) {
            if (e.getCategory() == ExpenseRecord.Category.FUEL) {
                fuelSum += e.getAmountWon();
            } else if (e.getCategory() == ExpenseRecord.Category.MAINTENANCE) {
                maintSum += e.getAmountWon();
            }
        }
        long totalSum = fuelSum + maintSum;

        binding.textMonthTotalExpense.setText(
                NumberFormat.getNumberInstance(Locale.KOREA).format(totalSum) + "원"
        );
        binding.textMonthFuelExpense.setText(
                NumberFormat.getNumberInstance(Locale.KOREA).format(fuelSum) + "원"
        );
        binding.textMonthMaintExpense.setText(
                NumberFormat.getNumberInstance(Locale.KOREA).format(maintSum) + "원"
        );

        binding.textExpenseCount.setText(String.format(Locale.KOREA, "총 %d건", expenses.size()));

        if (expenses.isEmpty()) {
            binding.recyclerExpenses.setVisibility(View.GONE);
            binding.textEmptyExpenses.setVisibility(View.VISIBLE);
        } else {
            binding.recyclerExpenses.setVisibility(View.VISIBLE);
            binding.textEmptyExpenses.setVisibility(View.GONE);
            adapter.setItems(expenses);
        }

        // 미확인 주유 감지 건이 있는지 확인 (M03 pending tasks 기반)
        boolean hasPendingFuel = false;
        for (PendingTaskItem task : homeRepository.getPendingTasksForSelectedVehicle()) {
            if (task.getDestination() == PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM) {
                hasPendingFuel = true;
                binding.textPendingFuelSubtitle.setText(task.getTitle() + " — 탭하여 실제 결제 금액을 확인해 주세요.");
                break;
            }
        }
        binding.cardPendingFuelBanner.setVisibility(hasPendingFuel ? View.VISIBLE : View.GONE);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateBottomNavBadges();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
