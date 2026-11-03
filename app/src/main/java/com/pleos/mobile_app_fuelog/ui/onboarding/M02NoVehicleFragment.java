package com.pleos.mobile_app_fuelog.ui.onboarding;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.core.permission.OptionalPermissionType;
import com.pleos.mobile_app_fuelog.core.permission.PermissionGuideHelper;
import com.pleos.mobile_app_fuelog.databinding.FragmentM02NoVehicleBinding;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.ui.auth.M01LoginFragment;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M02. 등록된 차량 없음 화면 Fragment.
 * - 차량 번호(필수) + 유종 칩 선택(휘발유/경유/LPG/전기, 기본 휘발유)의 2초 컷 간편 온보딩 제공.
 * - 등록 완료 시 M03(차량 있음) 화면으로 이동.
 */
public class M02NoVehicleFragment extends Fragment {

    // 대한민국 자동차 번호판 패턴 (예: 12가 3456, 123가 4567, 서울12가 3456 등)
    private static final Pattern PLATE_NUMBER_PATTERN = Pattern.compile(
            "^([가-힣]{0,2}\\s?\\d{2,3}[가-힣])\\s?(\\d{4})$"
    );

    private FragmentM02NoVehicleBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentM02NoVehicleBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authRepository = AuthRepository.getInstance(requireContext());
        userCacheManager = UserCacheManager.getInstance(requireContext());

        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            navigateBackToM01();
            return;
        }

        boolean prevHidden = userCacheManager.wasPreviousAccountCacheHidden();
        String cachedVehicle = userCacheManager.getVisibleCachedVehicleName();
        String sessionText = "현재 로그인 계정: " + session.getEmail() + " (ID: " + session.getUserId() + ")\n"
                + "• 차량 캐시 상태: " + (cachedVehicle == null ? "등록 차량 없음 (이전 계정 캐시 노출 차단됨)" : cachedVehicle)
                + (prevHidden ? "\n• [보안 보호] 직전 다른 계정의 캐시가 완전히 숨김 및 초기화되었습니다." : "");
        binding.tvM02SessionInfo.setText(sessionText);

        binding.btnM02RegisterVehicle.setOnClickListener(v -> {
            binding.tilM02PlateNumber.setError(null);
            String rawPlate = binding.etM02PlateNumber.getText() != null
                    ? binding.etM02PlateNumber.getText().toString().trim()
                    : "";

            String normalizedPlate = normalizeKoreanPlateNumber(rawPlate);
            if (normalizedPlate == null) {
                binding.tilM02PlateNumber.setError(
                        "올바른 차량 번호 형식을 입력해 주세요 (예: 12가 3456 또는 123가 4567)."
                );
                return;
            }

            String selectedFuelType = getSelectedFuelTypeLabel();
            String formattedVehicleSummary = normalizedPlate + " (" + selectedFuelType + ")";

            userCacheManager.setRegisteredVehicleForUser(session.getUserId(), formattedVehicleSummary);
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.nav_m02_no_vehicle, true)
                    .build();
            NavHostFragment.findNavController(this)
                    .navigate(R.id.nav_m03_vehicle_home, null, navOptions);
        });

        binding.btnM02ExpireSession.setOnClickListener(v -> {
            authRepository.expireCurrentSession();
            Snackbar.make(binding.getRoot(),
                    "세션이 만료되어 이전 캐시를 숨기고 M01(로그인) 화면으로 이동합니다.",
                    Snackbar.LENGTH_SHORT).show();
            navigateBackToM01();
        });

        binding.btnM02Logout.setOnClickListener(v -> {
            authRepository.signOut();
            navigateBackToM01();
        });
    }

    /**
     * 입력된 차량 번호가 대한민국 번호판 규칙에 맞는지 검증하고 공백을 표준화(예: "12가 3456")한다.
     */
    @Nullable
    public static String normalizeKoreanPlateNumber(@Nullable String rawInput) {
        if (rawInput == null) {
            return null;
        }
        String compact = rawInput.trim().replaceAll("\\s+", "");
        Matcher matcher = PLATE_NUMBER_PATTERN.matcher(compact);
        if (!matcher.matches()) {
            return null;
        }
        return matcher.group(1) + " " + matcher.group(2);
    }

    @NonNull
    private String getSelectedFuelTypeLabel() {
        int checkedId = binding.chipGroupFuelType.getCheckedChipId();
        if (checkedId == R.id.chip_fuel_diesel) {
            return "경유";
        } else if (checkedId == R.id.chip_fuel_lpg) {
            return "LPG";
        } else if (checkedId == R.id.chip_fuel_ev) {
            return "전기";
        }
        return "휘발유";
    }

    private void startOptionalFeatureWithGuide(@NonNull OptionalPermissionType permissionType) {
        PermissionGuideHelper.showFeaturePermissionPurposeDialog(
                requireContext(),
                permissionType,
                new PermissionGuideHelper.PermissionActionCallback() {
                    @Override
                    public void onProceedToSystemRequest(@NonNull OptionalPermissionType type) {
                        permissionLauncher.launch(type.getAndroidPermissions());
                    }

                    @Override
                    public void onContinueWithoutPermission(@NonNull OptionalPermissionType type) {
                        if (binding != null) {
                            Snackbar.make(binding.getRoot(),
                                    type.getTitle() + " 없이 수동 차량 등록 모드로 계속 진행합니다 (로그인 유지).",
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
                ? "선택 권한이 허용되어 기능을 바로 시작합니다."
                : "선택 권한이 거부되었으나 로그인 상태와 기본 차량 등록 기능은 정상 유지됩니다.";
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
