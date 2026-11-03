package com.pleos.mobile_app_fuelog.data.auth;

import androidx.annotation.NonNull;

/**
 * FR-M01-1: 초기 버전은 이메일 방식 한 가지로 제한하고 소셜 로그인은 후속 확장한다.
 */
public enum AuthProviderType {
    EMAIL("email", "이메일", true),
    GOOGLE("google", "Google", false),
    KAKAO("kakao", "Kakao", false),
    APPLE("apple", "Apple", false);

    private final String providerKey;
    private final String displayName;
    private final boolean supportedInInitialRelease;

    AuthProviderType(String providerKey, String displayName, boolean supportedInInitialRelease) {
        this.providerKey = providerKey;
        this.displayName = displayName;
        this.supportedInInitialRelease = supportedInInitialRelease;
    }

    @NonNull
    public String getProviderKey() {
        return providerKey;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    public boolean isSupportedInInitialRelease() {
        return supportedInInitialRelease;
    }
}
