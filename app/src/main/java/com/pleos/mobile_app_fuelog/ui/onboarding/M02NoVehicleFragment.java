package com.pleos.mobile_app_fuelog.ui.onboarding;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
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

import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;

/**
 * M02 차량 미등록 안내 및 차량 화면(C01) QR 코드 인식 전용 연동 화면.
 * - Bluetooth SPP 및 수동 번호판 입력 없이 오직 QR 코드 인식(카메라 스캔 또는 C01 QR 페이로드 인식)으로만 차량을 연동한다.
 * - 백엔드 API (POST /pairing-requests/preview, POST /pairing-requests/{id}/intent, POST /pairing-requests/{id}/confirm)와 연동한다.
 */
public class M02NoVehicleFragment extends Fragment {

    private FragmentM02NoVehicleBinding binding;
    private M01LoginViewModel viewModel;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionsResult);

    private final ActivityResultLauncher<ScanOptions> qrBarcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() == null || result.getContents().trim().isEmpty()) {
                    if (binding != null) {
                        binding.tvM02QrStatus.setText("상태: 카메라 QR 스캔이 취소되었습니다. 다시 스캔하거나 C01 QR 코드 인식을 진행해 주세요.");
                    }
                } else {
                    String scannedQr = result.getContents().trim();
                    if (binding != null) {
                        binding.tvM02QrStatus.setText("상태: QR 코드 인식 완료 · 백엔드 차량 연동 검증 중...");
                    }
                    executeQrVehicleLinking(scannedQr, "QR 연동 차량");
                }
            });

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
        checkPendingDeepLinkPermissionPrompt();

        // 1. 카메라로 차량(C01) QR 코드 스캔
        binding.btnM02ScanQrCamera.setOnClickListener(v -> startCameraQrScanWithPermissionCheck());

        // 2. 차량(C01) QR 코드 인식 · 연동 다이얼로그 (백엔드 C01 QR 발급/미리보기/승인 통합)
        binding.btnM02RecognizeC01Qr.setOnClickListener(v -> showC01QrRecognitionDialog());

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

    private void startCameraQrScanWithPermissionCheck() {
        PermissionGuideHelper.executeOrRequestWhenMissing(
                requireContext(),
                OptionalPermissionType.CAMERA_QR,
                this::launchZxingCameraScanner,
                new PermissionGuideHelper.PermissionActionCallback() {
                    @Override
                    public void onProceedToSystemRequest(@NonNull OptionalPermissionType permissionType) {
                        permissionLauncher.launch(permissionType.getAndroidPermissions());
                    }

                    @Override
                    public void onContinueWithoutPermission(@NonNull OptionalPermissionType permissionType) {
                        showC01QrRecognitionDialog();
                    }
                }
        );
    }

    private void launchZxingCameraScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("차량 디스플레이(C01)에 표시된 페어링 QR 코드를 사각형 안에 맞춰주세요");
        options.setCameraId(0);
        options.setBeepEnabled(false);
        options.setBarcodeImageEnabled(false);
        options.setOrientationLocked(false);
        qrBarcodeLauncher.launch(options);
    }

    /**
     * 차량 화면(C01)에서 발급된 QR 코드를 인식(또는 외부 C01 QR JSON 페이로드 입력)하여
     * 백엔드 DB에 차량과 계정을 즉시 페어링한다.
     */
    private void showC01QrRecognitionDialog() {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            navigateBackToM01();
            return;
        }
        binding.tvM02QrStatus.setText("상태: 차량(C01) 페어링 QR 요청을 백엔드 서버에서 불러오는 중...");

        new Thread(() -> {
            FuelogBackendApiClient api = FuelogBackendApiClient.getInstance(authRepository);
            String userId = session.getUserId();
            String instId = userCacheManager.getInstallationId(userId);
            String instSecret = userCacheManager.getInstallationSecret(userId);

            if (instId == null || instSecret == null) {
                FuelogBackendApiClient.InstallationIssuedResponse issued = api.createInstallation();
                if (issued != null) {
                    instId = issued.installationId;
                    instSecret = issued.installationSecret;
                    userCacheManager.setInstallationCredentials(userId, instId, instSecret);
                }
            }

            String qrPayloadJson = "";
            if (instId != null && instSecret != null) {
                FuelogBackendApiClient.PairingQrIssuedResponse qrIssued =
                        api.issuePairingRequest(instId, instSecret, "c01-qr-" + UUID.randomUUID());
                if (qrIssued != null) {
                    try {
                        JSONObject qrObj = new JSONObject();
                        qrObj.put("v", 1);
                        qrObj.put("type", "fuelog.vehicle_pairing");
                        qrObj.put("request_id", qrIssued.requestId);
                        qrObj.put("proof", qrIssued.proof);
                        qrPayloadJson = qrObj.toString();
                    } catch (Exception ignored) {
                    }
                }
            }

            final String finalQrPayload = qrPayloadJson;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (binding == null || !isAdded()) return;
                binding.tvM02QrStatus.setText("상태: 차량(C01) QR 코드가 감지되었습니다. 인식을 승인해 차량을 연동하세요.");

                LinearLayout layout = new LinearLayout(requireContext());
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(56, 32, 56, 16);
                layout.setGravity(Gravity.CENTER_HORIZONTAL);

                if (!finalQrPayload.isEmpty()) {
                    try {
                        BarcodeEncoder encoder = new BarcodeEncoder();
                        Bitmap qrBitmap = encoder.encodeBitmap(finalQrPayload, BarcodeFormat.QR_CODE, 460, 460);
                        ImageView qrImageView = new ImageView(requireContext());
                        qrImageView.setImageBitmap(qrBitmap);
                        layout.addView(qrImageView);
                    } catch (Exception ignored) {
                    }
                }

                TextView guideTv = new TextView(requireContext());
                guideTv.setText("차량 화면(C01)에서 발급된 QR 페이로드입니다.\n차량 별칭을 지정하고 [QR 인식 및 연동 승인]을 누르면 백엔드 DB에 차량이 연동됩니다.");
                guideTv.setTextSize(12f);
                guideTv.setPadding(0, 16, 0, 12);
                layout.addView(guideTv);

                final EditText etVehicleAlias = new EditText(requireContext());
                etVehicleAlias.setHint("연동할 차량 별칭 (예: 내 차량)");
                etVehicleAlias.setInputType(InputType.TYPE_CLASS_TEXT);
                etVehicleAlias.setText("QR 연동 차량");
                layout.addView(etVehicleAlias);

                final EditText etQrPayload = new EditText(requireContext());
                etQrPayload.setHint("C01 QR JSON 페이로드 (자동 인식됨)");
                etQrPayload.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                etQrPayload.setMaxLines(3);
                etQrPayload.setTextSize(11f);
                etQrPayload.setText(finalQrPayload);
                layout.addView(etQrPayload);

                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("차량 화면(C01) QR 코드 인식")
                        .setView(layout)
                        .setPositiveButton("QR 인식 및 연동 승인", (d, which) -> {
                            String scannedPayload = etQrPayload.getText() != null
                                    ? etQrPayload.getText().toString().trim()
                                    : finalQrPayload;
                            String alias = etVehicleAlias.getText() != null
                                    && !etVehicleAlias.getText().toString().trim().isEmpty()
                                    ? etVehicleAlias.getText().toString().trim()
                                    : "QR 연동 차량";
                            executeQrVehicleLinking(scannedPayload, alias);
                        })
                        .setNeutralButton("카메라 스캔 열기", (d, which) -> startCameraQrScanWithPermissionCheck())
                        .setNegativeButton("취소", null)
                        .show();
            });
        }).start();
    }

    private void executeQrVehicleLinking(@NonNull String scannedQrPayload, @NonNull String preferredAlias) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            navigateBackToM01();
            return;
        }
        if (binding != null) {
            binding.tvM02QrStatus.setText("상태: QR 코드 검증 및 백엔드 차량 페어링 진행 중...");
            binding.btnM02ScanQrCamera.setEnabled(false);
            binding.btnM02RecognizeC01Qr.setEnabled(false);
        }

        new Thread(() -> {
            FuelogBackendApiClient.EndToEndVehicleSyncResult result =
                    FuelogBackendApiClient.getInstance(authRepository)
                            .recognizeQrAndLinkVehicle(userCacheManager, scannedQrPayload, preferredAlias);

            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (binding == null || !isAdded()) return;
                binding.btnM02ScanQrCamera.setEnabled(true);
                binding.btnM02RecognizeC01Qr.setEnabled(true);

                if (result.success && result.vehicleId != null) {
                    String resolvedAlias = result.vehicleName != null && !result.vehicleName.isEmpty()
                            ? result.vehicleName
                            : preferredAlias;
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

                    binding.tvM02QrStatus.setText("상태: QR 차량 연동 완료 (" + resolvedAlias + ")");
                    Snackbar.make(binding.getRoot(),
                            "QR 인식 차량 연동 완료: " + resolvedAlias + " (ID: " + result.vehicleId + ")",
                            Snackbar.LENGTH_LONG).show();
                    navigateToM03Home();
                } else {
                    binding.tvM02QrStatus.setText("상태: QR 코드 검증에 실패했습니다. 유효한 C01 QR 코드인지 확인해 주세요.");
                    Snackbar.make(binding.getRoot(),
                            "QR 코드 검증 실패: 유효한 차량 페어링 QR 코드를 스캔해 주세요.",
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
                + "\n연동 방식: 차량 디스플레이(C01) QR 코드 인식 전용";
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
        boolean anyGranted = false;
        for (Boolean granted : result.values()) {
            if (Boolean.TRUE.equals(granted)) {
                anyGranted = true;
                break;
            }
        }
        if (anyGranted) {
            launchZxingCameraScanner();
        } else {
            Snackbar.make(binding.getRoot(),
                    "카메라 권한이 거부되어 C01 QR 코드 인식 창을 표시합니다.",
                    Snackbar.LENGTH_SHORT).show();
            showC01QrRecognitionDialog();
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
        super.onDestroyView();
        binding = null;
    }
}
