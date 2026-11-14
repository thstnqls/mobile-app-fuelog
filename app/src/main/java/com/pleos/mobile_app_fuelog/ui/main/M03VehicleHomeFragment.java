package com.pleos.mobile_app_fuelog.ui.main;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.pleos.mobile_app_fuelog.MainActivity;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.core.permission.OptionalPermissionType;
import com.pleos.mobile_app_fuelog.core.permission.PermissionGuideHelper;
import com.pleos.mobile_app_fuelog.databinding.FragmentM03VehicleHomeBinding;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.home.ExpenseRecord;
import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.home.MaintenanceScheduleItem;
import com.pleos.mobile_app_fuelog.data.home.MonthlyExpenseSummary;
import com.pleos.mobile_app_fuelog.data.home.PendingTaskItem;
import com.pleos.mobile_app_fuelog.data.home.TripRecord;
import com.pleos.mobile_app_fuelog.data.home.VehicleProfile;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.ui.auth.M01LoginFragment;
import com.pleos.mobile_app_fuelog.ui.vehicle.VehicleRegisterActivity;
import com.pleos.mobile_app_fuelog.ui.vehicle.VehicleSwitcherBottomSheetHelper;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * M03. 홈 화면 Fragment (차봇 스타일 모던 차량관리 UI).
 * - 상단 차량 헤더 (차량 번호, 유종, 누적 주행거리, 차량 변경 버튼, 오프라인 마지막 갱신 시각)
 * - 1. 확인할 기록 (FR-M03-1: 미완료 작업 최상단 배치 -> 탭 시 M07/M04/M09/M13 이동)
 * - 2. 이번달 차계부 (FR-M03-2, FR-M03-3: 본인+명시적 공유 집계 범위, 확인된 거래만 합산, 미확인 추정 별도, 운행 소비 비용 제외)
 * - 3. 소모품 관리 (정비 예정 게이지 바)
 * - 4. 최근 운행
 */
public class M03VehicleHomeFragment extends Fragment {

    private FragmentM03VehicleHomeBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private M03HomeRepository homeRepository;
    private final NumberFormat currencyFormat = NumberFormat.getNumberInstance(Locale.KOREA);

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);

    private final ActivityResultLauncher<Intent> registerVehicleLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    syncInboxFromBackend();
                }
            });

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentM03VehicleHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authRepository = AuthRepository.getInstance(requireContext());
        userCacheManager = UserCacheManager.getInstance(requireContext());
        homeRepository = M03HomeRepository.getInstance();

        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            view.post(this::navigateBackToM01);
            return;
        }

        String userRegisteredVehicle = userCacheManager.getVisibleCachedVehicleName();
        String userVehicleUuid = userCacheManager.getRegisteredVehicleUuid(session.getUserId());
        homeRepository.clearAllAndInitVehicle(userVehicleUuid, userRegisteredVehicle);

        setupHeaderActions();
        setupExpenseCardActions();

        Bundle args = getArguments();
        String promptPermissionKey = args != null ? args.getString(M01LoginFragment.ARG_PROMPT_PERMISSION_KEY) : null;
        if (promptPermissionKey != null) {
            args.remove(M01LoginFragment.ARG_PROMPT_PERMISSION_KEY);
            OptionalPermissionType permType = OptionalPermissionType.fromKey(promptPermissionKey);
            if (permType != null) {
                requestFeaturePermissionOnlyIfMissing(permType);
            }
        }

        renderDashboard();
        syncInboxFromBackend();
    }

    private void syncInboxFromBackend() {
        Executors.newSingleThreadExecutor().execute(() -> {
            AuthSession session = authRepository.getCurrentValidSession();
            if (session == null) {
                return;
            }
            FuelogBackendApiClient apiClient = FuelogBackendApiClient.getInstance(authRepository);
            String userId = session.getUserId();
            String instId = userCacheManager.getInstallationId(userId);
            String instSecret = userCacheManager.getInstallationSecret(userId);

            // 0) 백엔드 사용자의 전체 차량 목록(GET /mobile/vehicles) 조회 및 반영
            List<FuelogBackendApiClient.BackendVehicleItem> backendVehicles = apiClient.fetchMobileVehicles();
            if (!backendVehicles.isEmpty()) {
                for (FuelogBackendApiClient.BackendVehicleItem bv : backendVehicles) {
                    String vid = bv.vehicleId != null ? bv.vehicleId : bv.id;
                    userCacheManager.setServerVehicleName(vid, bv.getDisplayName());
                }
                homeRepository.applyBackendUserVehicles(backendVehicles, userCacheManager::getEffectiveVehicleName);
                String currentSelected = homeRepository.getSelectedVehicle() != null
                        ? homeRepository.getSelectedVehicle().getVehicleId() : null;
                if (currentSelected == null || !homeRepository.hasVehicle(currentSelected)) {
                    String preferredVid = userCacheManager.getRegisteredVehicleUuid(userId);
                    if (preferredVid != null && homeRepository.hasVehicle(preferredVid)) {
                        homeRepository.selectVehicle(preferredVid);
                    }
                }
            }

            // 1) 백엔드 차량 홈(GET /vehicle/home) 및 동기화 상태(GET /sync/status) 조회
            // 주의: 백그라운드 동기화 중에는 신규 차량이나 단말을 등록/생성하지 않으며, 이미 연결된 설치 자격증명이 있을 때만 안전하게 상태를 조회한다.
            if (instId != null && instSecret != null) {
                FuelogBackendApiClient.InstallationMeResponse instMe = apiClient.fetchInstallationMe(instId, instSecret);
                if (instMe != null && instMe.paired && instMe.vehicleId != null) {
                    FuelogBackendApiClient.VehicleHomeResponse homeResp =
                            apiClient.fetchVehicleHome(instSecret, userCacheManager.getActiveScopedToken());
                    FuelogBackendApiClient.SyncStatusResponse syncStatusResp =
                            apiClient.fetchSyncStatus(instSecret);
                    if (homeResp.success && homeResp.vehicleAlias != null && !homeResp.vehicleAlias.trim().isEmpty()) {
                        String currentCached = userCacheManager.getVisibleCachedVehicleName();
                        if (currentCached == null || currentCached.isEmpty()) {
                            userCacheManager.setRegisteredVehicleForUser(userId, homeResp.vehicleAlias.trim());
                        }
                    }
                    homeRepository.applyBackendVehicleHomeAndSync(instMe.vehicleId, homeResp, syncStatusResp);
                }
            }

            // 2) 백엔드 M06 모바일 확인함(GET /mobile/inbox) 실제 DB 데이터 조회
            FuelogBackendApiClient.InboxPageResponse page = apiClient.fetchMobileInbox(0L);
            if (page.success && !page.items.isEmpty()) {
                homeRepository.applyBackendInboxItems(page.items);
            }

            // 3) 백엔드 M12 정비 예정 계획(GET /mobile/vehicles/{vehicleId}/maintenance-plans) 실제 DB 데이터 조회
            VehicleProfile selectedVeh = homeRepository.getSelectedVehicle();
            if (selectedVeh != null) {
                List<FuelogBackendApiClient.MaintenancePlanItem> plans =
                        apiClient.fetchMaintenancePlans(selectedVeh.getVehicleId());
                if (!plans.isEmpty()) {
                    homeRepository.applyBackendMaintenancePlans(selectedVeh.getVehicleId(), plans);
                }
            }
            if (isAdded() && binding != null) {
                requireActivity().runOnUiThread(this::renderDashboard);
            }
        });
    }

    private void setupHeaderActions() {
        // 차량 변경 버튼 및 카드 영역 탭 시 차량 전환 바텀시트 열기
        binding.btnM03ChangeVehicle.setOnClickListener(v -> showVehicleSwitcherDialog());
        binding.btnM03SwitchVehicle.setOnClickListener(v -> showVehicleSwitcherDialog());
        binding.cardM03VehicleSwipeArea.setOnClickListener(v -> showVehicleSwitcherDialog());
        binding.layoutM03VehicleHeaderInfo.setOnClickListener(v -> showVehicleSwitcherDialog());

        // 동기화 배지 탭 시 온라인(백엔드 재동기화)/오프라인(마지막 갱신 시각) 전환
        binding.tvM03SyncStatus.setOnClickListener(v -> {
            boolean nextOffline = !homeRepository.isOfflineMode();
            homeRepository.setOfflineMode(nextOffline);
            renderSyncStatusHeader();
            if (!nextOffline) {
                syncInboxFromBackend();
            }
        });

        binding.btnM03EmptyRegisterVehicle.setOnClickListener(v ->
                registerVehicleLauncher.launch(new Intent(requireContext(), VehicleRegisterActivity.class))
        );
    }

    private void setupExpenseCardActions() {
        // FR-M03-2: 개인+공동 지출 vs 개인 지출만 토글
        binding.tvM03ScopeBadge.setOnClickListener(v -> {
            if (homeRepository.getCurrentScopeMode() == MonthlyExpenseSummary.ScopeMode.PERSONAL_AND_SHARED) {
                homeRepository.setCurrentScopeMode(MonthlyExpenseSummary.ScopeMode.PERSONAL_ONLY);
            } else {
                homeRepository.setCurrentScopeMode(MonthlyExpenseSummary.ScopeMode.PERSONAL_AND_SHARED);
            }
            renderDashboard();
        });

        binding.btnM03AddExpense.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.nav_m07_fuel_confirm)
        );
    }

    private void showVehicleSwitcherDialog() {
        List<VehicleProfile> vehicles = homeRepository.getVehicles();
        if (vehicles.isEmpty()) {
            Snackbar.make(binding.getRoot(), "차량 목록을 불러오는 중입니다...", Snackbar.LENGTH_SHORT).show();
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    AuthSession session = authRepository.getCurrentValidSession();
                    if (session != null) {
                        FuelogBackendApiClient apiClient = FuelogBackendApiClient.getInstance(authRepository);
                        List<FuelogBackendApiClient.BackendVehicleItem> backendVehicles = apiClient.fetchMobileVehicles();
                        if (backendVehicles != null && !backendVehicles.isEmpty()) {
                            for (FuelogBackendApiClient.BackendVehicleItem bv : backendVehicles) {
                                String vid = bv.vehicleId != null ? bv.vehicleId : bv.id;
                                userCacheManager.setServerVehicleName(vid, bv.getDisplayName());
                            }
                            homeRepository.applyBackendUserVehicles(backendVehicles, userCacheManager::getEffectiveVehicleName);
                        }
                    }
                } catch (Exception ignored) {
                }
                if (isAdded() && getActivity() != null) {
                    requireActivity().runOnUiThread(() -> {
                        List<VehicleProfile> refreshed = homeRepository.getVehicles();
                        if (refreshed.isEmpty()) {
                            registerVehicleLauncher.launch(new Intent(requireContext(), VehicleRegisterActivity.class));
                        } else {
                            String currentVid = homeRepository.getSelectedVehicle() != null
                                    ? homeRepository.getSelectedVehicle().getVehicleId()
                                    : "";
                            VehicleSwitcherBottomSheetHelper.show(requireContext(), refreshed, currentVid, this::onVehicleSelected);
                        }
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
        homeRepository.setNewUserEmptyMode(false);
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

        renderDashboard();
        Snackbar.make(binding.getRoot(),
                "'" + selected.getDisplayName() + "' 차량으로 전환되었습니다.",
                Snackbar.LENGTH_SHORT).show();

        // 차량 전환 시 해당 차량의 데이터로 재동기화
        syncInboxFromBackend();
    }

    private void renderSyncStatusHeader() {
        if (homeRepository.isOfflineMode()) {
            binding.tvM03SyncStatus.setText("오프라인 · " + homeRepository.getLastSyncedTimestamp());
            binding.tvM03SyncStatus.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.fuelog_input_error_bg)
            );
            binding.tvM03SyncStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.fuelog_input_error_text)
            );
        } else {
            binding.tvM03SyncStatus.setText("최근 갱신 " + homeRepository.getLastSyncedTimestamp());
            binding.tvM03SyncStatus.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.fuelog_info_bg)
            );
            binding.tvM03SyncStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.fuelog_text_secondary)
            );
        }
    }

    private void renderDashboard() {
        if (binding == null) {
            return;
        }
        renderSyncStatusHeader();

        // 차량 전환 시 이전 차량 데이터가 남아 섞이지 않도록 모든 컨테이너 초기화
        binding.containerPendingTasks.removeAllViews();

        VehicleProfile selectedVehicle = homeRepository.getSelectedVehicle();
        if (selectedVehicle == null || homeRepository.isNewUserEmptyMode()) {
            binding.layoutM03VehicleHeaderInfo.setVisibility(View.GONE);
            binding.cardM03OdometerBar.setVisibility(View.GONE);
            binding.cardM03EmptyState.setVisibility(View.VISIBLE);
            binding.layoutM03DashboardContent.setVisibility(View.GONE);
            return;
        }

        binding.layoutM03VehicleHeaderInfo.setVisibility(View.VISIBLE);
        binding.cardM03OdometerBar.setVisibility(View.VISIBLE);
        binding.cardM03EmptyState.setVisibility(View.GONE);
        binding.layoutM03DashboardContent.setVisibility(View.VISIBLE);

        binding.tvM03PlateNumber.setText(selectedVehicle.getDisplayName());
        binding.tvM03VehicleSubinfo.setText(
                selectedVehicle.getFuelType()
                        + (selectedVehicle.isSharedVehicle() ? " · 공동 관리 차량" : " · 개인 전용 차량")
        );
        double odoKm = homeRepository.getLatestOdometerKm();
        if (odoKm > 0.0) {
            binding.tvM03OdometerValue.setText(String.format(Locale.KOREA, "%,.1f km", odoKm));
        } else {
            binding.tvM03OdometerValue.setText("최신 거리 확인 필요");
        }

        renderMonthlyExpensesSection();
        renderPendingTasksSection();
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).updateBottomNavBadges();
        }
    }

    /**
     * 1. 확인할 기록 (FR-M03-1: 주유 확인 M07 / 운행 귀속 M04 / 정산 변경 M09 / 공동 관리 M13)
     */
    private void renderPendingTasksSection() {
        List<PendingTaskItem> tasks = homeRepository.getPendingTasksForSelectedVehicle();
        binding.tvM03PendingCountBadge.setText(tasks.size() + "건");

        if (tasks.isEmpty()) {
            binding.containerPendingTasks.addView(createEmptyRowText("확인할 미완료 기록이 없습니다."));
            return;
        }

        for (int i = 0; i < tasks.size(); i++) {
            PendingTaskItem task = tasks.get(i);

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dpToPx(10), 0, dpToPx(10));
            row.setClickable(true);
            row.setFocusable(true);

            LinearLayout textCol = new LinearLayout(requireContext());
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            );
            textCol.setLayoutParams(colParams);

            TextView titleView = new TextView(requireContext());
            titleView.setText("[" + task.getDestination().getBadgeLabel() + "] " + task.getTitle());
            titleView.setTextSize(14f);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_primary));

            TextView subView = new TextView(requireContext());
            subView.setText(task.getSubtitle());
            subView.setTextSize(12f);
            subView.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_secondary));

            textCol.addView(titleView);
            textCol.addView(subView);

            TextView arrowView = new TextView(requireContext());
            arrowView.setText(task.getDestination().getScreenCode() + " ›");
            arrowView.setTextSize(13f);
            arrowView.setTypeface(null, Typeface.BOLD);
            arrowView.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_primary));

            row.addView(textCol);
            row.addView(arrowView);
            row.setOnClickListener(v -> onPendingTaskClicked(task));

            binding.containerPendingTasks.addView(row);
            if (i < tasks.size() - 1) {
                binding.containerPendingTasks.addView(createDivider());
            }
        }
    }

    private void onPendingTaskClicked(@NonNull PendingTaskItem task) {
        if (task.getTaskId().startsWith("inbox_")) {
            try {
                long inboxItemId = Long.parseLong(task.getTaskId().substring("inbox_".length()));
                homeRepository.markInboxTaskReadLocally(inboxItemId);
                Executors.newSingleThreadExecutor().execute(() ->
                        FuelogBackendApiClient.getInstance(authRepository).markInboxItemAsRead(inboxItemId)
                );
            } catch (NumberFormatException ignored) {
            }
        }
        PendingTaskItem.TaskDestination dest = task.getDestination();
        if (dest == PendingTaskItem.TaskDestination.M04_TRIP_ATTRIBUTION) {
            NavHostFragment.findNavController(this).navigate(R.id.nav_m04_driver_connection);
            return;
        }
        if (dest == PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM) {
            Bundle args = new Bundle();
            args.putString(com.pleos.mobile_app_fuelog.ui.ledger.M07FuelConfirmFragment.ARG_CANDIDATE_ID, "cand-" + task.getTaskId());
            NavHostFragment.findNavController(this).navigate(R.id.nav_m07_fuel_confirm, args);
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(dest.getScreenCode() + " · " + dest.getBadgeLabel())
                .setMessage(task.getTitle() + "\n\n" + task.getSubtitle())
                .setPositiveButton(dest.getScreenCode() + " 이동", (d, w) ->
                        Snackbar.make(binding.getRoot(),
                                dest.getScreenCode() + "(" + dest.getBadgeLabel() + ") 화면으로 이동합니다.",
                                Snackbar.LENGTH_SHORT).show()
                )
                .setNegativeButton("닫기", null)
                .show();
    }

    /**
     * 2. 이번달 차계부 (FR-M03-2, FR-M03-3)
     */
    private void renderMonthlyExpensesSection() {
        MonthlyExpenseSummary summary = homeRepository.getMonthlyExpenseSummaryForSelectedVehicle();

        if (summary.getScopeMode() == MonthlyExpenseSummary.ScopeMode.PERSONAL_AND_SHARED) {
            binding.tvM03ScopeBadge.setText("개인+공동 지출 ▾");
        } else {
            binding.tvM03ScopeBadge.setText("개인 지출만 ▾");
        }

        binding.tvM03ConfirmedTotal.setText(formatWon(summary.getConfirmedTotalWon()));
        binding.tvM03ConfirmedBreakdown.setText(
                "주유 " + formatWon(summary.getConfirmedFuelWon())
                        + " · 정비 " + formatWon(summary.getConfirmedMaintenanceWon())
        );

        long manWon = Math.round(summary.getConfirmedTotalWon() / 10000.0);
        binding.tvM03ChartCurrentLabel.setText(manWon + "만");
    }

    private View createDivider() {
        View divider = new View(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)
        );
        divider.setLayoutParams(params);
        divider.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.fuelog_border));
        return divider;
    }

    private View createEmptyRowText(@NonNull String message) {
        TextView tv = new TextView(requireContext());
        int pad = dpToPx(8);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_secondary));
        tv.setTextSize(12f);
        tv.setText(message);
        return tv;
    }

    @NonNull
    private String formatWon(long amountWon) {
        return currencyFormat.format(amountWon) + "원";
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    public void requestFeaturePermissionOnlyIfMissing(@NonNull OptionalPermissionType permissionType) {
        PermissionGuideHelper.executeOrRequestWhenMissing(
                requireContext(),
                permissionType,
                () -> {
                    if (binding != null) {
                        Snackbar.make(binding.getRoot(),
                                permissionType.getFeatureName() + " 기능을 시작합니다.",
                                Snackbar.LENGTH_SHORT).show();
                    }
                },
                new PermissionGuideHelper.PermissionActionCallback() {
                    @Override
                    public void onProceedToSystemRequest(@NonNull OptionalPermissionType type) {
                        permissionLauncher.launch(type.getAndroidPermissions());
                    }

                    @Override
                    public void onContinueWithoutPermission(@NonNull OptionalPermissionType type) {
                        if (binding != null) {
                            Snackbar.make(binding.getRoot(),
                                    type.getTitle() + "을(를) 허용하지 않고 기본 기능으로 계속 이용합니다.",
                                    Snackbar.LENGTH_LONG).show();
                        }
                    }
                }
        );
    }

    private void onPermissionsResult(@NonNull Map<String, Boolean> result) {
        if (binding == null) {
            return;
        }
        boolean anyGranted = false;
        for (Boolean granted : result.values()) {
            if (Boolean.TRUE.equals(granted)) {
                anyGranted = true;
                break;
            }
        }
        String message = anyGranted
                ? "권한이 허용되어 해당 기능을 시작합니다."
                : "권한이 거부되었으나 기본 기능은 정상 동작합니다.";
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG).show();
    }

    private void navigateBackToM01() {
        if (!isAdded()) {
            return;
        }
        NavController navController = NavHostFragment.findNavController(this);
        navController.navigate(R.id.nav_m01_login);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
