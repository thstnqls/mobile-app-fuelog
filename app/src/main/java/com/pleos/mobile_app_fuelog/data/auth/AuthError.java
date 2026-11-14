package com.pleos.mobile_app_fuelog.data.auth;

import androidx.annotation.NonNull;

/**
 * FR-M01-3: 서버 오류와 입력 오류를 구분하고, UI가 적절한 필드 또는 배너에 표시할 수 있도록 구조화한다.
 */
public final class AuthError {

    public enum Field {
        EMAIL,
        PASSWORD,
        PASSWORD_CONFIRM,
        TERMS,
        GENERAL
    }

    private final AuthErrorType type;
    private final Field field;
    private final String userMessage;
    private final int httpStatusCode;

    public AuthError(
            @NonNull AuthErrorType type,
            @NonNull Field field,
            @NonNull String userMessage,
            int httpStatusCode
    ) {
        this.type = type;
        this.field = field;
        this.userMessage = userMessage;
        this.httpStatusCode = httpStatusCode;
    }

    @NonNull
    public static AuthError inputError(@NonNull Field field, @NonNull String message) {
        return new AuthError(AuthErrorType.INPUT_ERROR, field, message, 400);
    }

    @NonNull
    public static AuthError serverError(@NonNull String message, int statusCode) {
        return new AuthError(AuthErrorType.SERVER_ERROR, Field.GENERAL, message, statusCode);
    }

    @NonNull
    public AuthErrorType getType() {
        return type;
    }

    @NonNull
    public Field getField() {
        return field;
    }

    @NonNull
    public String getUserMessage() {
        return userMessage;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public boolean isServerError() {
        return type == AuthErrorType.SERVER_ERROR;
    }

    public boolean isInputError() {
        return type == AuthErrorType.INPUT_ERROR;
    }
}
