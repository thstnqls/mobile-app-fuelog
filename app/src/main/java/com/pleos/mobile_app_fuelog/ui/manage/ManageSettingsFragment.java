package com.pleos.mobile_app_fuelog.ui.manage;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.databinding.FragmentManageSettingsBinding;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.connection.M04DriverConnectionCoordinator;
import com.pleos.mobile_app_fuelog.data.connection.RegisteredPleosDevice;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.ui.auth.M01LoginViewModel;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * [관리·설정 탭]
 * - 카드 안에 내장된 인라인 카메라 뷰파인더(DecoratedBarcodeView)로 QR을 스캔하면
 *   백엔드(POST /pairing-requests/{id}/intent)가 발급한 6자리 비교 확인 코드(confirmation_code)를 화면에 표시한다.
 * - 사용자가 차량 디스플레이(C01)의 6자리 코드와 일치함을 확인하면 최종 연동(confirm)을 완료한다.
 */
public class ManageSettingsFragment extends Fragment {

    private FragmentManageSettingsBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private M01LoginViewModel loginViewModel;
    private M04DriverConnectionCoordinator coordinator;
    private boolean isProcessingQr = false;

    @Nullable
    private String pendingRequestId = null;
    @Nullable
    private String pendingConfirmationCode = null;
    @NonNull
    private String pendingVehicleAlias = "QR 연동 차량";

    @Nullable
    private CountDownTimer codeCountDownTimer = null;
    private int codeFailureCount = 0;
    private boolean isCheckingCode = false;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);

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

        setupInlineBarcodeScanner();
        setupListeners();
        renderAll();
        syncLatestVehicleFromServerDb();
    }

    private void setupInlineBarcodeScanner() {
        binding.barcodeScannerManage.getBarcodeView().setDecoderFactory(
                new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE))
        );
        binding.barcodeScannerManage.setStatusText("차량 디스플레이(C01)의 QR 코드를 사각형 안에 맞춰주세요");
        binding.barcodeScannerManage.decodeContinuous(result -> {
            if (binding == null || isProcessingQr || result.getText() == null || result.getText().trim().isEmpty()) {
                return;
            }
            isProcessingQr = true;
            binding.barcodeScannerManage.pause();
            String scannedQr = result.getText().trim();
            requestSixDigitConfirmationCode(scannedQr, "QR 연동 차량");
        });
    }

    private void startInlineCameraWithPermissionCheck() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            if (binding != null) {
                binding.barcodeScannerManage.resume();
            }
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        }
    }

    private void onPermissionsResult(Map<String, Boolean> result) {
        if (binding == null) {
            return;
        }
        Boolean camGranted = result.get(Manifest.permission.CAMERA);
        if (Boolean.TRUE.equals(camGranted)) {
            binding.barcodeScannerManage.resume();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null && !isProcessingQr) {
            startInlineCameraWithPermissionCheck();
        }
    }

    @Override
    public void onPause() {
        if (binding != null) {
            binding.barcodeScannerManage.pause();
        }
        super.onPause();
    }

    private void syncLatestVehicleFromServerDb() {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) return;
        String userId = session.getUserId();
        String instSecret = userCacheManager.getInstallationSecret(userId);
        if (instSecret == null || instSecret.isEmpty()) return;

        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient api = FuelogBackendApiClient.getInstance(authRepository);
            FuelogBackendApiClient.VehicleHomeResponse home =
                    api.fetchVehicleHome(instSecret, userCacheManager.getActiveScopedToken());
            if (home.success && home.vehicleAlias != null && !home.vehicleAlias.trim().isEmpty()) {
                userCacheManager.setRegisteredVehicleForUser(userId, home.vehicleAlias.trim());
                coordinator.setVehicleAlias(home.vehicleAlias.trim());
                if (isAdded() && getActivity() != null) {
                    requireActivity().runOnUiThread(this::renderAll);
                }
            }
        });
    }

    private void setupListeners() {
        // 1. 차량 별칭 수정
        binding.btnManageEditAlias.setOnClickListener(v -> showEditAliasDialog());

        // 2. 내장 카메라 QR 스캔 재시작 / 권한 허용
        binding.btnManageScanQrCamera.setOnClickListener(v -> {
            isProcessingQr = false;
            pendingRequestId = null;
            pendingConfirmationCode = null;
            codeFailureCount = 0;
            isCheckingCode = false;
            if (codeCountDownTimer != null) {
                codeCountDownTimer.cancel();
                codeCountDownTimer = null;
            }
            binding.layoutManageConfirmationCode.setVisibility(View.GONE);
            startInlineCameraWithPermissionCheck();
        });

        // 3. 차량 화면(C01)의 6자리 승인 코드 직접 입력 또는 6자리 입력 즉시 자동 API 전송
        binding.etManageConfirmationCode.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (s != null && s.toString().trim().length() == 6
                        && pendingRequestId != null
                        && binding != null
                        && !isCheckingCode) {
                    attemptConfirmationWithEnteredCode();
                }
            }
        });

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

    /**
     * 1단계: QR 스캔 직후 6자리 비교 확인 코드(confirmation_code)를 발급받아 카드 안에 표시한다.
     */
    private void requestSixDigitConfirmationCode(@NonNull String scannedQrPayload, @NonNull String preferredAlias) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            navigateToM01();
            return;
        }
        Snackbar.make(binding.getRoot(), "QR 코드를 검증하고 6자리 비교 확인 코드를 발급받습니다...", Snackbar.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient.QrIntentCodeResult codeResult =
                    FuelogBackendApiClient.getInstance(authRepository)
                            .submitQrIntentForConfirmationCode(userCacheManager, scannedQrPayload, preferredAlias);
            if (!isAdded() || getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (codeResult.success && codeResult.confirmationCode != null && codeResult.requestId != null) {
                    pendingRequestId = codeResult.requestId;
                    pendingConfirmationCode = codeResult.confirmationCode;
                    pendingVehicleAlias = codeResult.vehicleName != null ? codeResult.vehicleName : preferredAlias;

                    binding.layoutManageConfirmationCode.setVisibility(View.VISIBLE);
                    binding.etManageConfirmationCode.setText("");
                    binding.etManageConfirmationCode.setEnabled(true);
                    binding.etManageConfirmationCode.requestFocus();

                    startCodeCountdownTimer();

                    binding.tvManageConfirmationCodeGuide.setText(
                            "차량 디스플레이(C01) 화면에 표시된 6자리 승인 코드를 입력해 주세요.\n"
                                    + "(대상 차량: " + pendingVehicleAlias + ")"
                    );
                    Snackbar.make(binding.getRoot(),
                            "120초 이내에 차량 디스플레이의 6자리 승인 코드를 입력해 주세요.",
                            Snackbar.LENGTH_SHORT).show();
                } else {
                    isProcessingQr = false;
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        binding.barcodeScannerManage.resume();
                    }
                    Snackbar.make(binding.getRoot(),
                            "QR 코드 검증 실패: " + (codeResult.errorMessage != null ? codeResult.errorMessage : "유효한 C01 QR 코드를 비춰주세요."),
                            Snackbar.LENGTH_LONG).show();
                }
            });
        });
    }

    private void startCodeCountdownTimer() {
        if (codeCountDownTimer != null) {
            codeCountDownTimer.cancel();
        }
        codeFailureCount = 0;
        isCheckingCode = false;
        if (binding == null) return;
        binding.etManageConfirmationCode.setEnabled(true);
        binding.tvManageCodeError.setVisibility(View.GONE);
        binding.layoutManageCodeLoading.setVisibility(View.GONE);
        binding.tvManageCodeTimer.setTextColor(Color.parseColor("#5CE1A6"));

        codeCountDownTimer = new CountDownTimer(120000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (binding == null) return;
                long totalSec = millisUntilFinished / 1000;
                long min = totalSec / 60;
                long sec = totalSec % 60;
                binding.tvManageCodeTimer.setText(String.format(Locale.getDefault(), "남은 입력 시간: %02d:%02d", min, sec));
                if (totalSec <= 20) {
                    binding.tvManageCodeTimer.setTextColor(Color.parseColor("#FF6B6B"));
                }
            }

            @Override
            public void onFinish() {
                if (binding == null) return;
                binding.tvManageCodeTimer.setText("유효 시간 만료 (120초 초과)");
                binding.tvManageCodeTimer.setTextColor(Color.parseColor("#FF6B6B"));
                binding.etManageConfirmationCode.setEnabled(false);
                binding.layoutManageCodeLoading.setVisibility(View.GONE);
                binding.tvManageCodeError.setText("120초가 초과되어 승인 코드가 만료되었습니다. 새 QR 코드를 스캔해 주세요.");
                binding.tvManageCodeError.setVisibility(View.VISIBLE);
                pendingRequestId = null;
                pendingConfirmationCode = null;
            }
        }.start();
    }

    private void attemptConfirmationWithEnteredCode() {
        if (binding == null || isCheckingCode) return;
        if (pendingRequestId == null) {
            Snackbar.make(binding.getRoot(), "먼저 차량 화면의 QR 코드를 스캔해 주세요.", Snackbar.LENGTH_SHORT).show();
            return;
        }
        String inputCode = binding.etManageConfirmationCode.getText() != null
                ? binding.etManageConfirmationCode.getText().toString().trim()
                : "";
        if (inputCode.length() != 6 || !inputCode.matches("\\d{6}")) {
            Snackbar.make(binding.getRoot(), "차량 화면에 표시된 6자리 숫자를 입력해 주세요.", Snackbar.LENGTH_SHORT).show();
            return;
        }

        hideKeyboard(binding.etManageConfirmationCode);

        // 6자리 입력 시 로딩 애니메이션 띄우면서 코드 검사 진행
        isCheckingCode = true;
        binding.layoutManageCodeLoading.setVisibility(View.VISIBLE);
        binding.tvManageCodeLoadingText.setText("승인 코드 검사 중...");
        binding.tvManageCodeError.setVisibility(View.GONE);

        binding.getRoot().postDelayed(() -> {
            if (binding == null) {
                isCheckingCode = false;
                return;
            }

            boolean isMatch = (pendingConfirmationCode != null && pendingConfirmationCode.equals(inputCode));

            if (!isMatch) {
                // 틀렸을 때의 로직
                isCheckingCode = false;
                codeFailureCount++;
                binding.layoutManageCodeLoading.setVisibility(View.GONE);

                String errorMsg = "승인 코드가 일치하지 않습니다. (" + codeFailureCount + "회 불일치)\n차량 디스플레이(C01)의 6자리 코드를 다시 확인해 주세요.";
                binding.tvManageCodeError.setText(errorMsg);
                binding.tvManageCodeError.setVisibility(View.VISIBLE);

                // 흔들림 애니메이션 효과 및 입력 필드 리셋 후 재입력 대기
                binding.etManageConfirmationCode.animate()
                        .translationXBy(20f).setDuration(50)
                        .withEndAction(() -> binding.etManageConfirmationCode.animate()
                                .translationXBy(-40f).setDuration(50)
                                .withEndAction(() -> binding.etManageConfirmationCode.animate()
                                        .translationXBy(20f).setDuration(50)
                                        .withEndAction(() -> {
                                            if (binding != null) {
                                                binding.etManageConfirmationCode.setTranslationX(0f);
                                                binding.etManageConfirmationCode.setText("");
                                                binding.etManageConfirmationCode.requestFocus();
                                                showKeyboard(binding.etManageConfirmationCode);
                                            }
                                        })))
                        .start();

                Snackbar.make(binding.getRoot(), "승인 코드가 일치하지 않습니다. 다시 입력해 주세요.", Snackbar.LENGTH_SHORT).show();
            } else {
                // 맞았을 때의 로직
                binding.tvManageCodeLoadingText.setText("승인 코드 일치 확인! 백엔드 DB 차량 등록 진행 중...");
                binding.tvManageCodeError.setVisibility(View.GONE);
                confirmSixDigitCodeAndCompleteSync(pendingRequestId, inputCode, pendingVehicleAlias);
            }
        }, 500);
    }

    private void hideKeyboard(@Nullable View view) {
        if (view != null && isAdded() && getContext() != null) {
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }

    private void showKeyboard(@Nullable View view) {
        if (view != null && isAdded() && getContext() != null) {
            InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    /**
     * 2단계: 6자리 확인 코드 일치 확인 후 POST /pairing-requests/{id}/confirm으로 최종 연동을 완료한다.
     */
    private void confirmSixDigitCodeAndCompleteSync(
            @NonNull String requestId,
            @NonNull String confirmationCode,
            @NonNull String preferredAlias
    ) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            navigateToM01();
            return;
        }
        Snackbar.make(binding.getRoot(),
                "승인 코드 확인 완료! 차량 화면에서 [코드가 같아요 · 차량 연결]을 눌러주세요.",
                Snackbar.LENGTH_LONG).show();

        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient.EndToEndVehicleSyncResult result =
                    FuelogBackendApiClient.getInstance(authRepository)
                            .confirmQrPairingWithCode(userCacheManager, requestId, confirmationCode, preferredAlias);
            if (!isAdded() || getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                isProcessingQr = false;
                isCheckingCode = false;
                binding.layoutManageCodeLoading.setVisibility(View.GONE);

                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    binding.barcodeScannerManage.resume();
                }
                if (result.success && result.vehicleId != null) {
                    if (codeCountDownTimer != null) {
                        codeCountDownTimer.cancel();
                        codeCountDownTimer = null;
                    }
                    String resolvedAlias = result.vehicleName != null ? result.vehicleName : preferredAlias;
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
                    binding.layoutManageConfirmationCode.setVisibility(View.GONE);
                    renderAll();
                    Snackbar.make(binding.getRoot(),
                            "6자리 코드(" + confirmationCode + ") 확인 및 차량 연동 완료: " + resolvedAlias,
                            Snackbar.LENGTH_LONG).show();
                } else {
                    Snackbar.make(binding.getRoot(),
                            "차량 화면(C01)에서 6자리 코드(" + confirmationCode + ") 확인을 완료해 주세요.",
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
                    "현재 서버 DB 연동 차량: " + cachedVehicle
                            + "\n• 차량 UUID: " + (vehicleId != null ? vehicleId : "서버 확인 중")
                            + "\n• 설치 ID (C01): " + (instId != null ? instId : "서버 확인 중")
            );
        } else {
            binding.tvManageCurrentVehicle.setText(
                    "현재 연동된 차량이 없습니다.\n아래 내장 카메라 뷰파인더에 차량 화면(C01)의 QR 코드를 비춰주세요."
            );
        }

        if (session != null) {
            binding.tvManageAccountInfo.setText(
                    "로그인 이메일: " + session.getEmail()
                            + "\n사용자 ID(sub): " + session.getUserId()
                            + "\n연동 프로토콜: 화면 내장 카메라(C01) QR 스캔 + 6자리 확인 코드 대조"
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
            empty.setText("QR 인식으로 연동된 차량 단말이 없습니다. 상단 내장 카메라에 차량 QR 코드를 비춰 연동해 주세요.");
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
            sub.setText("연동 방식: 내장 카메라 C01/C03 QR 스캔 · 대상 차량: " + dev.getVehicleAlias());
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
        if (codeCountDownTimer != null) {
            codeCountDownTimer.cancel();
            codeCountDownTimer = null;
        }
        if (binding != null) {
            binding.barcodeScannerManage.pause();
        }
        super.onDestroyView();
        binding = null;
    }
}
