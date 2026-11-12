package com.pleos.mobile_app_fuelog.data.connection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * M04. 휴대폰 연결·이번 운행 사용자 상태 머신 및 보안 검증기.
 *
 * - FR-M04-1: Bluetooth 일반 오디오 연결과 fuelog 앱 인증을 엄격히 분리. 오디오 연결만으로 '사용자 인증 완료' 표시 금지.
 * - FR-M04-2: 짧은 수명의 차량·운행·챌린지 결합 증명을 전송하며, 차량(C03) 완료 응답 전까지 '요청 보냄'으로 표시.
 * - FR-M04-3: 주행 중에는 '정차 후 적용'으로 보류하고 차량 C03과 일치시킴. 연결 해제/배터리 절약/앱 종료 상태 안내.
 * - 예외/완료:
 *   1) QR 인식 및 온라인 확인 방식으로 차량 연동 및 운행 사용자 인증 수행.
 *   2) 두 폰 동시 연결 · 증명 재사용(Replay) · 멤버 탈퇴 · 권한 철회에서 오인증 없음.
 *   3) 재연결 시 이미 끝난 운행을 가져오지 않는다.
 */
public final class M04DriverConnectionCoordinator {

    public static final String DEFAULT_VEHICLE_ID = "veh_qr_unlinked";
    public static final String DEFAULT_TRIP_SESSION_ID = "trip_live_20261103_01";
    public static final String ENDED_TRIP_SESSION_ID = "trip_ended_20261102_99";

    private static volatile M04DriverConnectionCoordinator instance;

    public static M04DriverConnectionCoordinator getInstance() {
        if (instance == null) {
            synchronized (M04DriverConnectionCoordinator.class) {
                if (instance == null) {
                    instance = new M04DriverConnectionCoordinator();
                }
            }
        }
        return instance;
    }

    private String vehicleAlias = "차량 미연동 (QR 인식 필요)";
    private String currentVehicleId = DEFAULT_VEHICLE_ID;
    private String currentTripSessionId = DEFAULT_TRIP_SESSION_ID;
    private String localPhoneDeviceId = "phone_pixel10_main";

    private M04ConnectionStage currentStage = M04ConnectionStage.CONNECTING;
    private String statusDetailMessage = "차량 화면(C01/C03) QR 인식으로 연동할 준비가 되었습니다.";

    // FR-M04-1: 블루투스 오디오 연결 상태와 fuelog 앱 인증 상태를 별개로 관리
    private boolean bluetoothAudioConnected = false;
    private boolean fuelogAppAuthenticated = false;

    // FR-M04-2: 차량(C03) 완료 응답 전 '요청 보냄' 표시
    private boolean requestSentWaitingAck = false;
    @Nullable
    private TripAuthProof latestIssuedProof;

    // FR-M04-3: 주행 중 '정차 후 적용' 보류 및 환경 상태 안내
    private boolean vehicleDriving = false;
    private boolean deferredUntilStopped = false;
    private boolean batterySaverActive = false;

    // 자동 추천 허용 여부
    private boolean autoRecommendAllowed = true;

    // 예외/완료 방어 상태
    private final Set<String> consumedProofIds = new HashSet<>();
    private final Set<String> endedTripSessionIds = new HashSet<>();
    @Nullable
    private String activeBoundPhoneDeviceId = null;
    private boolean memberWithdrawn = false;

    private final List<RegisteredPleosDevice> registeredDevices = new ArrayList<>();

    public M04DriverConnectionCoordinator() {
        endedTripSessionIds.add(ENDED_TRIP_SESSION_ID);
        registeredDevices.add(new RegisteredPleosDevice(
                "pleos_ivi_01",
                "Pleos Connect IVI (v2.1)",
                "00:11:22:AA:BB:CC",
                vehicleAlias,
                true,
                true
        ));
    }

    /**
     * FR-M04-1: 일반 블루투스 오디오(A2DP/HFP)만 연결된 상태.
     * 절대 '사용자 인증 완료(IN_USE)'로 표시하지 않고, 앱 인증이 별도로 필요함을 표시한다.
     */
    public synchronized void onBluetoothAudioConnectedOnly() {
        this.bluetoothAudioConnected = true;
        this.fuelogAppAuthenticated = false;
        if (this.currentStage != M04ConnectionStage.IN_USE) {
            this.currentStage = M04ConnectionStage.CONNECTING;
            this.statusDetailMessage = "Bluetooth 오디오만 연결됨 (사용자 미인증) — '이번 운행에 내 계정 사용'으로 앱 인증을 진행해 주세요.";
        }
    }

    /**
     * 권한 미허용 시 상태 전환.
     */
    public synchronized void onPermissionMissing() {
        this.fuelogAppAuthenticated = false;
        this.requestSentWaitingAck = false;
        this.currentStage = M04ConnectionStage.PERMISSION_REQUIRED;
        this.statusDetailMessage = "카메라(QR 인식) 권한이 없습니다. 권한을 허용하거나 QR 코드 데이터를 인식해 주세요.";
    }

    /**
     * 예외/완료: 권한 철회 시 즉시 인증 해제.
     */
    public synchronized void onServerDisconnected() {
        fuelogAppAuthenticated = false;
        requestSentWaitingAck = false;
        deferredUntilStopped = false;
        activeBoundPhoneDeviceId = null;
        latestIssuedProof = null;
        currentStage = M04ConnectionStage.CONNECTING;
        statusDetailMessage = "서버에서 차량 연결이 해제되었습니다. 차량 QR을 다시 인식해 주세요.";
    }

    public synchronized void onPermissionRevoked() {
        this.fuelogAppAuthenticated = false;
        this.requestSentWaitingAck = false;
        this.deferredUntilStopped = false;
        this.activeBoundPhoneDeviceId = null;
        this.currentStage = M04ConnectionStage.PERMISSION_REQUIRED;
        this.statusDetailMessage = "권한이 철회되어 연결 및 사용자 인증이 즉시 해제되었습니다.";
    }

    /**
     * 예외/완료: 차량 공유 멤버 탈퇴 시 오인증 차단.
     */
    public synchronized void setMemberWithdrawn(boolean withdrawn) {
        this.memberWithdrawn = withdrawn;
        if (withdrawn) {
            this.fuelogAppAuthenticated = false;
            this.requestSentWaitingAck = false;
            this.deferredUntilStopped = false;
            this.activeBoundPhoneDeviceId = null;
            this.currentStage = M04ConnectionStage.FAILED;
            this.statusDetailMessage = "차량 멤버에서 탈퇴된 계정입니다. 이번 운행 사용자로 인증할 수 없습니다.";
        }
    }

    public synchronized boolean isMemberWithdrawn() {
        return memberWithdrawn;
    }

    /**
     * FR-M04-2: '이번 운행에 내 계정 사용' 요청 시작.
     * 서버가 발급한 짧은 수명의 차량·운행·챌린지 결합 증명을 생성해 SPP로 전송하고,
     * 차량(C03)의 완료 응답 전까지는 반드시 '요청 보냄(WAITING_VEHICLE_CONFIRM)'으로 표시한다.
     */
    @NonNull
    public synchronized TripAuthProof startUseMyAccountForCurrentTrip(
            @NonNull String userId,
            @NonNull String userEmail,
            @NonNull String challengeNonce,
            long nowEpochSeconds
    ) {
        // 1) 멤버 탈퇴 여부 검사
        if (memberWithdrawn) {
            this.currentStage = M04ConnectionStage.FAILED;
            this.statusDetailMessage = "멤버 탈퇴 계정은 운행 사용자 인증이 차단됩니다.";
            throw new IllegalStateException(statusDetailMessage);
        }

        // 2) 등록 기기 유무 검사
        if (registeredDevices.isEmpty()) {
            this.currentStage = M04ConnectionStage.UNREGISTERED;
            this.statusDetailMessage = "등록된 차량 기기가 없습니다. 먼저 기기를 등록해 주세요.";
            throw new IllegalStateException(statusDetailMessage);
        }

        // 3) 이미 끝난 운행 세션인지 검사
        if (endedTripSessionIds.contains(currentTripSessionId)) {
            this.currentStage = M04ConnectionStage.EXPIRED;
            this.statusDetailMessage = "이미 종료된 운행 세션(" + currentTripSessionId + ")입니다. 이전 운행은 가져오지 않습니다.";
            throw new IllegalStateException(statusDetailMessage);
        }

        // 4) 두 폰 동시 연결 차단 검사
        if (activeBoundPhoneDeviceId != null && !activeBoundPhoneDeviceId.equals(localPhoneDeviceId)) {
            this.currentStage = M04ConnectionStage.FAILED;
            this.fuelogAppAuthenticated = false;
            this.statusDetailMessage = "다른 휴대폰(" + activeBoundPhoneDeviceId + ")이 이미 이번 운행에 연결되어 있어 동시 연결 오인증을 차단했습니다.";
            throw new IllegalStateException(statusDetailMessage);
        }

        // 5) 앱 인증 중 -> 짧은 수명의 증명 발급 후 전송 -> 차량 확인 대기 ('요청 보냄')
        this.currentStage = M04ConnectionStage.APP_AUTHENTICATING;
        TripAuthProof proof = TripAuthProof.issueShortLivedProof(
                userId,
                userEmail,
                localPhoneDeviceId,
                currentVehicleId,
                currentTripSessionId,
                challengeNonce,
                nowEpochSeconds
        );
        this.latestIssuedProof = proof;
        this.requestSentWaitingAck = true;
        this.fuelogAppAuthenticated = false; // 완료 응답 전에는 아직 최종 사용 중이 아님
        this.currentStage = M04ConnectionStage.WAITING_VEHICLE_CONFIRM;

        if (vehicleDriving) {
            this.deferredUntilStopped = true;
            this.statusDetailMessage = "요청 보냄 · 현재 주행 중이므로 '정차 후 적용'으로 보류되었습니다 (차량 C03 화면과 일치).";
        } else {
            this.deferredUntilStopped = false;
            this.statusDetailMessage = "요청 보냄 · 차량(Pleos C03)의 사용자 확인 응답을 기다리고 있습니다.";
        }

        return proof;
    }

    /**
     * 차량(Pleos C03)으로부터 증명 검증 및 확인 응답 수신.
     * - 증명 만료, 재사용(Replay), 타 차량/운행 불일치, 두 폰 충돌, 멤버 탈퇴를 모두 재검증한다.
     * - FR-M04-3: 주행 중(vehicleDriving == true)이면 즉시 적용하지 않고 '정차 후 적용' 보류 상태를 유지한다.
     */
    public synchronized boolean onVehicleC03ResponseReceived(
            @NonNull TripAuthProof submittedProof,
            @NonNull String expectedChallengeNonce,
            long nowEpochSeconds
    ) {
        if (memberWithdrawn) {
            this.currentStage = M04ConnectionStage.FAILED;
            this.fuelogAppAuthenticated = false;
            this.requestSentWaitingAck = false;
            this.statusDetailMessage = "멤버 탈퇴로 인해 차량 인증이 거부되었습니다.";
            return false;
        }

        // 증명 재사용(Replay) 차단
        if (consumedProofIds.contains(submittedProof.getProofId())) {
            this.currentStage = M04ConnectionStage.FAILED;
            this.fuelogAppAuthenticated = false;
            this.requestSentWaitingAck = false;
            this.statusDetailMessage = "이미 사용된 인증 증명(Replay 감지)입니다. 보안을 위해 인증을 차단했습니다.";
            return false;
        }

        // 증명 만료 검사
        if (submittedProof.isExpired(nowEpochSeconds)) {
            this.currentStage = M04ConnectionStage.EXPIRED;
            this.fuelogAppAuthenticated = false;
            this.requestSentWaitingAck = false;
            this.statusDetailMessage = "단기 인증 증명의 유효시간(60초)이 만료되었습니다. 다시 시도해 주세요.";
            return false;
        }

        // 차량·운행·챌린지 바인딩 일치 검사
        if (!submittedProof.matchesTarget(currentVehicleId, currentTripSessionId, expectedChallengeNonce)) {
            this.currentStage = M04ConnectionStage.FAILED;
            this.fuelogAppAuthenticated = false;
            this.requestSentWaitingAck = false;
            this.statusDetailMessage = "차량·운행·챌린지 증명 서명이 일치하지 않아 오인증을 차단했습니다.";
            return false;
        }

        // 두 폰 동시 연결 검사
        if (activeBoundPhoneDeviceId != null
                && !activeBoundPhoneDeviceId.equals(submittedProof.getPhoneDeviceId())) {
            this.currentStage = M04ConnectionStage.FAILED;
            this.fuelogAppAuthenticated = false;
            this.requestSentWaitingAck = false;
            this.statusDetailMessage = "다른 기기가 이미 이번 운행을 점유 중이어서 동시 연결을 거부했습니다.";
            return false;
        }

        // 증명 1회 소비 기록
        consumedProofIds.add(submittedProof.getProofId());
        this.activeBoundPhoneDeviceId = submittedProof.getPhoneDeviceId();

        // FR-M04-3: 주행 중일 때는 '정차 후 적용'으로 보류하고 차량 C03과 일치시킴
        if (vehicleDriving) {
            this.deferredUntilStopped = true;
            this.requestSentWaitingAck = true;
            this.fuelogAppAuthenticated = false;
            this.currentStage = M04ConnectionStage.WAITING_VEHICLE_CONFIRM;
            this.statusDetailMessage = "정차 후 적용 보류 중 (차량 C03 동기화 완료 · 안전을 위해 정차 시 내 계정으로 전환됩니다).";
            return true;
        }

        // 정차 상태: 즉시 '사용 중(IN_USE)' 전환
        this.deferredUntilStopped = false;
        this.requestSentWaitingAck = false;
        this.fuelogAppAuthenticated = true;
        this.currentStage = M04ConnectionStage.IN_USE;
        this.statusDetailMessage = "사용 중 · 이번 운행(" + currentTripSessionId + ")에 내 계정이 등록되었습니다 (차량 C03 일치).";
        return true;
    }

    /**
     * FR-M04-3: 주행 상태 변경 (주행 중 -> 정차 전환 시 보류된 사용자 등록을 즉시 적용).
     */
    public synchronized void setVehicleDriving(boolean driving) {
        this.vehicleDriving = driving;
        if (!driving && deferredUntilStopped && activeBoundPhoneDeviceId != null && !memberWithdrawn) {
            this.deferredUntilStopped = false;
            this.requestSentWaitingAck = false;
            this.fuelogAppAuthenticated = true;
            this.currentStage = M04ConnectionStage.IN_USE;
            this.statusDetailMessage = "차량 정차 감지 · 보류되었던 '이번 운행에 내 계정 사용'이 최종 적용되었습니다 (C03 일치).";
        } else if (driving && currentStage == M04ConnectionStage.WAITING_VEHICLE_CONFIRM) {
            this.deferredUntilStopped = true;
            this.statusDetailMessage = "요청 보냄 · 주행 중이므로 '정차 후 적용'으로 보류됩니다 (차량 C03과 일치).";
        }
    }

    public synchronized boolean isVehicleDriving() {
        return vehicleDriving;
    }

    public synchronized boolean isDeferredUntilStopped() {
        return deferredUntilStopped;
    }

    /**
     * 예외/완료: 재연결 시 이미 끝난 운행을 가져오지 않는다.
     */
    public synchronized boolean onReconnectAttempt(@NonNull String candidateTripSessionId) {
        if (endedTripSessionIds.contains(candidateTripSessionId)) {
            this.fuelogAppAuthenticated = false;
            this.requestSentWaitingAck = false;
            this.currentStage = M04ConnectionStage.CONNECTING;
            this.statusDetailMessage = "재연결 감지: 이미 종료된 이전 운행(" + candidateTripSessionId + ")은 가져오지 않고 새 운행 세션을 대기합니다.";
            return false;
        }
        this.currentTripSessionId = candidateTripSessionId;
        this.statusDetailMessage = "운행 세션(" + candidateTripSessionId + ")에 재연결되었습니다.";
        return true;
    }

    public synchronized void markCurrentTripEnded() {
        endedTripSessionIds.add(currentTripSessionId);
        this.fuelogAppAuthenticated = false;
        this.requestSentWaitingAck = false;
        this.deferredUntilStopped = false;
        this.activeBoundPhoneDeviceId = null;
        this.currentStage = M04ConnectionStage.EXPIRED;
        this.statusDetailMessage = "운행 종료됨: 이번 운행 세션(" + currentTripSessionId + ")이 완료되어 인증이 만료되었습니다.";
    }

    public synchronized void startFreshTripSession() {
        this.currentTripSessionId = "trip_live_" + UUID.randomUUID().toString().substring(0, 6);
        this.fuelogAppAuthenticated = false;
        this.requestSentWaitingAck = false;
        this.deferredUntilStopped = false;
        this.activeBoundPhoneDeviceId = null;
        this.currentStage = M04ConnectionStage.CONNECTING;
        this.statusDetailMessage = "새 운행(" + currentTripSessionId + ")이 시작되었습니다. '이번 운행에 내 계정 사용'을 눌러 연결해 주세요.";
    }

    /**
     * 다른 휴대폰이 먼저 연결된 상황 시뮬레이션/설정 (두 폰 동시 연결 테스트용).
     */
    public synchronized void setOtherPhoneOccupyingSession(@Nullable String otherPhoneId) {
        this.activeBoundPhoneDeviceId = otherPhoneId;
    }

    /**
     * 예외/완료: BLE/SPP 미지원 기기 또는 연결 불가 시 QR / 온라인 확인 방식 제공.
     */
    @NonNull
    public synchronized String generateQrAndOnlinePinPayload(@NonNull String userId, @NonNull String userEmail) {
        String challenge = "qr_chal_" + UUID.randomUUID().toString().substring(0, 8);
        TripAuthProof proof = startUseMyAccountForCurrentTrip(
                userId,
                userEmail,
                challenge,
                System.currentTimeMillis() / 1000L
        );
        return "fuelog://pleos-c03/verify?vehicle=" + currentVehicleId
                + "&trip=" + currentTripSessionId
                + "&proof=" + proof.getProofId()
                + "&nonce=" + challenge;
    }

    @NonNull
    public synchronized M04ConnectionStage getCurrentStage() {
        return currentStage;
    }

    @NonNull
    public synchronized String getStatusDetailMessage() {
        return statusDetailMessage;
    }

    public synchronized boolean isBluetoothAudioConnected() {
        return bluetoothAudioConnected;
    }

    public synchronized boolean isFuelogAppAuthenticated() {
        return fuelogAppAuthenticated;
    }

    public synchronized boolean isRequestSentWaitingAck() {
        return requestSentWaitingAck;
    }

    @Nullable
    public synchronized TripAuthProof getLatestIssuedProof() {
        return latestIssuedProof;
    }

    @NonNull
    public synchronized String getVehicleAlias() {
        return vehicleAlias;
    }

    public synchronized void setVehicleAlias(@NonNull String vehicleAlias) {
        if (!vehicleAlias.trim().isEmpty()) {
            this.vehicleAlias = vehicleAlias.trim();
        }
    }

    public synchronized boolean isAutoRecommendAllowed() {
        return autoRecommendAllowed;
    }

    public synchronized void setAutoRecommendAllowed(boolean autoRecommendAllowed) {
        this.autoRecommendAllowed = autoRecommendAllowed;
    }

    public synchronized boolean isBatterySaverActive() {
        return batterySaverActive;
    }

    public synchronized void setBatterySaverActive(boolean batterySaverActive) {
        this.batterySaverActive = batterySaverActive;
    }

    @NonNull
    public synchronized List<RegisteredPleosDevice> getRegisteredDevices() {
        return new ArrayList<>(registeredDevices);
    }

    public synchronized void registerDevice(@NonNull RegisteredPleosDevice device) {
        registeredDevices.add(device);
        if (currentStage == M04ConnectionStage.UNREGISTERED) {
            currentStage = M04ConnectionStage.CONNECTING;
            statusDetailMessage = "기기(" + device.getDeviceName() + ")가 등록되었습니다.";
        }
    }

    /**
     * 차량 화면(C01/C03)의 QR 코드를 인식하여 차량 ID, 차량 별칭, 연결 기기 정보를 등록·갱신한다.
     */
    public synchronized void registerOrUpdateFromQrPairing(
            @NonNull RegisteredPleosDevice device,
            @NonNull String syncedVehicleId,
            @NonNull String syncedVehicleAlias
    ) {
        if (!syncedVehicleId.trim().isEmpty()) {
            this.currentVehicleId = syncedVehicleId.trim();
        }
        if (!syncedVehicleAlias.trim().isEmpty()) {
            this.vehicleAlias = syncedVehicleAlias.trim();
        }
        boolean exists = false;
        for (int i = 0; i < registeredDevices.size(); i++) {
            RegisteredPleosDevice existing = registeredDevices.get(i);
            if (existing.getMacAddress().equalsIgnoreCase(device.getMacAddress())
                    || existing.getDeviceId().equals(device.getDeviceId())) {
                registeredDevices.set(i, device);
                exists = true;
                break;
            }
        }
        if (!exists) {
            registeredDevices.add(0, device);
        }
        if (currentStage == M04ConnectionStage.UNREGISTERED) {
            currentStage = M04ConnectionStage.CONNECTING;
        }
        statusDetailMessage = "차량 QR 코드 인식 완료 · 차량(" + this.vehicleAlias + ") 연동 진행 중...";
    }

    public synchronized void removeAllRegisteredDevices() {
        registeredDevices.clear();
        fuelogAppAuthenticated = false;
        requestSentWaitingAck = false;
        currentStage = M04ConnectionStage.UNREGISTERED;
        statusDetailMessage = "등록된 차량 기기가 모두 해제되었습니다 (미등록 상태).";
    }
}
