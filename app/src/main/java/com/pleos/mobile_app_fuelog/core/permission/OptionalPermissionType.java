package com.pleos.mobile_app_fuelog.core.permission;

import android.Manifest;

import androidx.annotation.NonNull;

/**
 * FR-M01-2: 위치·근처 기기·알림 권한은 해당 기능 시작 시 목적을 설명하고 요청.
 * 선택 권한 거부로 로그인 자체를 막지 않는다.
 */
public enum OptionalPermissionType {

    LOCATION(
            "location",
            "위치 권한 (선택)",
            "주유소 자동 매칭 및 주유 위치 기록",
            "현재 위치한 주유소를 자동으로 찾고 주유 단가·위치를 기록하기 위해 위치 권한이 필요합니다.",
            "위치 권한을 허용하지 않아도 로그인 및 수동 주유 기록 입력은 정상적으로 이용할 수 있습니다.",
            new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }
    ),

    NEARBY_DEVICES(
            "nearby_devices",
            "근처 기기 권한 (선택)",
            "차량 블루투스·OBD 자동 연결 및 주행 인식",
            "차량 블루투스 또는 OBD 기기와 연결해 시동·주행 거리를 자동으로 인식하기 위해 근처 기기 권한이 필요합니다.",
            "근처 기기 권한을 거부해도 로그인 및 차계부 핵심 기능은 제한 없이 이용할 수 있습니다.",
            new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }
    ),

    NOTIFICATIONS(
            "notifications",
            "알림 권한 (선택)",
            "주유 리마인더·정비 주기 및 계정 안내 알림",
            "주유 기록 누락 방지 리마인더, 소모품 교체 주기, 중요 계정 안내를 푸시 알림으로 받기 위해 알림 권한이 필요합니다.",
            "알림 권한을 거부해도 로그인 및 앱 내 모든 조회·기록 기능은 그대로 사용할 수 있습니다.",
            new String[]{
                    Manifest.permission.POST_NOTIFICATIONS
            }
    ),

    CAMERA_QR(
            "camera_qr",
            "카메라 권한 (선택)",
            "차량 화면(C01/C03) QR 코드 인식 및 차량 연동",
            "차량 디스플레이에 표시된 페어링·운행 사용자 확인 QR 코드를 카메라로 스캔하기 위해 카메라 권한이 필요합니다.",
            "카메라 권한을 허용하지 않아도 QR 페이로드 직접 인식 기능으로 차량을 연동할 수 있습니다.",
            new String[]{
                    Manifest.permission.CAMERA
            }
    );

    private final String key;
    private final String title;
    private final String featureName;
    private final String purposeDescription;
    private final String denialFallbackGuide;
    private final String[] androidPermissions;

    OptionalPermissionType(
            String key,
            String title,
            String featureName,
            String purposeDescription,
            String denialFallbackGuide,
            String[] androidPermissions
    ) {
        this.key = key;
        this.title = title;
        this.featureName = featureName;
        this.purposeDescription = purposeDescription;
        this.denialFallbackGuide = denialFallbackGuide;
        this.androidPermissions = androidPermissions;
    }

    @NonNull
    public String getKey() {
        return key;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    @NonNull
    public String getFeatureName() {
        return featureName;
    }

    @NonNull
    public String getPurposeDescription() {
        return purposeDescription;
    }

    @NonNull
    public String getDenialFallbackGuide() {
        return denialFallbackGuide;
    }

    @NonNull
    public String[] getAndroidPermissions() {
        return androidPermissions.clone();
    }

    public static OptionalPermissionType fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (OptionalPermissionType type : values()) {
            if (type.key.equalsIgnoreCase(key.trim())) {
                return type;
            }
        }
        return null;
    }
}
