package com.pleos.mobile_app_fuelog.ui.main;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
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
import com.pleos.mobile_app_fuelog.ui.auth.M01LoginFragment;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        homeRepository = new M03HomeRepository();

        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            view.post(this::navigateBackToM01);
            return;
        }

        String userRegisteredVehicle = userCacheManager.getVisibleCachedVehicleName();
        homeRepository.seedDefaultData(userRegisteredVehicle);

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
    }

    private void setupHeaderActions() {
        // 우측 상단 [⇄ 변경] 버튼: 차량 전환(이전 차량 데이터 격리), 신규 사용자 빈 상태, 로그아웃 등 선택
        binding.btnM03ChangeVehicle.setOnClickListener(v -> showVehicleSwitcherDialog());

        // 동기화 배지 탭 시 온라인/오프라인(마지막 갱신 시각) 전환
        binding.tvM03SyncStatus.setOnClickListener(v -> {
            homeRepository.setOfflineMode(!homeRepository.isOfflineMode());
            renderSyncStatusHeader();
        });

        binding.btnM03EmptyRegisterVehicle.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.nav_m02_no_vehicle)
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
                Snackbar.make(binding.getRoot(),
                        "주유·정비 지출 기록 화면(M07)으로 연결됩니다.",
                        Snackbar.LENGTH_SHORT).show()
        );
    }

    private void showVehicleSwitcherDialog() {
        String primaryLabel = homeRepository.getVehicles().isEmpty()
                ? "12가 3456 (휘발유)"
                : homeRepository.getVehicles().get(0).getDisplayName();

        String[] options = new String[]{
                "🚗 " + primaryLabel + " (메인 차량)",
                "🚙 88하 9012 (경유 · 세컨드카)",
                "➕ 새 차량 추가 등록 (M02)",
                "🆕 신규 사용자 빈 상태 보기",
                "🚪 로그아웃 (다른 계정으로 전환)"
        };

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("차량 전환 및 관리")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        homeRepository.setNewUserEmptyMode(false);
                        homeRepository.selectVehicle(M03HomeRepository.VEHICLE_ID_PRIMARY);
                        renderDashboard();
                    } else if (which == 1) {
                        homeRepository.setNewUserEmptyMode(false);
                        homeRepository.selectVehicle(M03HomeRepository.VEHICLE_ID_SECONDARY);
                        renderDashboard();
                    } else if (which == 2) {
                        NavHostFragment.findNavController(this).navigate(R.id.nav_m02_no_vehicle);
                    } else if (which == 3) {
                        homeRepository.setNewUserEmptyMode(true);
                        renderDashboard();
                    } else if (which == 4) {
                        authRepository.signOut();
                        navigateBackToM01();
                    }
                })
                .show();
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
        binding.containerConfirmedExpenses.removeAllViews();
        binding.containerRecentTrips.removeAllViews();
        binding.containerMaintenanceSchedule.removeAllViews();

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
        if (M03HomeRepository.VEHICLE_ID_SECONDARY.equals(selectedVehicle.getVehicleId())) {
            binding.tvM03VehicleSubinfo.setText("경유 · 개인 전용 차량");
            binding.tvM03OdometerValue.setText("78,910 km");
        } else {
            binding.tvM03VehicleSubinfo.setText(selectedVehicle.getFuelType() + " · 공동 관리 차량");
            binding.tvM03OdometerValue.setText("42,350 km");
        }

        renderPendingTasksSection();
        renderMonthlyExpensesSection();
        renderMaintenanceSection();
        renderRecentTripsSection();
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
        PendingTaskItem.TaskDestination dest = task.getDestination();
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
                        + " (" + summary.getConfirmedCount() + "건 확정)"
        );

        long manWon = Math.round(summary.getConfirmedTotalWon() / 10000.0);
        binding.tvM03ChartCurrentLabel.setText(manWon + "만");

        String calloutText = "미확인 추정 " + summary.getUnconfirmedCount() + "건("
                + formatWon(summary.getUnconfirmedEstimatedWon()) + ") 별도 집계\n"
                + "운행 소비 비용(" + formatWon(summary.getExcludedTripConsumptionWon()) + ")은 중복 합산하지 않아요";
        binding.tvM03UnconfirmedAndExclusionNote.setText(calloutText);

        List<ExpenseRecord> confirmedList = homeRepository.getConfirmedExpensesForSelectedVehicle();
        for (ExpenseRecord record : confirmedList) {
            String tag = record.getOwnership() == ExpenseRecord.Ownership.EXPLICITLY_SHARED
                    ? "공동"
                    : "개인";
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dpToPx(6), 0, dpToPx(6));

            TextView left = new TextView(requireContext());
            left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            left.setText(record.getDateLabel() + " · [" + tag + "] " + record.getTitle());
            left.setTextSize(12.5f);
            left.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_secondary));

            TextView right = new TextView(requireContext());
            right.setText(formatWon(record.getAmountWon()));
            right.setTextSize(12.5f);
            right.setTypeface(null, Typeface.BOLD);
            right.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_primary));

            row.addView(left);
            row.addView(right);
            binding.containerConfirmedExpenses.addView(row);
        }
    }

    /**
     * 3. 소모품 관리 / 정비 예정 (차봇 우측 폰 스타일 수평 게이지 바)
     */
    private void renderMaintenanceSection() {
        List<MaintenanceScheduleItem> items = homeRepository.getMaintenanceSchedulesForSelectedVehicle();
        binding.tvM03MaintenanceHeaderCount.setText("소모품 관리 " + items.size());

        if (items.isEmpty()) {
            binding.containerMaintenanceSchedule.addView(createEmptyRowText("예정된 정비 항목이 없습니다."));
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            MaintenanceScheduleItem item = items.get(i);

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dpToPx(10), 0, dpToPx(10));

            // 좌측: 소모품명 + 상세
            LinearLayout leftCol = new LinearLayout(requireContext());
            leftCol.setOrientation(LinearLayout.VERTICAL);
            leftCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));

            TextView title = new TextView(requireContext());
            title.setText(item.getItemTitle());
            title.setTextSize(14f);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_primary));

            TextView detail = new TextView(requireContext());
            detail.setText(item.getDueDetail());
            detail.setTextSize(11.5f);
            detail.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_secondary));

            leftCol.addView(title);
            leftCol.addView(detail);

            // 우측: 게이지 바 + 남은 거리/일자
            LinearLayout rightCol = new LinearLayout(requireContext());
            rightCol.setOrientation(LinearLayout.VERTICAL);
            rightCol.setGravity(Gravity.END);
            rightCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));

            ProgressBar bar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dpToPx(110), dpToPx(8));
            bar.setLayoutParams(barParams);
            bar.setMax(100);
            bar.setProgress(item.isUrgent() ? 88 : 55);
            int barColor = ContextCompat.getColor(
                    requireContext(),
                    item.isUrgent() ? R.color.fuelog_server_error_border : R.color.fuelog_primary
            );
            bar.setProgressTintList(ColorStateList.valueOf(barColor));

            TextView badge = new TextView(requireContext());
            badge.setText(item.getRemainingBadge());
            badge.setTextSize(11.5f);
            badge.setTextColor(ContextCompat.getColor(
                    requireContext(),
                    item.isUrgent() ? R.color.fuelog_server_error_text : R.color.fuelog_text_secondary
            ));

            rightCol.addView(bar);
            rightCol.addView(badge);

            row.addView(leftCol);
            row.addView(rightCol);
            binding.containerMaintenanceSchedule.addView(row);

            if (i < items.size() - 1) {
                binding.containerMaintenanceSchedule.addView(createDivider());
            }
        }
    }

    /**
     * 4. 최근 운행
     */
    private void renderRecentTripsSection() {
        List<TripRecord> trips = homeRepository.getRecentTripsForSelectedVehicle();
        if (trips.isEmpty()) {
            binding.containerRecentTrips.addView(createEmptyRowText("표시할 최근 운행 기록이 없습니다."));
            return;
        }

        for (int i = 0; i < trips.size(); i++) {
            TripRecord trip = trips.get(i);

            LinearLayout col = new LinearLayout(requireContext());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(0, dpToPx(8), 0, dpToPx(8));

            TextView route = new TextView(requireContext());
            route.setText(trip.getRouteSummary() + "  (" + String.format(Locale.KOREA, "%.1fkm", trip.getDistanceKm()) + ")");
            route.setTextSize(14f);
            route.setTypeface(null, Typeface.BOLD);
            route.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_primary));

            TextView meta = new TextView(requireContext());
            meta.setText(trip.getDateTimeLabel() + " · " + trip.getDurationMinutes() + "분 · " + trip.getAttributionBadge());
            meta.setTextSize(12f);
            meta.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_secondary));

            col.addView(route);
            col.addView(meta);
            binding.containerRecentTrips.addView(col);

            if (i < trips.size() - 1) {
                binding.containerRecentTrips.addView(createDivider());
            }
        }
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
