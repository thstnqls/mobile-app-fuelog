package com.pleos.mobile_app_fuelog.data.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * FR-M01-3: 이메일, 비밀번호, 필수 약관 동의에 대한 클라이언트 입력 검증기.
 */
public final class AuthValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    public static final int MIN_PASSWORD_LENGTH = 8;

    private AuthValidator() {
    }

    @Nullable
    public static AuthError validateEmail(@Nullable String email) {
        if (email == null || email.trim().isEmpty()) {
            return AuthError.inputError(
                    AuthError.Field.EMAIL,
                    "이메일 주소를 입력해 주세요."
            );
        }
        if (!EMAIL_PATTERN.matcher(email.trim()).matches()) {
            return AuthError.inputError(
                    AuthError.Field.EMAIL,
                    "올바른 이메일 형식이 아닙니다 (예: user@fuelog.com)."
            );
        }
        return null;
    }

    @Nullable
    public static AuthError validateLoginInput(@Nullable String email, @Nullable String password) {
        AuthError emailError = validateEmail(email);
        if (emailError != null) {
            return emailError;
        }
        if (password == null || password.isEmpty()) {
            return AuthError.inputError(
                    AuthError.Field.PASSWORD,
                    "비밀번호를 입력해 주세요."
            );
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return AuthError.inputError(
                    AuthError.Field.PASSWORD,
                    "비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다."
            );
        }
        return null;
    }

    @Nullable
    public static AuthError validateSignUpInput(
            @Nullable String email,
            @Nullable String password,
            @Nullable String passwordConfirm,
            boolean serviceTermsChecked,
            boolean privacyPolicyChecked,
            boolean age14Checked
    ) {
        AuthError emailError = validateEmail(email);
        if (emailError != null) {
            return emailError;
        }
        if (password == null || password.isEmpty()) {
            return AuthError.inputError(
                    AuthError.Field.PASSWORD,
                    "비밀번호를 입력해 주세요."
            );
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return AuthError.inputError(
                    AuthError.Field.PASSWORD,
                    "비밀번호는 영문·숫자를 포함해 " + MIN_PASSWORD_LENGTH + "자 이상 입력해 주세요."
            );
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) hasLetter = true;
            if (Character.isDigit(c)) hasDigit = true;
        }
        if (!hasLetter || !hasDigit) {
            return AuthError.inputError(
                    AuthError.Field.PASSWORD,
                    "비밀번호에는 영문자와 숫자가 모두 포함되어야 합니다."
            );
        }
        if (passwordConfirm == null || !password.equals(passwordConfirm)) {
            return AuthError.inputError(
                    AuthError.Field.PASSWORD_CONFIRM,
                    "비밀번호 확인이 일치하지 않습니다."
            );
        }
        if (!serviceTermsChecked || !privacyPolicyChecked || !age14Checked) {
            return AuthError.inputError(
                    AuthError.Field.TERMS,
                    "필수 약관(서비스 이용약관, 개인정보 처리방침, 만 14세 이상)에 모두 동의해 주세요."
            );
        }
        return null;
    }
}
