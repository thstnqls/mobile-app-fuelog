package com.pleos.mobile_app_fuelog.ui.ledger;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

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
            new Thread(() -> {
                try {
                    FuelogBackendApiClient apiClient = FuelogBackendApiClient.getInstance(authRepository);
                    List<FuelogBackendApiClient.BackendVehicleItem> backendVehicles = apiClient.fetchMobileVehicles();
                    if (backendVehicles != null && !backendVehicles.isEmpty()) {
                        homeRepository.applyBackendUserVehicles(backendVehicles);
                    }
                } catch (Exception ignored) {
                }
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(this::renderLedgerData);
                }
            }).start();
        } else {
            renderLedgerData();
        }
    }

    private void setupViews() {
        // 차량명 라벨 탭 시 차량 변경 다이얼로그 (다차량 보유 시)
        binding.textLedgerVehicleName.setOnClickListener(v -> {
            List<VehicleProfile> vehicles = homeRepository.getVehicles();
            if (vehicles.size() > 1) {
                String[] names = new String[vehicles.size()];
                int checkedItem = 0;
                VehicleProfile cur = homeRepository.getSelectedVehicle();
                for (int i = 0; i < vehicles.size(); i++) {
                    names[i] = vehicles.get(i).getDisplayName();
                    if (cur != null && vehicles.get(i).getVehicleId().equals(cur.getVehicleId())) {
                        checkedItem = i;
                    }
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("차계부 차량 선택")
                        .setSingleChoiceItems(names, checkedItem, (dialog, which) -> {
                            VehicleProfile picked = vehicles.get(which);
                            homeRepository.selectVehicle(picked.getVehicleId());
                            AuthSession session = authRepository.getCurrentValidSession();
                            if (session != null) {
                                userCacheManager.setRegisteredVehicleForUser(session.getUserId(), picked.getDisplayName());
                            }
                            renderLedgerData();
                            dialog.dismiss();
                        })
                        .setNegativeButton("닫기", null)
                        .show();
            }
        });

        // 빠른 등록 버튼 -> M07 수동 등록 모드
        binding.btnQuickAddExpense.setOnClickListener(v -> {
            NavHostFragment.findNavController(this).navigate(R.id.nav_m07_fuel_confirm);
        });

        // 감지된 주유 확인 배너 -> M07 자동 감지 확인 모드
        binding.cardPendingFuelBanner.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putString(M07FuelConfirmFragment.ARG_CANDIDATE_ID, "cand-auto-detected-01");
            NavHostFragment.findNavController(this).navigate(R.id.nav_m07_fuel_confirm, args);
        });
    }

    private void setupRecyclerView() {
        adapter = new ExpenseListAdapter(record -> {
            Snackbar.make(binding.getRoot(),
                    record.getTitle() + " (" + NumberFormat.getNumberInstance(Locale.KOREA).format(record.getAmountWon()) + "원)",
                    Snackbar.LENGTH_SHORT).show();
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
            String cachedVehicleName = userCacheManager.getVisibleCachedVehicleName();
            if (cachedVehicleName != null && !cachedVehicleName.isEmpty()) {
                binding.textLedgerVehicleName.setText(cachedVehicleName);
            } else {
                binding.textLedgerVehicleName.setText("플레오스 연동 차량");
            }
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
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
