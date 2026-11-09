package com.pleos.mobile_app_fuelog.ui.onboarding;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.core.permission.OptionalPermissionType;
import com.pleos.mobile_app_fuelog.core.permission.PermissionGuideHelper;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.connection.M04DriverConnectionCoordinator;
import com.pleos.mobile_app_fuelog.data.connection.RegisteredPleosDevice;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.databinding.FragmentM02NoVehicleBinding;
import com.pleos.mobile_app_fuelog.ui.auth.M01LoginFragment;
import com.pleos.mobile_app_fuelog.ui.auth.M01LoginViewModel;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * M02 차량 미등록 안내 및 차량 화면(C01) QR 코드 인식 전용 연동 화면.
 * - 카드 안에 내장된 인라인 카메라 뷰파인더(DecoratedBarcodeView)로 QR을 스캔하면
 *   백엔드(POST /pairing-requests/{id}/intent)가 발급한 6자리 비교 확인 코드(confirmation_code)를 화면에 표시한다.
 * - 사용자가 차량 디스플레이(C01)의 6자리 코드와 일치함을 확인하면 최종 연동(confirm)을 완료한다.
 */
public class M02NoVehicleFragment extends Fragment {

    private FragmentM02NoVehicleBinding binding;
    private M01LoginViewModel viewModel;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
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
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentM02NoVehicleBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        userCacheManager = UserCacheManager.getInstance(requireContext());
        authRepository = AuthRepository.getInstance(requireContext());
        viewModel = new ViewModelProvider(requireActivity()).get(M01LoginViewModel.class);
        viewModel.init(authRepository);

        bindSessionInfo();
        setupInlineBarcodeScanner();
        checkPendingDeepLinkPermissionPrompt();

        // 1. 내장 카메라 QR 스캔 재시작 / 권한 허용
        binding.btnM02ScanQrCamera.setOnClickListener(v -> {
            isProcessingQr = false;
            pendingRequestId = null;
            pendingConfirmationCode = null;
            codeFailureCount = 0;
            isCheckingCode = false;
            if (codeCountDownTimer != null) {
                codeCountDownTimer.cancel();
                codeCountDownTimer = null;
            }
            binding.layoutM02ConfirmationCode.setVisibility(View.GONE);
            startInlineCameraWithPermissionCheck();
        });

        // 2. 차량 화면(C01)의 6자리 승인 코드 입력 즉시 자동 검증 및 API 전송
        binding.etM02ConfirmationCode.addTextChangedListener(new TextWatcher() {
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

        // 3. 세션 만료 시뮬레이션 -> M01 복귀
        binding.btnM02ExpireSession.setOnClickListener(v -> {
            authRepository.expireCurrentSession();
            navigateBackToM01();
        });

        // 4. 로그아웃 (다른 계정 전환 시 캐시 격리 검증)
        binding.btnM02Logout.setOnClickListener(v -> {
            authRepository.signOut();
            navigateBackToM01();
        });
    }

    private void setupInlineBarcodeScanner() {
        binding.barcodeScannerM02.getBarcodeView().setDecoderFactory(
                new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE))
        );
        binding.barcodeScannerM02.setStatusText("차량 디스플레이(C01)의 QR 코드를 사각형 안에 맞춰주세요");
        binding.barcodeScannerM02.decodeContinuous(result -> {
            if (binding == null || isProcessingQr || result.getText() == null || result.getText().trim().isEmpty()) {
                return;
            }
            isProcessingQr = true;
            binding.barcodeScannerM02.pause();
            String scannedQr = result.getText().trim();
            binding.tvM02QrStatus.setText("상태: QR 스캔 완료 · 서버에서 6자리 비교 확인 코드를 발급받는 중...");
            requestSixDigitConfirmationCode(scannedQr, "QR 연동 차량");
        });
    }

    private void startInlineCameraWithPermissionCheck() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            if (binding != null) {
                binding.tvM02QrStatus.setText("상태: 내장 카메라 스캐너가 활성화되었습니다. 차량 화면(C01)의 QR 코드를 비춰주세요.");
                binding.barcodeScannerM02.resume();
            }
        } else {
            if (binding != null) {
                binding.tvM02QrStatus.setText("상태: 내장 카메라 뷰파인더를 켜기 위해 카메라 권한을 허용해 주세요.");
            }
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
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
            binding.barcodeScannerM02.pause();
        }
        super.onPause();
    }

    /**
     * 1단계: QR 스캔 직후 POST /pairing-requests/{id}/intent를 호출해 6자리 확인 코드(confirmation_code)를 받아 화면에 표시한다.
     */
    private void requestSixDigitConfirmationCode(@NonNull String scannedQrPayload, @NonNull String preferredAlias) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            navigateBackToM01();
            return;
        }
        if (binding != null) {
            binding.tvM02QrStatus.setText("상태: QR 검증 및 6자리 비교 확인 코드 발급 중...");
        }

        new Thread(() -> {
            FuelogBackendApiClient.QrIntentCodeResult codeResult =
                    FuelogBackendApiClient.getInstance(authRepository)
                            .submitQrIntentForConfirmationCode(userCacheManager, scannedQrPayload, preferredAlias);

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (binding == null || !isAdded()) return;

                if (codeResult.success && codeResult.confirmationCode != null && codeResult.requestId != null) {
                    pendingRequestId = codeResult.requestId;
                    pendingConfirmationCode = codeResult.confirmationCode;
                    pendingVehicleAlias = codeResult.vehicleName != null ? codeResult.vehicleName : preferredAlias;

                    // 화면 내 차량 승인 코드 입력창 영역 노출 (코드를 자동 입력하지 않고 사용자가 직접 입력하도록 빈 상태 유지)
                    binding.layoutM02ConfirmationCode.setVisibility(View.VISIBLE);
                    binding.etM02ConfirmationCode.setText("");
                    binding.etM02ConfirmationCode.setEnabled(true);
                    binding.etM02ConfirmationCode.requestFocus();

                    startCodeCountdownTimer();

                    binding.tvM02ConfirmationCodeGuide.setText(
                            "차량 디스플레이(C01) 화면에 표시된 6자리 승인 코드를 입력해 주세요.\n"
                                    + "(대상 차량: " + pendingVehicleAlias + ")"
                    );
                    binding.tvM02QrStatus.setText(
                            "상태: QR 인식 완료 — 120초 이내에 차량 디스플레이의 6자리 승인 코드를 입력해 주세요."
                    );
                } else {
                    isProcessingQr = false;
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                        binding.barcodeScannerM02.resume();
                    }
                    binding.tvM02QrStatus.setText("상태: QR 코드 확인 코드 발급에 실패했습니다. 유효한 C01 QR 코드를 다시 스캔해 주세요.");
                    Snackbar.make(binding.getRoot(),
                            "QR 코드 검증 실패: " + (codeResult.errorMessage != null ? codeResult.errorMessage : "유효한 QR이 아닙니다."),
                            Snackbar.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void startCodeCountdownTimer() {
        if (codeCountDownTimer != null) {
            codeCountDownTimer.cancel();
        }
        codeFailureCount = 0;
        isCheckingCode = false;
        if (binding == null) return;
        binding.etM02ConfirmationCode.setEnabled(true);
        binding.tvM02CodeError.setVisibility(View.GONE);
        binding.layoutM02CodeLoading.setVisibility(View.GONE);
        binding.tvM02CodeTimer.setTextColor(Color.parseColor("#5CE1A6"));

        codeCountDownTimer = new CountDownTimer(120000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (binding == null) return;
                long totalSec = millisUntilFinished / 1000;
                long min = totalSec / 60;
                long sec = totalSec % 60;
                binding.tvM02CodeTimer.setText(String.format(Locale.getDefault(), "남은 입력 시간: %02d:%02d", min, sec));
                if (totalSec <= 20) {
                    binding.tvM02CodeTimer.setTextColor(Color.parseColor("#FF6B6B"));
                }
            }

            @Override
            public void onFinish() {
                if (binding == null) return;
                binding.tvM02CodeTimer.setText("유효 시간 만료 (120초 초과)");
                binding.tvM02CodeTimer.setTextColor(Color.parseColor("#FF6B6B"));
                binding.etM02ConfirmationCode.setEnabled(false);
                binding.layoutM02CodeLoading.setVisibility(View.GONE);
                binding.tvM02CodeError.setText("120초가 초과되어 승인 코드가 만료되었습니다. 새 QR 코드를 스캔해 주세요.");
                binding.tvM02CodeError.setVisibility(View.VISIBLE);
                binding.tvM02QrStatus.setText("상태: 120초 유효 시간 만료 — 차량 화면에서 새 QR 코드를 스캔해 주세요.");
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
        String inputCode = binding.etM02ConfirmationCode.getText() != null
                ? binding.etM02ConfirmationCode.getText().toString().trim()
                : "";
        if (inputCode.length() != 6 || !inputCode.matches("\\d{6}")) {
            Snackbar.make(binding.getRoot(), "차량 화면에 표시된 6자리 숫자를 입력해 주세요.", Snackbar.LENGTH_SHORT).show();
            return;
        }

        hideKeyboard(binding.etM02ConfirmationCode);

        // 6개 코드 입력 시 로딩 애니메이션 띄우면서 코드 검사 진행
        isCheckingCode = true;
        binding.layoutM02CodeLoading.setVisibility(View.VISIBLE);
        binding.tvM02CodeLoadingText.setText("승인 코드 검사 중...");
        binding.tvM02CodeError.setVisibility(View.GONE);

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
                binding.layoutM02CodeLoading.setVisibility(View.GONE);

                String errorMsg = "승인 코드가 일치하지 않습니다. (" + codeFailureCount + "회 불일치)\n차량 디스플레이(C01)의 6자리 코드를 다시 확인해 주세요.";
                binding.tvM02CodeError.setText(errorMsg);
                binding.tvM02CodeError.setVisibility(View.VISIBLE);
                binding.tvM02QrStatus.setText("상태: 승인 코드 불일치 (" + codeFailureCount + "회) — 다시 입력해 주세요.");

                // 흔들림 애니메이션 효과 및 입력 필드 리셋 후 재입력 대기
                binding.etM02ConfirmationCode.animate()
                        .translationXBy(20f).setDuration(50)
                        .withEndAction(() -> binding.etM02ConfirmationCode.animate()
                                .translationXBy(-40f).setDuration(50)
                                .withEndAction(() -> binding.etM02ConfirmationCode.animate()
                                        .translationXBy(20f).setDuration(50)
                                        .withEndAction(() -> {
                                            if (binding != null) {
                                                binding.etM02ConfirmationCode.setTranslationX(0f);
                                                binding.etM02ConfirmationCode.setText("");
                                                binding.etM02ConfirmationCode.requestFocus();
                                                showKeyboard(binding.etM02ConfirmationCode);
                                            }
                                        })))
                        .start();

                Snackbar.make(binding.getRoot(), "승인 코드가 일치하지 않습니다. 다시 입력해 주세요.", Snackbar.LENGTH_SHORT).show();
            } else {
                // 맞았을 때의 로직
                binding.tvM02CodeLoadingText.setText("승인 코드 일치 확인! 백엔드 DB 차량 등록 진행 중...");
                binding.tvM02CodeError.setVisibility(View.GONE);
                confirmSixDigitCodeAndCompleteLinking(pendingRequestId, inputCode, pendingVehicleAlias);
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
     * 2단계: 화면에 표시된 6자리 확인 코드를 차량(C01)과 대조한 뒤 POST /pairing-requests/{id}/confirm으로 최종 연동을 확정한다.
     */
    private void confirmSixDigitCodeAndCompleteLinking(
            @NonNull String requestId,
            @NonNull String confirmationCode,
            @NonNull String vehicleAlias
    ) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            navigateBackToM01();
            return;
        }
        if (binding != null) {
            binding.tvM02QrStatus.setText("상태: 승인 코드 일치 확인! 백엔드 등록 및 차량 확정 진행 중...");
            Snackbar.make(binding.getRoot(), "승인 코드 확인 완료! 백엔드 차량 연동 승인 요청 중...", Snackbar.LENGTH_SHORT).show();
        }

        new Thread(() -> {
            FuelogBackendApiClient.EndToEndVehicleSyncResult result =
                    FuelogBackendApiClient.getInstance(authRepository)
                            .confirmQrPairingWithCode(userCacheManager, requestId, confirmationCode, vehicleAlias);

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (binding == null || !isAdded()) return;
                isCheckingCode = false;
                binding.layoutM02CodeLoading.setVisibility(View.GONE);

                if (result.success && result.vehicleId != null) {
                    if (codeCountDownTimer != null) {
                        codeCountDownTimer.cancel();
                        codeCountDownTimer = null;
                    }
                    String resolvedAlias = result.vehicleName != null && !result.vehicleName.isEmpty()
                            ? result.vehicleName
                            : vehicleAlias;
                    userCacheManager.setRegisteredVehicleUuid(session.getUserId(), result.vehicleId);
                    userCacheManager.setRegisteredVehicleForUser(session.getUserId(), resolvedAlias);

                    M04DriverConnectionCoordinator.getInstance().registerOrUpdateFromQrPairing(
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

                    binding.tvM02QrStatus.setText("상태: 6자리 확인 코드 검증 및 차량 연동 완료 (" + resolvedAlias + ")");
                    Snackbar.make(binding.getRoot(),
                            "6자리 코드(" + confirmationCode + ") 확인 및 차량 연동 완료: " + resolvedAlias,
                            Snackbar.LENGTH_LONG).show();
                    navigateToM03Home();
                } else {
                    binding.tvM02QrStatus.setText("상태: 차량 연동 실패 — 확인 코드를 다시 확인하거나 차량 화면 상태를 확인해 주세요.");
                    Snackbar.make(binding.getRoot(),
                            "차량 연동에 실패했습니다. 승인 코드를 다시 확인해 주세요.",
                            Snackbar.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void bindSessionInfo() {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            navigateBackToM01();
            return;
        }
        String info = "로그인 계정: " + session.getEmail()
                + "\n계정 식별자(sub): " + session.getUserId()
                + "\n연동 방식: 화면 내장 카메라(C01) QR 스캔 + 6자리 확인 코드 대조";
        binding.tvM02SessionInfo.setText(info);
    }

    private void checkPendingDeepLinkPermissionPrompt() {
        Bundle args = getArguments();
        if (args == null) {
            return;
        }
        String permKey = args.getString(M01LoginFragment.ARG_PROMPT_PERMISSION_KEY);
        OptionalPermissionType permType = OptionalPermissionType.fromKey(permKey);
        if (permType != null) {
            args.remove(M01LoginFragment.ARG_PROMPT_PERMISSION_KEY);
            PermissionGuideHelper.executeOrRequestWhenMissing(
                    requireContext(),
                    permType,
                    () -> {
                    },
                    new PermissionGuideHelper.PermissionActionCallback() {
                        @Override
                        public void onProceedToSystemRequest(@NonNull OptionalPermissionType type) {
                            permissionLauncher.launch(type.getAndroidPermissions());
                        }

                        @Override
                        public void onContinueWithoutPermission(@NonNull OptionalPermissionType type) {
                            Snackbar.make(binding.getRoot(),
                                    type.getTitle() + " 없이 계속 이용합니다.",
                                    Snackbar.LENGTH_SHORT).show();
                        }
                    }
            );
        }
    }

    private void onPermissionsResult(Map<String, Boolean> result) {
        if (binding == null) {
            return;
        }
        Boolean camGranted = result.get(Manifest.permission.CAMERA);
        if (Boolean.TRUE.equals(camGranted)) {
            binding.tvM02QrStatus.setText("상태: 내장 카메라 스캐너가 활성화되었습니다. 차량 화면(C01)의 QR 코드를 비춰주세요.");
            binding.barcodeScannerM02.resume();
        } else {
            binding.tvM02QrStatus.setText("상태: 카메라 권한이 거부되었습니다. [카메라 스캔 다시 시작] 또는 [차량(C01) QR 인식]을 이용해 주세요.");
        }
    }

    private void navigateToM03Home() {
        NavOptions navOptions = new NavOptions.Builder()
                .setPopUpTo(R.id.nav_m02_no_vehicle, true)
                .build();
        NavHostFragment.findNavController(this).navigate(R.id.nav_m03_vehicle_home, null, navOptions);
    }

    private void navigateBackToM01() {
        NavOptions navOptions = new NavOptions.Builder()
                .setPopUpTo(R.id.nav_m02_no_vehicle, true)
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
            binding.barcodeScannerM02.pause();
        }
        super.onDestroyView();
        binding = null;
    }
}
