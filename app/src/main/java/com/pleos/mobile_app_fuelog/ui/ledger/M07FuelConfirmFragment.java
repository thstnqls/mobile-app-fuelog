package com.pleos.mobile_app_fuelog.ui.ledger;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.home.ExpenseRecord;
import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.databinding.FragmentM07FuelConfirmBinding;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * M07. 주유 확인·보정·수동 등록 화면.
 * - 자동 감지 모드: 차량 F01 연료 센서가 감지한 원본(추정량·단가)을 보존하고 실제 결제 금액 대조 및 CONFIRM/REJECT 수행.
 * - 수동 등록 모드: 사용자가 직접 주유 금액·주유량을 차계부에 등록.
 * - FR-M07-1: 자동 원본 보존 및 수정값·버전 구분.
 * - FR-M07-2: 금액만 입력해도 확인 거래로 저장 가능 (주유량 미확인 허용).
 * - FR-M07-3: 금액·양·단가 불일치 시 할인/반올림 안내 표시 및 사용자 입력 실제 금액 우선 보존.
 * - FR-M07-4: 오피넷 후보 및 직접 입력 지원.
 * - FR-M07-5: '주유 아님' 후보 기각 지원.
 */
public class M07FuelConfirmFragment extends Fragment {

    public static final String ARG_CANDIDATE_ID = "arg_candidate_id";
    public static final String ARG_INITIAL_AMOUNT = "arg_initial_amount";
    public static final String ARG_INITIAL_LITERS = "arg_initial_liters";

    private FragmentM07FuelConfirmBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private M03HomeRepository homeRepository;
    private FuelogBackendApiClient apiClient;

    @Nullable
    private String candidateId;
    private long candidateRevision = 1L;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentM07FuelConfirmBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authRepository = AuthRepository.getInstance(requireContext());
        userCacheManager = UserCacheManager.getInstance(requireContext());
        homeRepository = M03HomeRepository.getInstance();
        apiClient = FuelogBackendApiClient.getInstance(authRepository);

        if (getArguments() != null) {
            candidateId = getArguments().getString(ARG_CANDIDATE_ID);
        }

        setupViews();
        setupInputWatchers();

        if (candidateId != null && !candidateId.isEmpty()) {
            loadCandidateDetails(candidateId);
        } else {
            setupManualModeDefaults();
        }
    }

    private void setupViews() {
        binding.btnBack.setOnClickListener(v -> NavHostFragment.findNavController(this).popBackStack());

        // 주유소 선택 라디오 버튼
        binding.rgStationCandidates.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_station_manual) {
                binding.etStationManualInput.setVisibility(View.VISIBLE);
                binding.etStationManualInput.requestFocus();
            } else {
                binding.etStationManualInput.setVisibility(View.GONE);
            }
        });

        // 확인 저장 / 지출 등록 버튼
        binding.btnSaveFuelRecord.setOnClickListener(v -> handleSave());

        // 주유 아님 (기각) 버튼
        binding.btnRejectFuel.setOnClickListener(v -> handleReject());
    }

    private void setupManualModeDefaults() {
        binding.textScreenTitle.setText("주유 기록 등록");
        binding.cardAutoDetectedFuel.setVisibility(View.GONE);
        binding.btnRejectFuel.setVisibility(View.GONE);
        binding.btnSaveFuelRecord.setText("지출 저장");

        binding.etActualAmount.setText("50,000");
        binding.etActualLiters.setText("29.4");
        binding.etUnitPrice.setText("1,700");
    }

    private void loadCandidateDetails(@NonNull String cId) {
        binding.textScreenTitle.setText("감지된 주유 확인");
        binding.cardAutoDetectedFuel.setVisibility(View.VISIBLE);
        binding.btnRejectFuel.setVisibility(View.VISIBLE);
        binding.btnSaveFuelRecord.setText("확인 저장");

        // 기본 초기값
        binding.textDetectedLiters.setText("20.0 L");
        binding.textDetectedUnitPrice.setText("1,700원/L");
        binding.textDetectedAmount.setText("34,000원");
        binding.etActualAmount.setText("34,000");
        binding.etActualLiters.setText("20.0");
        binding.etUnitPrice.setText("1,700");

        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient.FuelCandidateDetailResponse resp =
                    apiClient.fetchFuelCandidateDetail(cId);

            if (!isAdded() || getActivity() == null) return;

            requireActivity().runOnUiThread(() -> {
                if (resp.isSuccess()) {
                    candidateRevision = resp.revision;
                    double liters = resp.estimatedLiters != null ? resp.estimatedLiters : 20.0;
                    double amount = resp.estimatedAmount != null ? resp.estimatedAmount : 34000.0;
                    long unitPrice = (liters > 0) ? Math.round(amount / liters) : 1700L;

                    binding.textDetectedLiters.setText(String.format(Locale.KOREA, "%.1f L", liters));
                    binding.textDetectedUnitPrice.setText(String.format(Locale.KOREA, "%,d원/L", unitPrice));
                    binding.textDetectedAmount.setText(String.format(Locale.KOREA, "%,d원", Math.round(amount)));

                    binding.etActualAmount.setText(String.format(Locale.KOREA, "%,d", Math.round(amount)));
                    binding.etActualLiters.setText(String.format(Locale.KOREA, "%.1f", liters));
                    binding.etUnitPrice.setText(String.valueOf(unitPrice));

                    if (!resp.stationCandidates.isEmpty()) {
                        binding.rbStationCandidate1.setText(resp.stationCandidates.get(0));
                        if (resp.stationCandidates.size() > 1) {
                            binding.rbStationCandidate2.setText(resp.stationCandidates.get(1));
                        }
                    }
                }
            });
        });
    }

    private void setupInputWatchers() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int count, int after) {
                checkPriceDiscrepancy();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        };

        binding.etActualAmount.addTextChangedListener(watcher);
        binding.etActualLiters.addTextChangedListener(watcher);
        binding.etUnitPrice.addTextChangedListener(watcher);
    }

    /**
     * FR-M07-3: 실제 금액·주유량·단가 불일치 시 할인/반올림 안내 표시.
     * 시스템이 몰래 값을 바꾸지 않고 사용자가 입력한 실제 결제 금액을 보존한다.
     */
    private void checkPriceDiscrepancy() {
        long actualAmount = parseLongSafe(binding.etActualAmount.getText().toString());
        double liters = parseDoubleSafe(binding.etActualLiters.getText().toString());
        long unitPrice = parseLongSafe(binding.etUnitPrice.getText().toString());

        if (actualAmount > 0 && liters > 0 && unitPrice > 0) {
            long calculatedAmount = Math.round(liters * unitPrice);
            long diff = Math.abs(actualAmount - calculatedAmount);

            if (diff > 50) {
                binding.layoutDiscrepancyNotice.setVisibility(View.VISIBLE);
                String msg = String.format(Locale.KOREA,
                        "단가 계산 금액(%,d원)과 입력 결제 금액(%,d원)이 약 %,d원 차이납니다.\n주유 할인·포인트 적용 여부를 확인해 주세요. 입력하신 실제 결제 금액(%,d원)이 보존 저장됩니다.",
                        calculatedAmount, actualAmount, diff, actualAmount);
                binding.textDiscrepancyDetail.setText(msg);
                return;
            }
        }
        binding.layoutDiscrepancyNotice.setVisibility(View.GONE);
    }

    private void handleSave() {
        long amount = parseLongSafe(binding.etActualAmount.getText().toString());
        if (amount <= 0) {
            Snackbar.make(binding.getRoot(), "실제 결제 금액을 입력해 주세요.", Snackbar.LENGTH_SHORT).show();
            return;
        }

        double liters = parseDoubleSafe(binding.etActualLiters.getText().toString());
        String selectedStation = getSelectedStationName();

        // 1. 자동 감지 후보인 경우 백엔드 PATCH /mobile/fuel-candidates/{id} CONFIRM 호출
        if (candidateId != null && !candidateId.isEmpty()) {
            Executors.newSingleThreadExecutor().execute(() -> {
                apiClient.patchFuelCandidate(
                        candidateId,
                        "CONFIRM",
                        candidateRevision,
                        selectedStation,
                        liters > 0 ? liters : null,
                        (double) amount
                );
            });
        }

        // 2. 로컬 차계부 저장소(M03HomeRepository)에 지출 레코드 추가
        String vehicleId = homeRepository.getSelectedVehicle() != null
                ? homeRepository.getSelectedVehicle().getVehicleId()
                : "primary_veh";

        SimpleDateFormat sdf = new SimpleDateFormat("MM월 dd일", Locale.KOREA);
        String dateLabel = sdf.format(new Date());
        if (liters > 0) {
            dateLabel += String.format(Locale.KOREA, " · %.1f L", liters);
        }

        ExpenseRecord newRecord = new ExpenseRecord(
                "fuel_exp_" + UUID.randomUUID(),
                vehicleId,
                ExpenseRecord.Category.FUEL,
                ExpenseRecord.Ownership.MY_RECORD,
                ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                selectedStation,
                dateLabel,
                amount
        );
        homeRepository.addExpenseRecord(newRecord);

        Snackbar.make(binding.getRoot(),
                String.format(Locale.KOREA, "주유 지출(%,d원)이 차계부에 저장되었습니다.", amount),
                Snackbar.LENGTH_SHORT).show();

        binding.getRoot().postDelayed(() -> {
            if (isAdded()) {
                NavHostFragment.findNavController(this).popBackStack();
            }
        }, 500);
    }

    private void handleReject() {
        if (candidateId != null && !candidateId.isEmpty()) {
            Executors.newSingleThreadExecutor().execute(() -> {
                apiClient.patchFuelCandidate(
                        candidateId,
                        "REJECT",
                        candidateRevision,
                        null,
                        null,
                        null
                );
            });
        }

        Snackbar.make(binding.getRoot(), "주유 후보를 기각 처리했습니다.", Snackbar.LENGTH_SHORT).show();

        binding.getRoot().postDelayed(() -> {
            if (isAdded()) {
                NavHostFragment.findNavController(this).popBackStack();
            }
        }, 500);
    }

    private String getSelectedStationName() {
        int checkedId = binding.rgStationCandidates.getCheckedRadioButtonId();
        if (checkedId == R.id.rb_station_manual) {
            String manual = binding.etStationManualInput.getText().toString().trim();
            return !manual.isEmpty() ? manual : "직접 입력 주유소";
        } else if (checkedId == R.id.rb_station_candidate_2) {
            return binding.rbStationCandidate2.getText().toString();
        } else {
            return binding.rbStationCandidate1.getText().toString();
        }
    }

    private long parseLongSafe(String str) {
        try {
            return Long.parseLong(str.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0L;
        }
    }

    private double parseDoubleSafe(String str) {
        try {
            return Double.parseDouble(str.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return 0.0;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
