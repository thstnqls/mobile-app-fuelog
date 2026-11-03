package com.pleos.mobile_app_fuelog.core.permission;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * FR-M01-2:
 * - 위치·근처 기기·알림 권한은 로그인 시점에 강제하지 않고, 해당 기능 시작 시 목적을 먼저 설명하고 요청한다.
 * - 선택 권한을 거부하더라도 로그인이나 기본 앱 진입을 절대 차단하지 않는다.
 */
public final class PermissionGuideHelper {

    public interface PermissionActionCallback {
        void onProceedToSystemRequest(@NonNull OptionalPermissionType permissionType);
        void onContinueWithoutPermission(@NonNull OptionalPermissionType permissionType);
    }

    private PermissionGuideHelper() {
    }

    /**
     * 해당 선택 권한이 현재 OS에서 허용되어 있는지 검사한다.
     */
    public static boolean isPermissionGranted(
            @NonNull Context context,
            @NonNull OptionalPermissionType permissionType
    ) {
        for (String perm : permissionType.getAndroidPermissions()) {
            if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    /**
     * 특정 기능이 실행될 때 호출한다.
     * - 이미 권한이 허용되어 있으면 아무 팝업 없이 즉시 기능을 실행(onAlreadyGranted)한다.
     * - 권한이 없을 때만 목적 설명 다이얼로그를 띄우고 OS 권한 요청을 진행한다.
     */
    public static void executeOrRequestWhenMissing(
            @NonNull Context context,
            @NonNull OptionalPermissionType permissionType,
            @NonNull Runnable onAlreadyGranted,
            @NonNull PermissionActionCallback callback
    ) {
        if (isPermissionGranted(context, permissionType)) {
            onAlreadyGranted.run();
            return;
        }
        showFeaturePermissionPurposeDialog(context, permissionType, callback);
    }

    /**
     * 특정 선택 기능을 시작할 때 권한이 없으면 목적을 먼저 설명하는 다이얼로그를 표시한다.
     */
    @NonNull
    public static AlertDialog showFeaturePermissionPurposeDialog(
            @NonNull Context context,
            @NonNull OptionalPermissionType permissionType,
            @NonNull PermissionActionCallback callback
    ) {
        String message = "기능: " + permissionType.getFeatureName() + "\n\n"
                + "• 수집/사용 목적:\n" + permissionType.getPurposeDescription() + "\n\n"
                + "• 선택 권한 안내:\n" + permissionType.getDenialFallbackGuide();

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(permissionType.getTitle() + " 안내")
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton("권한 요청 진행", (d, which) ->
                        callback.onProceedToSystemRequest(permissionType))
                .setNegativeButton("나중에 하기 (기능만 둘러보기)", (d, which) ->
                        callback.onContinueWithoutPermission(permissionType))
                .create();
        dialog.show();
        return dialog;
    }

    /**
     * M01 화면에서 선택 기능(위치·근처 기기·알림) 전체 설명을 열람할 수 있는 안내 다이얼로그를 표시한다.
     */
    @NonNull
    public static AlertDialog showOptionalFeaturesOverviewDialog(@NonNull Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("fuelog는 로그인·회원가입 시 어떤 선택 권한도 강제하지 않습니다.\n");
        sb.append("각 권한은 해당 기능을 실제로 시작할 때 목적을 안내한 뒤 요청하며, 거부하더라도 로그인과 차계부 이용은 차단되지 않습니다.\n\n");

        for (OptionalPermissionType type : OptionalPermissionType.values()) {
            sb.append("■ ").append(type.getTitle()).append(" — ").append(type.getFeatureName()).append("\n");
            sb.append("  ").append(type.getPurposeDescription()).append("\n\n");
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle("선택 기능 및 권한 안내 (FR-M01-2)")
                .setMessage(sb.toString().trim())
                .setPositiveButton("확인", null)
                .create();
        dialog.show();
        return dialog;
    }
}
