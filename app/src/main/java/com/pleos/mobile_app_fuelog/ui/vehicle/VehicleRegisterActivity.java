package com.pleos.mobile_app_fuelog.ui.vehicle;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.core.security.SafeLogger;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.connection.M04DriverConnectionCoordinator;
import com.pleos.mobile_app_fuelog.data.connection.RegisteredPleosDevice;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.databinding.ActivityVehicleRegisterBinding;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * C01 QR 코드 인식 및 차량 신규 등록/연동 전용 액티비티.
 * 관리 화면 및 대시보드 화면과 완전히 분리되어 독립된 카메라 라이프사이클을 갖는다.
 */
public class VehicleRegisterActivity extends AppCompatActivity {

    public static final String EXTRA_REGISTERED_VEHICLE_NAME = "extra_registered_vehicle_name";
    public static final String EXTRA_REGISTERED_VEHICLE_ID = "extra_registered_vehicle_id";

    private ActivityVehicleRegisterBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private M04DriverConnectionCoordinator coordinator;

    private boolean isProcessingQr = false;
    @Nullable
    private String pendingRequestId = null;
    @Nullable
    private String pendingConfirmationCode = null;
    @NonNull
    private String pendingVehicleAlias = "";

    @Nullable
    private CountDownTimer codeCountDownTimer = null;
    private int codeFailureCount = 0;
    private boolean isCheckingCode = false;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityVehicleRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = AuthRepository.getInstance(this);
        userCacheManager = UserCacheManager.getInstance(this);
        coordinator = M04DriverConnectionCoordinator.getInstance();

        setupToolbar();
        setupBarcodeScanner();
        setupListeners();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbarVehicleRegister);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        binding.toolbarVehicleRegister.setNavigationOnClickListener(v -> finish());
    }

    private void setupBarcodeScanner() {
        binding.barcodeScannerRegister.getBarcodeView().setDecoderFactory(
                new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE))
        );
        binding.barcodeScannerRegister.setStatusText("차량 디스플레이의 QR 코드를 사각형 안에 맞춰주세요");
        binding.barcodeScannerRegister.decodeContinuous(result -> {
            if (binding == null || isProcessingQr || result.getText() == null || result.getText().trim().isEmpty()) {
                return;
            }
            isProcessingQr = true;
            binding.barcodeScannerRegister.pause();
            String scannedQr = result.getText().trim();
            requestSixDigitConfirmationCode(scannedQr, "");
        });
    }

    private void startCameraWithPermissionCheck() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            if (binding != null) {
                binding.barcodeScannerRegister.resume();
            }
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        }
    }

    private void onPermissionsResult(Map<String, Boolean> result) {
        if (binding == null) return;
        Boolean camGranted = result.get(Manifest.permission.CAMERA);
        if (Boolean.TRUE.equals(camGranted)) {
            binding.barcodeScannerRegister.resume();
        } else {
            Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null && !isProcessingQr) {
            startCameraWithPermissionCheck();
        }
    }

    @Override
    protected void onPause() {
        if (binding != null) {
            binding.barcodeScannerRegister.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (codeCountDownTimer != null) {
            codeCountDownTimer.cancel();
            codeCountDownTimer = null;
        }
        super.onDestroy();
    }

    private void setupListeners() {
        binding.btnRegisterScanQrCamera.setOnClickListener(v -> {
            isProcessingQr = false;
            pendingRequestId = null;
            pendingConfirmationCode = null;
            codeFailureCount = 0;
            isCheckingCode = false;
            if (codeCountDownTimer != null) {
                codeCountDownTimer.cancel();
                codeCountDownTimer = null;
            }
            binding.layoutRegisterConfirmationCode.setVisibility(View.GONE);
            startCameraWithPermissionCheck();
        });

        binding.etRegisterConfirmationCode.addTextChangedListener(new TextWatcher() {
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
    }

    private void requestSixDigitConfirmationCode(@NonNull String scannedQrPayload, @NonNull String preferredAlias) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            Toast.makeText(this, "유효한 로그인 세션이 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Snackbar.make(binding.getRoot(), "QR 코드를 검증하고 6자리 비교 확인 코드를 발급받습니다...", Snackbar.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient.QrIntentCodeResult codeResult =
                    FuelogBackendApiClient.getInstance(authRepository)
                            .submitQrIntentForConfirmationCode(userCacheManager, scannedQrPayload, preferredAlias);
            runOnUiThread(() -> {
                if (isFinishing() || binding == null) return;
                if (codeResult.success && codeResult.confirmationCode != null && codeResult.requestId != null) {
                    pendingRequestId = codeResult.requestId;
                    pendingConfirmationCode = codeResult.confirmationCode;
                    pendingVehicleAlias = codeResult.vehicleName != null ? codeResult.vehicleName : preferredAlias;

                    binding.layoutRegisterConfirmationCode.setVisibility(View.VISIBLE);
                    binding.etRegisterConfirmationCode.setText("");
                    binding.etRegisterConfirmationCode.setEnabled(true);
                    binding.etRegisterConfirmationCode.requestFocus();

                    startCodeCountdownTimer();

                    binding.tvRegisterConfirmationCodeGuide.setText(
                            "차량 디스플레이 화면에 표시된 6자리 승인 코드를 입력해 주세요.\n"
                                    + "(대상 차량: " + pendingVehicleAlias + ")"
                    );
                    Snackbar.make(binding.getRoot(),
                            "120초 이내에 차량 디스플레이의 6자리 승인 코드를 입력해 주세요.",
                            Snackbar.LENGTH_SHORT).show();
                } else {
                    isProcessingQr = false;
                    startCameraWithPermissionCheck();
                    Snackbar.make(binding.getRoot(),
                            "QR 코드 검증 실패: " + (codeResult.errorMessage != null ? codeResult.errorMessage : "유효한 QR 코드를 비춰주세요."),
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
        codeCountDownTimer = new CountDownTimer(120_000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (binding == null) return;
                long totalSeconds = millisUntilFinished / 1000;
                long minutes = totalSeconds / 60;
                long seconds = totalSeconds % 60;
                binding.tvRegisterCodeTimer.setText(
                        String.format(Locale.KOREA, "남은 입력 시간: %02d:%02d", minutes, seconds)
                );
            }

            @Override
            public void onFinish() {
                if (binding == null) return;
                binding.tvRegisterCodeTimer.setText("승인 코드 유효 시간이 만료되었습니다.");
                binding.etRegisterConfirmationCode.setEnabled(false);
                pendingRequestId = null;
                pendingConfirmationCode = null;
                isProcessingQr = false;
                Snackbar.make(binding.getRoot(),
                        "입력 시간이 초과되었습니다. QR 코드를 다시 스캔해 주세요.",
                        Snackbar.LENGTH_LONG).show();
                startCameraWithPermissionCheck();
            }
        }.start();
    }

    private void attemptConfirmationWithEnteredCode() {
        if (binding == null || isCheckingCode) return;
        String enteredCode = binding.etRegisterConfirmationCode.getText() != null
                ? binding.etRegisterConfirmationCode.getText().toString().trim()
                : "";
        if (enteredCode.length() != 6 || pendingRequestId == null) return;

        isCheckingCode = true;
        binding.tvRegisterCodeError.setVisibility(View.GONE);
        binding.layoutRegisterCodeLoading.setVisibility(View.VISIBLE);
        binding.tvRegisterCodeLoadingText.setText("승인 코드 일치 확인 중...");

        View view = getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }

        if (pendingConfirmationCode != null && !enteredCode.equals(pendingConfirmationCode.trim())) {
            codeFailureCount++;
            binding.layoutRegisterCodeLoading.setVisibility(View.GONE);
            isCheckingCode = false;
            binding.tvRegisterCodeError.setVisibility(View.VISIBLE);
            binding.tvRegisterCodeError.setText(
                    "입력하신 승인 코드(" + enteredCode + ")가 차량 화면과 일치하지 않습니다. (오류 " + codeFailureCount + "/3회)"
            );
            if (codeFailureCount >= 3) {
                if (codeCountDownTimer != null) {
                    codeCountDownTimer.cancel();
                    codeCountDownTimer = null;
                }
                pendingRequestId = null;
                pendingConfirmationCode = null;
                isProcessingQr = false;
                binding.layoutRegisterConfirmationCode.setVisibility(View.GONE);
                Snackbar.make(binding.getRoot(),
                        "3회 오류로 세션이 취소되었습니다. QR 코드를 다시 스캔해 주세요.",
                        Snackbar.LENGTH_LONG).show();
                startCameraWithPermissionCheck();
            }
            return;
        }

        binding.tvRegisterCodeLoadingText.setText("코드 일치! 백엔드 DB 차량 등록 완료 중...");
        completeBackendVehicleConfirmation(enteredCode);
    }

    private void completeBackendVehicleConfirmation(@NonNull String confirmationCode) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null || pendingRequestId == null) {
            isCheckingCode = false;
            if (binding != null) binding.layoutRegisterCodeLoading.setVisibility(View.GONE);
            return;
        }

        final String reqId = pendingRequestId;
        final String preferredAlias = pendingVehicleAlias;

        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient client = FuelogBackendApiClient.getInstance(authRepository);
            FuelogBackendApiClient.EndToEndVehicleSyncResult result =
                    client.confirmQrPairingWithCode(userCacheManager, reqId, confirmationCode, preferredAlias);

            String vehicleId = result.vehicleId;
            String vehicleName = result.vehicleName != null && !result.vehicleName.isEmpty()
                    ? result.vehicleName
                    : preferredAlias;

            runOnUiThread(() -> {
                if (isFinishing() || binding == null) return;
                isProcessingQr = false;
                isCheckingCode = false;
                binding.layoutRegisterCodeLoading.setVisibility(View.GONE);

                if (result.success && vehicleId != null) {
                    if (codeCountDownTimer != null) {
                        codeCountDownTimer.cancel();
                        codeCountDownTimer = null;
                    }
                    userCacheManager.setRegisteredVehicleUuid(session.getUserId(), vehicleId);
                    userCacheManager.setRegisteredVehicleForUser(session.getUserId(), vehicleName);
                    coordinator.registerOrUpdateFromQrPairing(
                            new RegisteredPleosDevice(
                                    "qr_" + vehicleId,
                                    "연동 차량",
                                    "QR:" + vehicleId.substring(0, Math.min(8, vehicleId.length())),
                                    vehicleName,
                                    true,
                                    true
                            ),
                            vehicleId,
                            vehicleName
                    );

                    Toast.makeText(
                            this,
                            "차량 연동이 완료되었습니다: " + vehicleName,
                            Toast.LENGTH_LONG
                    ).show();

                    android.content.Intent resultIntent = new android.content.Intent();
                    resultIntent.putExtra(EXTRA_REGISTERED_VEHICLE_NAME, vehicleName);
                    resultIntent.putExtra(EXTRA_REGISTERED_VEHICLE_ID, vehicleId);
                    setResult(RESULT_OK, resultIntent);
                    finish();
                } else {
                    Snackbar.make(binding.getRoot(),
                            "차량 연동에 실패했습니다. 승인 코드와 차량 화면 상태를 확인해 주세요.",
                            Snackbar.LENGTH_LONG).show();
                    startCameraWithPermissionCheck();
                }
            });
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
