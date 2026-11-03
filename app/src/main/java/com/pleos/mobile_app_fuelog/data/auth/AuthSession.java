package com.pleos.mobile_app_fuelog.data.auth;

import androidx.annotation.NonNull;

import com.pleos.mobile_app_fuelog.core.security.SafeLogger;

/**
 * FR-M01-1: Supabase Auth 세션 모델.
 * FR-M01-3: toString() 등에서 토큰이 절대 평문으로 노출되지 않도록 마스킹 처리한다.
 */
public final class AuthSession {

    private final String userId;
    private final String email;
    private final String accessToken;
    private final String refreshToken;
    private final long expiresAtEpochSeconds;
    private final boolean termsAccepted;

    public AuthSession(
            @NonNull String userId,
            @NonNull String email,
            @NonNull String accessToken,
            @NonNull String refreshToken,
            long expiresAtEpochSeconds,
            boolean termsAccepted
    ) {
        this.userId = userId;
        this.email = email;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
        this.termsAccepted = termsAccepted;
    }

    @NonNull
    public String getUserId() {
        return userId;
    }

    @NonNull
    public String getEmail() {
        return email;
    }

    @NonNull
    public String getAccessToken() {
        return accessToken;
    }

    @NonNull
    public String getRefreshToken() {
        return refreshToken;
    }

    public long getExpiresAtEpochSeconds() {
        return expiresAtEpochSeconds;
    }

    public boolean isTermsAccepted() {
        return termsAccepted;
    }

    public boolean isExpired(long currentEpochSeconds) {
        return currentEpochSeconds >= expiresAtEpochSeconds;
    }

    @NonNull
    @Override
    public String toString() {
        return "AuthSession{" +
                "userId='" + userId + '\'' +
                ", email='" + SafeLogger.maskEmail(email) + '\'' +
                ", accessToken='[REDACTED]'" +
                ", refreshToken='[REDACTED]'" +
                ", expiresAtEpochSeconds=" + expiresAtEpochSeconds +
                ", termsAccepted=" + termsAccepted +
                '}';
    }
}
