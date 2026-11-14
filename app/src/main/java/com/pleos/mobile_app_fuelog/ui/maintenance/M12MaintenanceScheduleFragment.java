package com.pleos.mobile_app_fuelog.ui.maintenance;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.home.ExpenseRecord;
import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.home.VehicleProfile;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.databinding.DialogCompleteMaintenanceBinding;
import com.pleos.mobile_app_fuelog.databinding.DialogEditMaintenanceCycleBinding;
import com.pleos.mobile_app_fuelog.databinding.FragmentM12MaintenanceScheduleBinding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * M12. 소모품 정비 예정 상세 및 교체 주기 설정 Fragment
 */
public class M12MaintenanceScheduleFragment extends Fragment implements MaintenanceScheduleAdapter.OnPlanActionListener {

    private FragmentM12MaintenanceScheduleBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private M03HomeRepository homeRepository;
    private FuelogBackendApiClient apiClient;
    private MaintenanceScheduleAdapter adapter;

    @Nullable
    private String activeVehicleId;
    private double currentOdometerKm = 0.0;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentM12MaintenanceScheduleBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authRepository = AuthRepository.getInstance(requireContext());
        userCacheManager = UserCacheManager.getInstance(requireContext());
        homeRepository = M03HomeRepository.getInstance();
        apiClient = FuelogBackendApiClient.getInstance(authRepository);

        setupVehicleInfo();
        setupRecyclerView();
        setupActions();

        loadPlansFromBackend();
    }

    private void setupVehicleInfo() {
        VehicleProfile profile = homeRepository.getSelectedVehicle();
        if (profile != null) {
            activeVehicleId = profile.getVehicleId();
        } else {
            AuthSession session = authRepository.getCurrentValidSession();
            if (session != null) {
                String uid = session.getUserId();
                activeVehicleId = userCacheManager.getRegisteredVehicleUuid(uid);
            }
            if (activeVehicleId == null || activeVehicleId.trim().isEmpty()) {
                activeVehicleId = M03HomeRepository.VEHICLE_ID_PRIMARY;
            }
        }

        currentOdometerKm = homeRepository.getLatestOdometerKm();
        String name = profile != null ? profile.getDisplayName() : "내 차량";
        if (currentOdometerKm > 0) {
            binding.tvM12VehicleSubtitle.setText(String.format(Locale.KOREA, "%s · 누적 %,d km", name, Math.round(currentOdometerKm)));
        } else {
            binding.tvM12VehicleSubtitle.setText(name);
        }
    }

    private void setupRecyclerView() {
        adapter = new MaintenanceScheduleAdapter(currentOdometerKm > 0 ? currentOdometerKm : null, this);
        binding.recyclerM12Plans.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerM12Plans.setAdapter(adapter);
    }

    private void setupActions() {
        binding.btnM12Back.setOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack()
        );

        binding.btnM12Refresh.setOnClickListener(v -> loadPlansFromBackend());

        binding.btnM12AddPlan.setOnClickListener(v -> showAddPlanDialog());
    }

    private void loadPlansFromBackend() {
        if (activeVehicleId == null) {
            return;
        }

        binding.progressM12Loading.setVisibility(View.VISIBLE);

        Executors.newSingleThreadExecutor().execute(() -> {
            List<FuelogBackendApiClient.MaintenancePlanItem> plans =
                    apiClient.fetchMaintenancePlans(activeVehicleId);

            // 누적거리 최신 정보 갱신
            currentOdometerKm = homeRepository.getLatestOdometerKm();

            if (isAdded() && getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    if (binding == null) return;
                    binding.progressM12Loading.setVisibility(View.GONE);

                    // 활성 계획만 필터링
                    List<FuelogBackendApiClient.MaintenancePlanItem> activePlans = new ArrayList<>();
                    int overdueCount = 0;
                    for (FuelogBackendApiClient.MaintenancePlanItem p : plans) {
                        if (p.active) {
                            activePlans.add(p);
                            if ("DANGER".equalsIgnoreCase(p.riskLevel) || "OVERDUE".equalsIgnoreCase(p.riskLevel)) {
                                overdueCount++;
                            }
                        }
                    }

                    // 홈 레포지토리 캐시에도 동기화
                    homeRepository.applyBackendMaintenancePlans(activeVehicleId, activePlans);

                    adapter.updateData(activePlans, currentOdometerKm > 0 ? currentOdometerKm : null);

                    // 요약 배너 업데이트
                    updateSummaryBanner(activePlans.size(), overdueCount);

                    if (activePlans.isEmpty()) {
                        binding.layoutM12Empty.setVisibility(View.VISIBLE);
                        binding.recyclerM12Plans.setVisibility(View.GONE);
                    } else {
                        binding.layoutM12Empty.setVisibility(View.GONE);
                        binding.recyclerM12Plans.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void updateSummaryBanner(int totalCount, int overdueCount) {
        if (totalCount == 0) {
            binding.tvM12SummaryIcon.setText("⚙️");
            binding.tvM12SummaryIcon.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.fuelog_info_bg)
            ));
            binding.tvM12SummaryTitle.setText("등록된 소모품 주기 없음");
            binding.tvM12SummaryDetail.setText("새 정비 주기를 등록해 정비 시기를 관리하세요.");
        } else if (overdueCount > 0) {
            binding.tvM12SummaryIcon.setText("⚠️");
            binding.tvM12SummaryIcon.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.fuelog_server_error_bg)
            ));
            binding.tvM12SummaryTitle.setText(String.format(Locale.KOREA, "소모품 교체 필요 (%d건)", overdueCount));
            binding.tvM12SummaryDetail.setText(String.format(Locale.KOREA, "전체 %d개 중 %d건의 교체 시기가 도래했습니다.", totalCount, overdueCount));
        } else {
            binding.tvM12SummaryIcon.setText("✅");
            binding.tvM12SummaryIcon.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.fuelog_success_bg)
            ));
            binding.tvM12SummaryTitle.setText("소모품 관리 정상");
            binding.tvM12SummaryDetail.setText(String.format(Locale.KOREA, "전체 %d개 소모품이 주기 내에서 관리되고 있습니다.", totalCount));
        }
    }

    /**
     * 신규 소모품 주기 등록 다이얼로그
     */
    private void showAddPlanDialog() {
        DialogEditMaintenanceCycleBinding dialogBinding = DialogEditMaintenanceCycleBinding.inflate(LayoutInflater.from(requireContext()));
        dialogBinding.dialogEditCycleTitle.setText("새 소모품 교체 주기 추가");
        dialogBinding.etCycleItemName.setText("");
        dialogBinding.etCycleItemName.setHint("예: 엔진오일, 와이퍼, 타이어 위치 교환");
        dialogBinding.etCycleIntervalKm.setText("10000");
        dialogBinding.etCycleIntervalMonths.setText("12");

        new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .setNegativeButton("취소", null)
                .setPositiveButton("등록", (dialog, which) -> {
                    String name = dialogBinding.etCycleItemName.getText() != null
                            ? dialogBinding.etCycleItemName.getText().toString().trim() : "";
                    if (name.isEmpty()) {
                        Toast.makeText(requireContext(), "소모품 항목명을 입력해주세요.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Double intervalKm = null;
                    try {
                        String kmStr = dialogBinding.etCycleIntervalKm.getText() != null
                                ? dialogBinding.etCycleIntervalKm.getText().toString().trim() : "";
                        if (!kmStr.isEmpty()) intervalKm = Double.parseDouble(kmStr);
                    } catch (Exception ignored) {}

                    Integer intervalMonths = null;
                    try {
                        String mStr = dialogBinding.etCycleIntervalMonths.getText() != null
                                ? dialogBinding.etCycleIntervalMonths.getText().toString().trim() : "";
                        if (!mStr.isEmpty()) intervalMonths = Integer.parseInt(mStr);
                    } catch (Exception ignored) {}

                    if (intervalKm == null && intervalMonths == null) {
                        Toast.makeText(requireContext(), "거리 주기 또는 기간 주기를 입력해주세요.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    savePlanToBackend(UUID.randomUUID().toString(), 0L, name, intervalKm, intervalMonths);
                })
                .show();
    }

    @Override
    public void onEditCycle(@NonNull FuelogBackendApiClient.MaintenancePlanItem item) {
        DialogEditMaintenanceCycleBinding dialogBinding = DialogEditMaintenanceCycleBinding.inflate(LayoutInflater.from(requireContext()));
        dialogBinding.dialogEditCycleTitle.setText(item.name + " 주기 설정");
        dialogBinding.etCycleItemName.setText(item.name);
        dialogBinding.etCycleIntervalKm.setText(item.intervalKm != null ? String.valueOf(Math.round(item.intervalKm)) : "");
        dialogBinding.etCycleIntervalMonths.setText(item.intervalMonths != null ? String.valueOf(item.intervalMonths) : "");

        new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", (dialog, which) -> {
                    String name = dialogBinding.etCycleItemName.getText() != null
                            ? dialogBinding.etCycleItemName.getText().toString().trim() : item.name;
                    Double intervalKm = null;
                    try {
                        String kmStr = dialogBinding.etCycleIntervalKm.getText() != null
                                ? dialogBinding.etCycleIntervalKm.getText().toString().trim() : "";
                        if (!kmStr.isEmpty()) intervalKm = Double.parseDouble(kmStr);
                    } catch (Exception ignored) {}

                    Integer intervalMonths = null;
                    try {
                        String mStr = dialogBinding.etCycleIntervalMonths.getText() != null
                                ? dialogBinding.etCycleIntervalMonths.getText().toString().trim() : "";
                        if (!mStr.isEmpty()) intervalMonths = Integer.parseInt(mStr);
                    } catch (Exception ignored) {}

                    savePlanToBackend(item.id, item.revision, name, intervalKm, intervalMonths);
                })
                .show();
    }

    private void savePlanToBackend(String planId, long revision, String name, Double intervalKm, Integer intervalMonths) {
        if (activeVehicleId == null) return;

        binding.progressM12Loading.setVisibility(View.VISIBLE);
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success = apiClient.putMaintenancePlan(
                    activeVehicleId, planId, revision, name, intervalKm, intervalMonths
            );
            if (isAdded() && getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(requireContext(), "교체 주기가 저장되었습니다.", Toast.LENGTH_SHORT).show();
                        loadPlansFromBackend();
                    } else {
                        binding.progressM12Loading.setVisibility(View.GONE);
                        Toast.makeText(requireContext(), "주기 저장에 실패했습니다. 다시 시도해주세요.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    @Override
    public void onCompleteMaintenance(@NonNull FuelogBackendApiClient.MaintenancePlanItem item) {
        DialogCompleteMaintenanceBinding dialogBinding = DialogCompleteMaintenanceBinding.inflate(LayoutInflater.from(requireContext()));
        dialogBinding.tvCompleteItemName.setText("대상: " + item.name);

        // 기본 정비일자: 오늘
        String todayStr = LocalDate.now().toString();
        dialogBinding.etCompleteServiceDate.setText(todayStr);

        // 기본 주행거리: 현재 누적거리 또는 다음 예정거리
        double defaultOdo = currentOdometerKm > 0 ? currentOdometerKm : (item.nextDueKm != null ? item.nextDueKm : 0.0);
        if (defaultOdo > 0) {
            dialogBinding.etCompleteOdometerKm.setText(String.valueOf(Math.round(defaultOdo)));
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .setNegativeButton("취소", null)
                .setNeutralButton("상세 등록", (dialog, which) -> {
                    Bundle args = new Bundle();
                    args.putString(M11MaintenanceEventFragment.ARG_MODE, M11MaintenanceEventFragment.MODE_CREATE);
                    args.putString(M11MaintenanceEventFragment.ARG_VEHICLE_ID, activeVehicleId);
                    args.putString(M11MaintenanceEventFragment.ARG_ITEM_NAME, item.name);
                    NavHostFragment.findNavController(this).navigate(R.id.nav_m11_maintenance_event, args);
                })
                .setPositiveButton("정비 완료 처리", (dialog, which) -> {
                    String serviceDate = dialogBinding.etCompleteServiceDate.getText() != null
                            ? dialogBinding.etCompleteServiceDate.getText().toString().trim() : todayStr;
                    if (serviceDate.isEmpty()) serviceDate = todayStr;

                    Double odoKm = null;
                    try {
                        String odoStr = dialogBinding.etCompleteOdometerKm.getText() != null
                                ? dialogBinding.etCompleteOdometerKm.getText().toString().trim() : "";
                        if (!odoStr.isEmpty()) odoKm = Double.parseDouble(odoStr);
                    } catch (Exception ignored) {}

                    long costWon = 0L;
                    try {
                        String costStr = dialogBinding.etCompleteCost.getText() != null
                                ? dialogBinding.etCompleteCost.getText().toString().trim() : "";
                        if (!costStr.isEmpty()) costWon = Long.parseLong(costStr);
                    } catch (Exception ignored) {}

                    String shopMemo = dialogBinding.etCompleteShopMemo.getText() != null
                            ? dialogBinding.etCompleteShopMemo.getText().toString().trim() : null;

                    executeCompleteMaintenance(item, serviceDate, odoKm, costWon, shopMemo);
                })
                .show();
    }

    private void executeCompleteMaintenance(
            FuelogBackendApiClient.MaintenancePlanItem item,
            String serviceDate,
            Double odoKm,
            long costWon,
            @Nullable String shopMemo
    ) {
        if (activeVehicleId == null) return;

        binding.progressM12Loading.setVisibility(View.VISIBLE);
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean completeSuccess = apiClient.completeMaintenancePlan(
                    activeVehicleId, item.id, item.revision, serviceDate, odoKm
            );

            // 서버에서 실패한 경우 (기존 레거시 시드 데이터 등 500 오류 대비 자동 복구)
            if (!completeSuccess) {
                apiClient.deleteMaintenancePlan(activeVehicleId, item.id, item.revision);
                String newPlanId = UUID.randomUUID().toString();
                boolean putOk = apiClient.putMaintenancePlan(
                        activeVehicleId, newPlanId, 0, item.name, item.intervalKm, item.intervalMonths
                );
                if (putOk) {
                    completeSuccess = apiClient.completeMaintenancePlan(
                            activeVehicleId, newPlanId, 1, serviceDate, odoKm
                    );
                }
            }

            // 차계부(M10) 지출 및 정비 기록 로컬 연동
            homeRepository.addExpenseRecord(new ExpenseRecord(
                    "maint_" + UUID.randomUUID().toString(),
                    activeVehicleId,
                    ExpenseRecord.Category.MAINTENANCE,
                    ExpenseRecord.Ownership.MY_RECORD,
                    ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                    item.name + " 교체",
                    serviceDate,
                    costWon
            ));

            if (odoKm != null && odoKm > 0) {
                double currentOdo = homeRepository.getOdometerForVehicle(activeVehicleId);
                if (odoKm > currentOdo) {
                    homeRepository.setOdometerForVehicle(activeVehicleId, odoKm);
                }
            }

            // 정비 비용 또는 메모가 입력되었으면 백엔드 정비 이력 API도 함께 호출하여 차계부 연동
            if (costWon > 0 || (shopMemo != null && !shopMemo.isEmpty())) {
                apiClient.createMaintenanceEvent(
                        activeVehicleId, item.name, serviceDate, odoKm, costWon,
                        shopMemo, "정비 완료 확인 자동 기록"
                );
            }

            if (isAdded() && getActivity() != null) {
                final boolean finalSuccess = completeSuccess;
                requireActivity().runOnUiThread(() -> {
                    if (finalSuccess) {
                        Toast.makeText(requireContext(), "정비가 완료 처리되고 다음 주기가 시작되었습니다.", Toast.LENGTH_SHORT).show();
                        loadPlansFromBackend();
                    } else {
                        binding.progressM12Loading.setVisibility(View.GONE);
                        Toast.makeText(requireContext(), "정비 완료 처리에 실패했습니다. 다시 시도해주세요.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    @Override
    public void onDeletePlan(@NonNull FuelogBackendApiClient.MaintenancePlanItem item) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("소모품 주기 비활성화")
                .setMessage(item.name + " 교체 주기를 삭제하시겠습니까?\n기존 정비 이력은 유지되며 다음 예정 알림에서 제외됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    if (activeVehicleId == null) return;
                    binding.progressM12Loading.setVisibility(View.VISIBLE);
                    Executors.newSingleThreadExecutor().execute(() -> {
                        boolean ok = apiClient.deleteMaintenancePlan(activeVehicleId, item.id, item.revision);
                        if (isAdded() && getActivity() != null) {
                            requireActivity().runOnUiThread(() -> {
                                if (ok) {
                                    Toast.makeText(requireContext(), "소모품 주기가 비활성화되었습니다.", Toast.LENGTH_SHORT).show();
                                    loadPlansFromBackend();
                                } else {
                                    binding.progressM12Loading.setVisibility(View.GONE);
                                    Toast.makeText(requireContext(), "비활성화에 실패했습니다.", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    });
                })
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
