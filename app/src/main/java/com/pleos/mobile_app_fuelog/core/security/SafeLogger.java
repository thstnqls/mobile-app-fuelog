package com.pleos.mobile_app_fuelog.core.security;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * FR-M01-3: 비밀번호·토큰을 로그에 남기지 않는다.
 * 모든 인증/세션/네트워크 관련 로그는 SafeLogger를 통해 기록하며,
 * 민감 키워드(password, token, access_token, refresh_token, apikey, bearer, JWT)를 자동 마스킹한다.
 */
public final class SafeLogger {

    private static final String DEFAULT_TAG = "FuelogAuth";
    private static final int MAX_RECENT_LOGS = 100;

    // JWT 패턴 (header.payload.signature)
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*"
    );

    // JSON 내 민감 필드 패턴 ("password": "...", "access_token": "...", etc.)
    private static final Pattern JSON_SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(\"(?:password|new_password|current_password|access_token|refresh_token|id_token|token|apikey|api_key|secret|authorization)\"\\s*:\\s*\")([^\"]*)(\")"
    );

    // Key-Value 또는 쿼리스트링 내 민감 필드 패턴 (password=..., access_token=..., Bearer ...)
    private static final Pattern KV_SENSITIVE_PATTERN = Pattern.compile(
            "(?i)((?:password|access_token|refresh_token|token|apikey|api_key|secret)\\s*[=:]\\s*)([^\\s,&;\"]+)"
    );

    // Authorization Bearer 헤더 패턴
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)(Bearer\\s+)([A-Za-z0-9._~+/=-]+)"
    );

    private static final List<String> recentSanitizedLogs =
            Collections.synchronizedList(new ArrayList<>());

    private SafeLogger() {
    }

    /**
     * 문자열 내 비밀번호, 토큰, JWT, API 키를 마스킹 처리한다.
     */
    @NonNull
    public static String sanitize(@Nullable String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return "";
        }
        String sanitized = rawMessage;
        sanitized = JWT_PATTERN.matcher(sanitized).replaceAll("[REDACTED_JWT]");
        sanitized = JSON_SENSITIVE_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]$3");
        sanitized = KV_SENSITIVE_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED_TOKEN]");
        return sanitized;
    }

    /**
     * 이메일 주소를 로그에 남길 때 최소한의 식별만 가능하도록 마스킹한다 (예: ab***@domain.com).
     */
    @NonNull
    public static String maskEmail(@Nullable String email) {
        if (email == null || email.trim().isEmpty()) {
            return "[EMPTY_EMAIL]";
        }
        String trimmed = email.trim();
        int atIndex = trimmed.indexOf('@');
        if (atIndex <= 0) {
            return "***";
        }
        String localPart = trimmed.substring(0, atIndex);
        String domainPart = trimmed.substring(atIndex);
        if (localPart.length() <= 2) {
            return localPart.charAt(0) + "***" + domainPart;
        }
        return localPart.substring(0, 2) + "***" + domainPart;
    }

    public static void i(@NonNull String message) {
        i(DEFAULT_TAG, message);
    }

    public static void i(@NonNull String tag, @NonNull String message) {
        String safe = sanitize(message);
        recordLog("INFO", tag, safe);
        try {
            Log.i(tag, safe);
        } catch (RuntimeException ignored) {
            // JVM 단위 테스트 환경 호환
        }
    }

    public static void w(@NonNull String message) {
        w(DEFAULT_TAG, message);
    }

    public static void w(@NonNull String tag, @NonNull String message) {
        String safe = sanitize(message);
        recordLog("WARN", tag, safe);
        try {
            Log.w(tag, safe);
        } catch (RuntimeException ignored) {
            // JVM 단위 테스트 환경 호환
        }
    }

    public static void e(@NonNull String message, @Nullable Throwable throwable) {
        e(DEFAULT_TAG, message, throwable);
    }

    public static void e(@NonNull String tag, @NonNull String message, @Nullable Throwable throwable) {
        String throwableMsg = throwable != null && throwable.getMessage() != null
                ? " (" + sanitize(throwable.getMessage()) + ")"
                : "";
        String safe = sanitize(message) + throwableMsg;
        recordLog("ERROR", tag, safe);
        try {
            Log.e(tag, safe);
        } catch (RuntimeException ignored) {
            // JVM 단위 테스트 환경 호환
        }
    }

    private static void recordLog(String level, String tag, String sanitizedMessage) {
        synchronized (recentSanitizedLogs) {
            if (recentSanitizedLogs.size() >= MAX_RECENT_LOGS) {
                recentSanitizedLogs.remove(0);
            }
            recentSanitizedLogs.add("[" + level + "][" + tag + "] " + sanitizedMessage);
        }
    }

    @NonNull
    public static List<String> getRecentSanitizedLogs() {
        synchronized (recentSanitizedLogs) {
            return new ArrayList<>(recentSanitizedLogs);
        }
    }

    public static void clearRecentLogs() {
        synchronized (recentSanitizedLogs) {
            recentSanitizedLogs.clear();
        }
    }
}
