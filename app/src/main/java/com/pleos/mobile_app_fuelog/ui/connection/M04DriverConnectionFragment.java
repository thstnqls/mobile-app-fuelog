package com.pleos.mobile_app_fuelog.ui.connection;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.databinding.FragmentM04DriverConnectionBinding;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.connection.M04ConnectionStage;
import com.pleos.mobile_app_fuelog.data.connection.M04DriverConnectionCoordinator;
import com.pleos.mobile_app_fuelog.data.connection.RegisteredPleosDevice;
import com.pleos.mobile_app_fuelog.data.connection.TripAuthProof;
import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.home.TripRecord;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * [운행 탭] M04. 휴대폰 연결·이번 운행 사용자 & 운행 귀속 화면.
 * - 차량 연동은 Bluetooth SPP 없이 차량 화면(C01/C03)의 QR 코드 인식을 통해 수행한다.
 * - [운행 탭]에서는 '이번 운행에 내 계정 사용(QR·온라인 인증)'과 최근 운행 기록·귀속 확인에 집중한다.
 */
public class M04DriverConnectionFragment extends Fragment {

    private FragmentM04DriverConnectionBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private M04DriverConnectionCoordinator coordinator;
    private M03HomeRepository homeRepository;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentM04DriverConnectionBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authRepository = AuthRepository.getInstance(requireContext());
        userCacheManager = UserCacheManager.getInstance(requireContext());
        coordinator = M04DriverConnectionCoordinator.getInstance();
        homeRepository = new M03HomeRepository();

        String cachedVehicle = userCacheManager.getVisibleCachedVehicleName();
        String uid = userCacheManager.getLastAuthenticatedUserId();
        String cachedUuid = uid != null ? userCacheManager.getRegisteredVehicleUuid(uid) : null;
        if (cachedVehicle != null && !cachedVehicle.isEmpty()) {
            coordinator.setVehicleAlias(cachedVehicle);
            homeRepository.clearAllAndInitVehicle(cachedUuid, cachedVehicle);
        }

        setupListeners();
        renderState();
        renderDrivingTrips();
        syncDrivingTripsFromBackend();
    }

    private void syncDrivingTripsFromBackend() {
        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient apiClient = FuelogBackendApiClient.getInstance(authRepository);
            FuelogBackendApiClient.InboxPageResponse page = apiClient.fetchMobileInbox(0L);
            if (page.success && !page.items.isEmpty()) {
                homeRepository.applyBackendInboxItems(page.items);
                if (isAdded() && binding != null) {
                    requireActivity().runOnUiThread(this::renderDrivingTrips);
                }
            }
        });
    }

    private void setupListeners() {
        binding.btnM04GoManageSettings.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.nav_manage_settings)
        );

        binding.btnM04UseMyAccount.setOnClickListener(v -> executeQrAuthenticationFlow(null));

        binding.btnM04QrOnlineFallback.setOnClickListener(v -> showQrOnlineConfirmationDialog());

        binding.switchM04VehicleDriving.setChecked(coordinator.isVehicleDriving());
        binding.switchM04VehicleDriving.setOnCheckedChangeListener((buttonView, isChecked) -> {
            coordinator.setVehicleDriving(isChecked);
            renderState();
        });

        binding.btnM04SimBtAudioOnly.setOnClickListener(v -> {
            coordinator.onBluetoothAudioConnectedOnly();
            renderState();
        });

        binding.btnM04SimDualPhone.setOnClickListener(v -> {
            coordinator.setOtherPhoneOccupyingSession("other_phone_galaxy_s25");
            try {
                coordinator.startUseMyAccountForCurrentTrip("u1", "car@fuelog.com", "nonce_dual", System.currentTimeMillis() / 1000L);
            } catch (IllegalStateException ignored) {
            }
            coordinator.setOtherPhoneOccupyingSession(null);
            renderState();
        });

        binding.btnM04SimEndedTripReconnect.setOnClickListener(v -> {
            coordinator.onReconnectAttempt(M04DriverConnectionCoordinator.ENDED_TRIP_SESSION_ID);
            renderState();
        });
    }

    private void executeQrAuthenticationFlow(@Nullable String scannedQrPayload) {
        AuthSession session = authRepository.getCurrentValidSession();
        String userId = session != null ? session.getUserId() : "user_unauthenticated";
        String userEmail = session != null ? session.getEmail() : "unauthenticated@fuelog.local";

        if (coordinator.getRegisteredDevices().isEmpty()) {
            String vAlias = coordinator.getVehicleAlias();
            coordinator.registerOrUpdateFromQrPairing(
                    new RegisteredPleosDevice(
                            "qr_c03_device",
                            "C01/C03 QR 연동 차량 단말",
                            "QR:C03",
                            vAlias,
                            true,
                            true
                    ),
                    M04DriverConnectionCoordinator.DEFAULT_VEHICLE_ID,
                    vAlias
            );
        }

        String challengeNonce = "qr_c03_" + UUID.randomUUID().toString().substring(0, 8);
        long nowSec = System.currentTimeMillis() / 1000L;
        final TripAuthProof proof;
        try {
            proof = coordinator.startUseMyAccountForCurrentTrip(userId, userEmail, challengeNonce, nowSec);
        } catch (IllegalStateException e) {
            renderState();
            return;
        }

        binding.btnM04UseMyAccount.setEnabled(false);
        renderState();
        binding.tvM04ProofInfo.setVisibility(View.VISIBLE);
        binding.tvM04ProofInfo.setText(
                "전송 채널: 차량 화면(C03) QR / 온라인 인증 요청 보냄\n"
                        + "단기 증명 ID: " + proof.getProofId()
                        + " (차량=" + proof.getVehicleId()
                        + ", 운행=" + proof.getTripSessionId()
                        + ", 챌린지=" + proof.getChallengeNonce() + ")"
        );

        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient apiClient = FuelogBackendApiClient.getInstance(authRepository);
            FuelogBackendApiClient.EndToEndVehicleSyncResult result =
                    scannedQrPayload != null && !scannedQrPayload.isEmpty()
                            ? apiClient.recognizeQrAndLinkVehicle(userCacheManager, scannedQrPayload, coordinator.getVehicleAlias())
                            : apiClient.authenticateAndVerifyTripUserEndToEnd(userCacheManager, coordinator.getVehicleAlias());

            if (!isAdded() || getActivity() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                binding.btnM04UseMyAccount.setEnabled(true);
                coordinator.onVehicleC03ResponseReceived(
                        proof,
                        challengeNonce,
                        System.currentTimeMillis() / 1000L
                );
                if (result.success) {
                    if (result.vehicleName != null && !result.vehicleName.isEmpty()) {
                        coordinator.setVehicleAlias(result.vehicleName);
                    }
                    binding.tvM04ProofInfo.setVisibility(View.VISIBLE);
                    binding.tvM04ProofInfo.setText(
                            "백엔드 API(C02/C03/M04) QR 인증 연동 완료:\n"
                                    + "• vehicleId: " + (result.vehicleId != null ? result.vehicleId : proof.getVehicleId()) + "\n"
                                    + "• installationId: " + (result.installationId != null ? result.installationId : "-") + "\n"
                                    + "• intervalId: " + (result.intervalId != null ? result.intervalId : proof.getTripSessionId()) + "\n"
                                    + "• consentId/candidateId: " + (result.consentId != null ? result.consentId : proof.getProofId())
                    );
                }
                renderState();
            });
        });
    }

    private void showQrOnlineConfirmationDialog() {
        AuthSession session = authRepository.getCurrentValidSession();
        String userId = session != null ? session.getUserId() : "user_unauthenticated";
        String userEmail = session != null ? session.getEmail() : "unauthenticated@fuelog.local";

        try {
            String qrPayload = coordinator.generateQrAndOnlinePinPayload(userId, userEmail);
            TripAuthProof proof = coordinator.getLatestIssuedProof();
            renderState();

            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("앱 내 차량(C03) QR 코드 인식 / 온라인 확인")
                    .setMessage("외부 카메라 액티비티 이동 없이 앱 내에서 차량(Pleos C03) QR 코드 및 온라인 인증을 확인합니다.\n\n"
                            + "• 단기 증명 코드: " + (proof != null ? proof.getProofId() : "-") + "\n"
                            + "• 페이로드:\n" + qrPayload)
                    .setPositiveButton("앱 내 C03 QR 인식 · 확인 완료", (d, w) -> {
                        if (proof != null) {
                            executeQrAuthenticationFlow(null);
                        }
                    })
                    .setNegativeButton("닫기", null)
                    .show();
        } catch (IllegalStateException e) {
            renderState();
        }
    }

    private void renderState() {
        if (binding == null) {
            return;
        }
        M04ConnectionStage stage = coordinator.getCurrentStage();
        binding.tvM04VehicleAlias.setText(coordinator.getVehicleAlias());

        String stageLabel = coordinator.isDeferredUntilStopped()
                ? "정차 후 적용 (보류)"
                : (coordinator.isRequestSentWaitingAck() ? "요청 보냄 (확인 대기)" : stage.getStageLabel());
        binding.tvM04StageBadge.setText(stageLabel);

        String pipeline = "단계: 미등록 → 권한 필요 → 연결 중 → 앱 인증 중 → 차량 확인 대기(요청 보냄) → 사용 중 / 만료 / 실패\n"
                + "현재 단계: [" + stageLabel + "]";
        binding.tvM04StagePipeline.setText(pipeline);

        boolean btAudio = coordinator.isBluetoothAudioConnected();
        binding.tvM04BtAudioStatus.setText(btAudio ? "BT 오디오: 연결됨 (인증 아님)" : "BT 오디오: 일반 대기");
        binding.tvM04BtAudioStatus.setBackgroundColor(ContextCompat.getColor(
                requireContext(),
                btAudio ? R.color.fuelog_input_error_bg : R.color.fuelog_info_bg
        ));
        binding.tvM04BtAudioStatus.setTextColor(ContextCompat.getColor(
                requireContext(),
                btAudio ? R.color.fuelog_input_error_text : R.color.fuelog_text_secondary
        ));

        boolean appAuthed = coordinator.isFuelogAppAuthenticated();
        binding.tvM04AppAuthStatus.setText(
                appAuthed
                        ? "fuelog 앱 인증: 사용 중(완료)"
                        : (coordinator.isRequestSentWaitingAck() ? "fuelog 앱 인증: 요청 보냄" : "fuelog 앱 인증: 미인증")
        );
        binding.tvM04AppAuthStatus.setBackgroundColor(ContextCompat.getColor(
                requireContext(),
                appAuthed ? R.color.fuelog_primary : R.color.fuelog_info_bg
        ));
        binding.tvM04AppAuthStatus.setTextColor(ContextCompat.getColor(
                requireContext(),
                appAuthed ? R.color.white : R.color.fuelog_primary_dark
        ));

        binding.tvM04StatusDetail.setText(coordinator.getStatusDetailMessage());
    }

    private void renderDrivingTrips() {
        if (binding == null) {
            return;
        }
        binding.containerM04DrivingTrips.removeAllViews();
        List<TripRecord> trips = homeRepository.getRecentTripsForSelectedVehicle();
        if (trips.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("아직 백엔드 DB에 동기화된 운행 기록이 없습니다.\n홈 화면에서 운행 요약(C04) 동기화를 실행하면 DB에서 불러옵니다.");
            empty.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_secondary));
            empty.setTextSize(13f);
            binding.containerM04DrivingTrips.addView(empty);
            return;
        }

        for (TripRecord trip : trips) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(30, 26, 30, 26);
            row.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.fuelog_surface));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            lp.topMargin = 14;
            row.setLayoutParams(lp);

            LinearLayout top = new LinearLayout(requireContext());
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView routeTv = new TextView(requireContext());
            routeTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            routeTv.setText(trip.getRouteSummary());
            routeTv.setTypeface(null, Typeface.BOLD);
            routeTv.setTextSize(14f);
            routeTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_primary));
            top.addView(routeTv);

            TextView badge = new TextView(requireContext());
            badge.setText(trip.getAttributionBadge());
            badge.setTextSize(11f);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setPadding(20, 8, 20, 8);
            badge.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.fuelog_info_bg));
            badge.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_primary_dark));
            top.addView(badge);

            row.addView(top);

            TextView sub = new TextView(requireContext());
            sub.setText(String.format(Locale.KOREA,
                    "%s · %.1f km · 추정 %,d원",
                    trip.getDateTimeLabel(),
                    trip.getDistanceKm(),
                    trip.getEstimatedFuelConsumptionWon()));
            sub.setTextSize(12f);
            sub.setTextColor(ContextCompat.getColor(requireContext(), R.color.fuelog_text_secondary));
            sub.setPadding(0, 8, 0, 0);
            row.addView(sub);

            row.setOnClickListener(v -> showTripScopeSelectDialog(trip));
            binding.containerM04DrivingTrips.addView(row);
        }
    }

    private void showTripScopeSelectDialog(TripRecord trip) {
        String[] labels = new String[]{"개인 운행", "업무 운행", "공동 운행"};

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("운행 귀속 변경 — " + trip.getRouteSummary())
                .setItems(labels, (d, which) -> {
                    Snackbar.make(binding.getRoot(),
                            "운행 귀속이 '" + labels[which] + "'(으)로 선택되었습니다.",
                            Snackbar.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
