package com.pleos.mobile_app_fuelog.data.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 인증/가입/복구 처리 결과 래퍼.
 */
public final class AuthResult {

    private final boolean success;
    @Nullable
    private final AuthSession session;
    private final boolean hasVehicle;
    private final boolean previousAccountCacheHidden;
    @Nullable
    private final String noticeMessage;
    @Nullable
    private final AuthError error;

    private AuthResult(
            boolean success,
            @Nullable AuthSession session,
            boolean hasVehicle,
            boolean previousAccountCacheHidden,
            @Nullable String noticeMessage,
            @Nullable AuthError error
    ) {
        this.success = success;
        this.session = session;
        this.hasVehicle = hasVehicle;
        this.previousAccountCacheHidden = previousAccountCacheHidden;
        this.noticeMessage = noticeMessage;
        this.error = error;
    }

    @NonNull
    public static AuthResult loginSuccess(
            @NonNull AuthSession session,
            boolean hasVehicle,
            boolean previousAccountCacheHidden
    ) {
        return new AuthResult(true, session, hasVehicle, previousAccountCacheHidden, null, null);
    }

    @NonNull
    public static AuthResult recoverySuccess(@NonNull String neutralMessage) {
        return new AuthResult(true, null, false, false, neutralMessage, null);
    }

    @NonNull
    public static AuthResult failure(@NonNull AuthError error) {
        return new AuthResult(false, null, false, false, null, error);
    }

    public boolean isSuccess() {
        return success;
    }

    @Nullable
    public AuthSession getSession() {
        return session;
    }

    public boolean hasVehicle() {
        return hasVehicle;
    }

    public boolean isPreviousAccountCacheHidden() {
        return previousAccountCacheHidden;
    }

    @Nullable
    public String getNoticeMessage() {
        return noticeMessage;
    }

    @Nullable
    public AuthError getError() {
        return error;
    }
}
