package com.pleos.mobile_app_fuelog.data.connection;

import androidx.annotation.NonNull;

/**
 * M04 연결 상태 단계:
 * 미등록 → 권한 필요 → 연결 중 → 앱 인증 중 → 차량 확인 대기 → 사용 중 / 만료 / 실패.
 */
public enum M04ConnectionStage {
    UNREGISTERED(1, "미등록", "QR로 연동된 차량 단말이 없습니다"),
    PERMISSION_REQUIRED(2, "권한 필요", "카메라(QR 인식) 권한 허용이 필요합니다"),
    CONNECTING(3, "연결 중", "차량(C01/C03) QR 인식 채널을 연결하고 있습니다"),
    APP_AUTHENTICATING(4, "앱 인증 중", "서버 단기 증명(차량·운행·챌린지 결합)을 생성하고 있습니다"),
    WAITING_VEHICLE_CONFIRM(5, "차량 확인 대기", "요청 보냄 · 차량(C03) 확인 응답을 기다리는 중입니다"),
    IN_USE(6, "사용 중", "이번 운행에 내 계정이 사용자로 등록되었습니다"),
    EXPIRED(7, "만료", "인증 증명 유효시간이 만료되었거나 운행이 종료되었습니다"),
    FAILED(8, "실패", "연결 또는 사용자 인증에 실패했습니다");

    private final int stepOrder;
    private final String stageLabel;
    private final String defaultDescription;

    M04ConnectionStage(int stepOrder, String stageLabel, String defaultDescription) {
        this.stepOrder = stepOrder;
        this.stageLabel = stageLabel;
        this.defaultDescription = defaultDescription;
    }

    public int getStepOrder() {
        return stepOrder;
    }

    @NonNull
    public String getStageLabel() {
        return stageLabel;
    }

    @NonNull
    public String getDefaultDescription() {
        return defaultDescription;
    }
}
