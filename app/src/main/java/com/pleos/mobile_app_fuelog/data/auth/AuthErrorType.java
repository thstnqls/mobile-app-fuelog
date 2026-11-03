package com.pleos.mobile_app_fuelog.data.auth;

/**
 * FR-M01-3: 서버 오류와 입력 오류를 명확히 구분한다.
 */
public enum AuthErrorType {
    /**
     * 사용자 입력 형식 오류, 필수 약관 미동의, 이메일/비밀번호 불일치 등 입력/자격증명 관련 오류
     */
    INPUT_ERROR,

    /**
     * 네트워크 연결 끊김, 타임아웃, Supabase 5xx 서버 장애, 일시적 요청 제한 등 서버/통신 오류
     */
    SERVER_ERROR
}
