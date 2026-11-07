package com.pleos.mobile_app_fuelog.ui.manage;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.databinding.FragmentManageSettingsBinding;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.connection.M04DriverConnectionCoordinator;
import com.pleos.mobile_app_fuelog.data.connection.RegisteredPleosDevice;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.ui.auth.M01LoginViewModel;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * [관리·설정 탭]
 * - 차량 연동은 Bluetooth SPP 없이 차량 디스플레이(C01)의 QR 코드 인식을 통해서만 수행한다.
 * - 연동된 차량 별칭 관리, QR 재스캔 연동, 차량 연결 초기화(POST /connection/reset), 계정·세션 관리를 제공한다.
 */
public class ManageSettingsFragment extends Fragment {

    private FragmentManageSettingsBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private M01LoginViewModel loginViewModel;
    private M04DriverConnectionCoordinator coordinator;

    private final ActivityResultLauncher<ScanOptions> qrBarcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null && !result.getContents().trim().isEmpty()) {
                    executeQrVehicleSync(result.getContents().trim());
                }
            });

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentManageSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authRepository = AuthRepository.getInstance(requireContext());
        userCacheManager = UserCacheManager.getInstance(requireContext());
        loginViewModel = new ViewModelProvider(requireActivity()).get(M01LoginViewModel.class);
        loginViewModel.init(authRepository);
        coordinator = M04DriverConnectionCoordinator.getInstance();

        setupListeners();
        renderAll();
    }

    private void setupListeners() {
        // 1. 차량 별칭 수정
        binding.btnManageEditAlias.setOnClickListener(v -> showEditAliasDialog());

        // 2. 카메라로 차량(C01) QR 코드 스캔 연동
        binding.btnManageScanQrCamera.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("차량 디스플레이(C01)에 표시된 페어링 QR 코드를 스캔하세요");
            options.setBeepEnabled(false);
            options.setOrientationLocked(false);
            qrBarcodeLauncher.launch(options);
        });

        // 3. QR 인식 전용 화면(M02)으로 이동
        binding.btnManageRegisterVehicle.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.nav_m02_no_vehicle)
        );

        // 4. 자동 추천 허용 스위치
        binding.switchManageAutoRecommend.setChecked(coordinator.isAutoRecommendAllowed());
        binding.switchManageAutoRecommend.setOnCheckedChangeListener((buttonView, isChecked) ->
                coordinator.setAutoRecommendAllowed(isChecked)
        );

        // 5. 세션 만료 테스트
        binding.btnManageExpireSession.setOnClickListener(v -> {
            authRepository.expireCurrentSession();
            navigateToM01();
        });

        // 6. 로그아웃 (다른 계정 전환)
        binding.btnManageLogout.setOnClickListener(v -> {
            Executors.newSingleThreadExecutor().execute(() ->
                    FuelogBackendApiClient.getInstance(authRepository).revokeActiveSessionAndConsent(userCacheManager)
            );
            authRepository.signOut();
            navigateToM01();
        });
    }

    private void executeQrVehicleSync(@NonNull String scannedQrPayload) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            navigateToM01();
            return;
        }
        Snackbar.make(binding.getRoot(), "스캔한 QR 코드로 백엔드 차량 연동을 진행합니다...", Snackbar.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient.EndToEndVehicleSyncResult result =
                    FuelogBackendApiClient.getInstance(authRepository)
                            .recognizeQrAndLinkVehicle(userCacheManager, scannedQrPayload, "QR 연동 차량");
            if (!isAdded() || getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (result.success && result.vehicleId != null) {
                    String resolvedAlias = result.vehicleName != null ? result.vehicleName : "QR 연동 차량";
                    userCacheManager.setRegisteredVehicleUuid(session.getUserId(), result.vehicleId);
                    userCacheManager.setRegisteredVehicleForUser(session.getUserId(), resolvedAlias);
                    coordinator.registerOrUpdateFromQrPairing(
                            new RegisteredPleosDevice(
                                    "qr_" + result.vehicleId,
                                    "C01 QR 연동 차량 단말",
                                    "QR:" + result.vehicleId.substring(0, Math.min(8, result.vehicleId.length())),
                                    resolvedAlias,
                                    true,
                                    true
                            ),
                            result.vehicleId,
                            resolvedAlias
                    );
                    renderAll();
                    Snackbar.make(binding.getRoot(),
                            "QR 차량 연동 완료: " + resolvedAlias + " (" + result.vehicleId + ")",
                            Snackbar.LENGTH_LONG).show();
                } else {
                    Snackbar.make(binding.getRoot(),
                            "QR 코드 검증에 실패했습니다.",
                            Snackbar.LENGTH_LONG).show();
                }
            });
        });
    }

    private void renderAll() {
        if (binding == null) {
            return;
        }
        AuthSession session = authRepository.getCurrentValidSession();
        String cachedVehicle = userCacheManager.getVisibleCachedVehicleName();
        String vehicleId = session != null ? userCacheManager.getRegisteredVehicleUuid(session.getUserId()) : null;
        String instId = session != null ? userCacheManager.getInstallationId(session.getUserId()) : null;

        if (cachedVehicle != null && !cachedVehicle.isEmpty()) {
            coordinator.setVehicleAlias(cachedVehicle);
            binding.tvManageCurrentVehicle.setText(
                    "현재 QR 연동 차량: " + cachedVehicle
                            + "\n• 차량 UUID: " + (vehicleId != null ? vehicleId : "서버 확인 중")
                            + "\n• 설치 ID (C01): " + (instId != null ? instId : "서버 확인 중")
            );
        } else {
            binding.tvManageCurrentVehicle.setText(
                    "현재 연동된 차량이 없습니다.\n차량 화면(C01)의 QR 코드를 스캔해 차량을 연동해 주세요."
            );
        }

        if (session != null) {
            binding.tvManageAccountInfo.setText(
                    "로그인 이메일: " + session.getEmail()
                            + "\n사용자 ID(sub): " + session.getUserId()
                            + "\n연동 프로토콜: C01/M02 QR 코드 인식 전용"
            );
        } else {
            binding.tvManageAccountInfo.setText("유효한 로그인 세션이 없습니다.");
        }

        renderRegisteredDevices();
    }

    private void renderRegisteredDevices() {
        binding.containerManageDevices.removeAllViews();
        List<RegisteredPleosDevice> devices = coordinator.getRegisteredDevices();
        if (devices.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("QR 인식으로 연동된 차량 단말이 없습니다. 상단 [카메라로 차량(C01) QR 코드 스캔 연동] 또는 [M02 이동]으로 연동해 주세요.");
            empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_secondary));
            empty.setTextSize(12.5f);
            binding.containerManageDevices.addView(empty);
            return;
        }

        for (RegisteredPleosDevice dev : devices) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(28, 22, 28, 22);
            row.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.fuelog_surface));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.topMargin = 10;
            row.setLayoutParams(lp);

            LinearLayout col = new LinearLayout(requireContext());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = new TextView(requireContext());
            title.setText(dev.getDeviceName() + "  (" + dev.getMacAddress() + ")");
            title.setTypeface(null, Typeface.BOLD);
            title.setTextSize(13.5f);
            title.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_primary));
            col.addView(title);

            TextView sub = new TextView(requireContext());
            sub.setText("연동 방식: C01/C03 QR 코드 인증 · 대상 차량: " + dev.getVehicleAlias());
            sub.setTextSize(12f);
            sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_secondary));
            col.addView(sub);

            row.addView(col);

            MaterialButton unlinkBtn = new MaterialButton(requireContext());
            unlinkBtn.setText("연동 해제");
            unlinkBtn.setTextSize(11f);
            unlinkBtn.setOnClickListener(v -> {
                AuthSession s = authRepository.getCurrentValidSession();
                if (s != null) {
                    String instSecret = userCacheManager.getInstallationSecret(s.getUserId());
                    String vehUuid = userCacheManager.getRegisteredVehicleUuid(s.getUserId());
                    if (instSecret != null && vehUuid != null) {
                        Executors.newSingleThreadExecutor().execute(() ->
                                FuelogBackendApiClient.getInstance(authRepository)
                                        .resetVehicleConnection(instSecret, vehUuid)
                        );
                    }
                    userCacheManager.setRegisteredVehicleForUser(s.getUserId(), null);
                    userCacheManager.setRegisteredVehicleUuid(s.getUserId(), null);
                }
                coordinator.removeAllRegisteredDevices();
                renderAll();
                Snackbar.make(binding.getRoot(),
                        "차량 QR 연동이 초기화되었습니다 (C07 Reset).",
                        Snackbar.LENGTH_SHORT).show();
            });
            row.addView(unlinkBtn);

            binding.containerManageDevices.addView(row);
        }
    }

    private void showEditAliasDialog() {
        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(coordinator.getVehicleAlias());
        input.setPadding(48, 32, 48, 32);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("내 차량 별칭 수정")
                .setView(input)
                .setPositiveButton("저장", (d, w) -> {
                    if (input.getText() != null && !input.getText().toString().trim().isEmpty()) {
                        String newAlias = input.getText().toString().trim();
                        coordinator.setVehicleAlias(newAlias);
                        AuthSession s = authRepository.getCurrentValidSession();
                        if (s != null) {
                            userCacheManager.setRegisteredVehicleForUser(s.getUserId(), newAlias);
                        }
                        renderAll();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void navigateToM01() {
        NavOptions navOptions = new NavOptions.Builder()
                .setPopUpTo(R.id.nav_manage_settings, true)
                .build();
        NavHostFragment.findNavController(this).navigate(R.id.nav_m01_login, null, navOptions);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
