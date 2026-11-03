package com.pleos.mobile_app_fuelog.core.navigation;

import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pleos.mobile_app_fuelog.core.permission.OptionalPermissionType;
import com.pleos.mobile_app_fuelog.core.security.SafeLogger;

/**
 * 진입→다음 요구사항:
 * "비로그인 또는 세션 만료 → M02(차량 없음) / M03(차량 있음).
 *  푸시 딥링크는 로그인 후 권한을 재검사해 목적 화면으로 이동."
 */
public final class DeepLinkRouter {

    public static final String EXTRA_DEEPLINK_DESTINATION = "extra_deeplink_destination";
    public static final String EXTRA_DEEPLINK_PERMISSION = "extra_deeplink_permission";
    public static final String EXTRA_DEEPLINK_FEATURE_LABEL = "extra_deeplink_feature_label";

    public enum Destination {
        M01_LOGIN,
        M02_NO_VEHICLE,
        M03_HAS_VEHICLE
    }

    /**
     * 비로그인 또는 세션 만료 상태에서 유입된 푸시 딥링크 대기 정보.
     */
    public static final class PendingDeepLink {
        private final Destination targetDestination;
        @Nullable
        private final OptionalPermissionType requiredPermission;
        @NonNull
        private final String featureLabel;

        public PendingDeepLink(
                @NonNull Destination targetDestination,
                @Nullable OptionalPermissionType requiredPermission,
                @NonNull String featureLabel
        ) {
            this.targetDestination = targetDestination;
            this.requiredPermission = requiredPermission;
            this.featureLabel = featureLabel;
        }

        @NonNull
        public Destination getTargetDestination() {
            return targetDestination;
        }

        @Nullable
        public OptionalPermissionType getRequiredPermission() {
            return requiredPermission;
        }

        @NonNull
        public String getFeatureLabel() {
            return featureLabel;
        }
    }

    /**
     * 로그인 완료 후 최종 결정된 이동 경로 및 권한 재검사 결과.
     */
    public static final class RouteDecision {
        private final Destination destination;
        private final boolean fromDeepLink;
        @Nullable
        private final OptionalPermissionType permissionToPromptOnArrival;
        @NonNull
        private final String arrivalNotice;

        public RouteDecision(
                @NonNull Destination destination,
                boolean fromDeepLink,
                @Nullable OptionalPermissionType permissionToPromptOnArrival,
                @NonNull String arrivalNotice
        ) {
            this.destination = destination;
            this.fromDeepLink = fromDeepLink;
            this.permissionToPromptOnArrival = permissionToPromptOnArrival;
            this.arrivalNotice = arrivalNotice;
        }

        @NonNull
        public Destination getDestination() {
            return destination;
        }

        public boolean isFromDeepLink() {
            return fromDeepLink;
        }

        @Nullable
        public OptionalPermissionType getPermissionToPromptOnArrival() {
            return permissionToPromptOnArrival;
        }

        @NonNull
        public String getArrivalNotice() {
            return arrivalNotice;
        }
    }

    public interface PermissionGrantChecker {
        boolean isGranted(@NonNull OptionalPermissionType permissionType);
    }

    @Nullable
    private PendingDeepLink pendingDeepLink;

    @Nullable
    public synchronized PendingDeepLink getPendingDeepLink() {
        return pendingDeepLink;
    }

    public synchronized void setPendingDeepLink(@Nullable PendingDeepLink pendingDeepLink) {
        this.pendingDeepLink = pendingDeepLink;
        if (pendingDeepLink != null) {
            SafeLogger.i("푸시 딥링크 보류 등록: target=" + pendingDeepLink.getTargetDestination()
                    + ", permission=" + pendingDeepLink.getRequiredPermission());
        }
    }

    public synchronized void clearPendingDeepLink() {
        this.pendingDeepLink = null;
    }

    /**
     * Intent(푸시 알림 클릭 또는 fuelog://app/... 딥링크)에서 목적지와 필요 권한을 추출한다.
     */
    @Nullable
    public static PendingDeepLink parseFromIntent(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }

        String extraDest = intent.getStringExtra(EXTRA_DEEPLINK_DESTINATION);
        String extraPerm = intent.getStringExtra(EXTRA_DEEPLINK_PERMISSION);
        String extraLabel = intent.getStringExtra(EXTRA_DEEPLINK_FEATURE_LABEL);

        if (extraDest != null && !extraDest.isEmpty()) {
            Destination dest = "M02".equalsIgnoreCase(extraDest)
                    ? Destination.M02_NO_VEHICLE
                    : Destination.M03_HAS_VEHICLE;
            OptionalPermissionType perm = OptionalPermissionType.fromKey(extraPerm);
            String label = extraLabel != null ? extraLabel : "푸시 알림 바로가기";
            return new PendingDeepLink(dest, perm, label);
        }

        Uri data = intent.getData();
        if (data != null && "fuelog".equalsIgnoreCase(data.getScheme())) {
            String path = data.getPath() != null ? data.getPath() : "";
            Destination dest = path.toLowerCase().contains("m02")
                    ? Destination.M02_NO_VEHICLE
                    : Destination.M03_HAS_VEHICLE;
            OptionalPermissionType perm = OptionalPermissionType.fromKey(
                    data.getQueryParameter("permission")
            );
            String label = data.getQueryParameter("feature");
            if (label == null || label.isEmpty()) {
                label = "푸시 딥링크(" + data.toString() + ")";
            }
            return new PendingDeepLink(dest, perm, label);
        }

        return null;
    }

    /**
     * 로그인 성공 직후 호출되어 다음 이동 화면(M02 vs M03 또는 보류된 푸시 딥링크)을 결정한다.
     * 보류된 푸시 딥링크가 있는 경우, 현재 계정의 차량 보유 상태 및 OS 런타임 권한을 재검사한다.
     */
    @NonNull
    public synchronized RouteDecision resolvePostLoginRoute(
            boolean hasVehicle,
            @NonNull PermissionGrantChecker permissionChecker
    ) {
        PendingDeepLink deepLink = this.pendingDeepLink;
        this.pendingDeepLink = null; // 1회 소비

        if (deepLink == null) {
            if (hasVehicle) {
                return new RouteDecision(
                        Destination.M03_HAS_VEHICLE,
                        false,
                        null,
                        "등록된 차량이 확인되어 M03(차량 홈) 화면으로 이동했습니다."
                );
            } else {
                return new RouteDecision(
                        Destination.M02_NO_VEHICLE,
                        false,
                        null,
                        "등록된 차량이 없어 M02(첫 차량 등록) 화면으로 이동했습니다."
                );
            }
        }

        // 푸시 딥링크가 존재할 때: 계정 권한(차량 보유 여부) + OS 선택 권한을 재검사
        Destination target = deepLink.getTargetDestination();
        if (target == Destination.M03_HAS_VEHICLE && !hasVehicle) {
            SafeLogger.w("딥링크 권한 재검사: M03 딥링크이나 현재 계정에 등록 차량이 없어 M02로 안전 전환");
            return new RouteDecision(
                    Destination.M02_NO_VEHICLE,
                    true,
                    null,
                    "푸시 딥링크(" + deepLink.getFeatureLabel() + ")를 재검사한 결과, "
                            + "현재 계정에 등록된 차량이 없어 M02(차량 등록) 화면으로 먼저 안내합니다."
            );
        }

        OptionalPermissionType requiredPerm = deepLink.getRequiredPermission();
        if (requiredPerm != null) {
            boolean granted = permissionChecker.isGranted(requiredPerm);
            if (!granted) {
                SafeLogger.i("딥링크 권한 재검사: " + requiredPerm.getKey() + " 미허용 -> 목적 안내 후 요청 예정");
                return new RouteDecision(
                        target,
                        true,
                        requiredPerm,
                        "푸시 딥링크(" + deepLink.getFeatureLabel() + ") 이동 완료: "
                                + requiredPerm.getTitle() + " 상태를 재검사하여 기능 시작 전 목적 안내를 표시합니다."
                );
            } else {
                return new RouteDecision(
                        target,
                        true,
                        null,
                        "푸시 딥링크(" + deepLink.getFeatureLabel() + ") 이동 완료: "
                                + requiredPerm.getTitle() + "이(가) 이미 허용되어 기능을 바로 시작합니다."
                );
            }
        }

        return new RouteDecision(
                target,
                true,
                null,
                "푸시 딥링크(" + deepLink.getFeatureLabel() + ") 권한 재검사를 통과하여 목적 화면으로 이동했습니다."
        );
    }
}
