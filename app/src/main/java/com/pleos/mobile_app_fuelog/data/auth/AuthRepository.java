package com.pleos.mobile_app_fuelog.data.auth;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pleos.mobile_app_fuelog.BuildConfig;
import com.pleos.mobile_app_fuelog.core.security.SafeLogger;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FR-M01-1: Supabase Auth 세션 기반 이메일 로그인/가입/복구 저장소.
 * BuildConfig(SUPABASE_URL, SUPABASE_ANON_KEY) 설정 시 실제 Supabase REST 엔드포인트와 통신하며,
 * 미설정 시 동일한 계약의 로컬/Mock 폴백으로 동작한다.
 *
 * FR-M01-3:
 * - 서버 오류(5xx, 네트워크 예외, 타임아웃)와 입력 오류(4xx, 형식/자격증명 불일치)를 구분한다.
 * - 비밀번호와 토큰은 절대 로그에 남기지 않는다.
 *
 * 예외/완료:
 * - 계정 복구 요청은 계정 존재 여부를 노출하지 않는 일관된 안내 메시지로 응답한다.
 * - 세션 만료 후 다른 계정으로 로그인 시 이전 계정 캐시를 숨김/초기화한다.
 */
public final class AuthRepository {

    public static final String RECOVERY_NEUTRAL_GUIDANCE =
            "입력하신 이메일이 등록되어 있다면 비밀번호 재설정 안내 메일이 발송됩니다. " +
            "보안을 위해 계정 가입 여부는 별도로 표시하지 않으며, 메일함(스팸함 포함)을 확인해 주세요.";

    private static final String PREF_AUTH = "fuelog_supabase_auth_prefs";
    private static final String KEY_USER_ID = "session_user_id";
    private static final String KEY_EMAIL = "session_email";
    private static final String KEY_ACCESS_TOKEN = "session_access_token";
    private static final String KEY_REFRESH_TOKEN = "session_refresh_token";
    private static final String KEY_EXPIRES_AT = "session_expires_at";
    private static final String KEY_TERMS_ACCEPTED = "session_terms_accepted";

    private static final String ACCOUNT_PW_HASH_PREFIX = "local_acc_pwhash_";
    private static final String ACCOUNT_UID_PREFIX = "local_acc_uid_";

    private static volatile AuthRepository instance;

    private final SharedPreferences prefs;
    private final Map<String, String> memoryPrefs = new HashMap<>();
    private final UserCacheManager userCacheManager;

    private volatile boolean simulateServerError = false;

    public AuthRepository(@Nullable Context context, @NonNull UserCacheManager userCacheManager) {
        if (context != null) {
            this.prefs = context.getApplicationContext()
                    .getSharedPreferences(PREF_AUTH, Context.MODE_PRIVATE);
        } else {
            this.prefs = null;
        }
        this.userCacheManager = userCacheManager;
        seedDefaultLocalAccounts();
    }

    public static AuthRepository getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (AuthRepository.class) {
                if (instance == null) {
                    instance = new AuthRepository(context, UserCacheManager.getInstance(context));
                }
            }
        }
        return instance;
    }

    private void seedDefaultLocalAccounts() {
        // 데모 및 검증용 기본 계정 2개:
        // 1) car@fuelog.com / fuelog1234 -> 차량 보유 계정 (M03 이동, userId = user_with_car)
        // 2) new@fuelog.com / fuelog1234 -> 차량 미보유 계정 (M02 이동, userId = user_no_car)
        registerLocalSeedAccount("car@fuelog.com", "fuelog1234", "user_with_car");
        registerLocalSeedAccount("new@fuelog.com", "fuelog1234", "user_no_car");
    }

    private void registerLocalSeedAccount(String email, String password, String userId) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (getPref(ACCOUNT_UID_PREFIX + normalized, null) == null) {
            putPref(ACCOUNT_UID_PREFIX + normalized, userId);
            putPref(ACCOUNT_PW_HASH_PREFIX + normalized, sha256(password));
        }
    }

    public boolean isRemoteSupabaseConfigured() {
        return prefs != null
                && BuildConfig.SUPABASE_URL != null
                && !BuildConfig.SUPABASE_URL.trim().isEmpty()
                && BuildConfig.SUPABASE_ANON_KEY != null
                && !BuildConfig.SUPABASE_ANON_KEY.trim().isEmpty();
    }

    public void setSimulateServerError(boolean simulateServerError) {
        this.simulateServerError = simulateServerError;
    }

    public boolean isSimulateServerError() {
        return simulateServerError;
    }

    /**
     * FR-M01-1: 초기 버전은 이메일 로그인만 허용하며 소셜 로그인은 후속 확장한다.
     */
    @NonNull
    public AuthResult verifyProviderSupported(@NonNull AuthProviderType providerType) {
        if (!providerType.isSupportedInInitialRelease()) {
            return AuthResult.failure(
                    AuthError.inputError(
                            AuthError.Field.GENERAL,
                            providerType.getDisplayName() + " 로그인은 후속 버전에서 지원될 예정입니다. 이메일로 로그인해 주세요."
                    )
            );
        }
        return AuthResult.recoverySuccess("지원되는 로그인 방식입니다.");
    }

    /**
     * 현재 저장된 세션이 존재하고 만료되지 않았으면 반환한다.
     * 만료된 경우 즉시 UserCacheManager를 통해 이전 계정 캐시를 숨김 처리하고 null을 반환한다.
     */
    @Nullable
    public synchronized AuthSession getCurrentValidSession() {
        return getCurrentValidSession(System.currentTimeMillis() / 1000L);
    }

    @Nullable
    public synchronized AuthSession getCurrentValidSession(long nowEpochSeconds) {
        String userId = getPref(KEY_USER_ID, null);
        String email = getPref(KEY_EMAIL, null);
        String accessToken = getPref(KEY_ACCESS_TOKEN, null);
        String refreshToken = getPref(KEY_REFRESH_TOKEN, null);
        String expiresStr = getPref(KEY_EXPIRES_AT, null);
        boolean termsAccepted = Boolean.parseBoolean(getPref(KEY_TERMS_ACCEPTED, "false"));

        if (userId == null || email == null || accessToken == null || expiresStr == null) {
            return null;
        }

        long expiresAt;
        try {
            expiresAt = Long.parseLong(expiresStr);
        } catch (NumberFormatException e) {
            return null;
        }

        AuthSession session = new AuthSession(
                userId,
                email,
                accessToken,
                refreshToken != null ? refreshToken : "",
                expiresAt,
                termsAccepted
        );

        if (session.isExpired(nowEpochSeconds)) {
            SafeLogger.w("세션 만료 감지(user=" + SafeLogger.maskEmail(email) + "). 재로그인이 필요합니다.");
            clearStoredSessionTokensOnly();
            userCacheManager.onSessionExpiredOrLoggedOut();
            return null;
        }
        return session;
    }

    /**
     * 이메일 로그인 수행 (FR-M01-1, FR-M01-3).
     */
    @NonNull
    public AuthResult signInWithEmail(@Nullable String email, @Nullable String password) {
        AuthError validationError = AuthValidator.validateLoginInput(email, password);
        if (validationError != null) {
            SafeLogger.w("로그인 입력 검증 실패: field=" + validationError.getField());
            return AuthResult.failure(validationError);
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        SafeLogger.i("이메일 로그인 요청 시작: email=" + SafeLogger.maskEmail(normalizedEmail));

        if (simulateServerError) {
            SafeLogger.e("서버 오류 시뮬레이션 활성화됨 (HTTP 503)", null);
            return AuthResult.failure(
                    AuthError.serverError(
                            "일시적인 서버 통신 오류(503)가 발생했습니다. 네트워크 상태를 확인한 뒤 다시 시도해 주세요.",
                            503
                    )
            );
        }

        if (isRemoteSupabaseConfigured()) {
            return signInWithRemoteSupabase(normalizedEmail, password);
        }

        return signInWithLocalFallback(normalizedEmail, password);
    }

    /**
     * 이메일 회원가입 및 최초 필수 약관 동의 수행 (FR-M01-1, FR-M01-3).
     */
    @NonNull
    public AuthResult signUpWithEmail(
            @Nullable String email,
            @Nullable String password,
            @Nullable String passwordConfirm,
            boolean serviceTermsChecked,
            boolean privacyPolicyChecked,
            boolean age14Checked
    ) {
        AuthError validationError = AuthValidator.validateSignUpInput(
                email, password, passwordConfirm, serviceTermsChecked, privacyPolicyChecked, age14Checked
        );
        if (validationError != null) {
            SafeLogger.w("회원가입 입력 검증 실패: field=" + validationError.getField());
            return AuthResult.failure(validationError);
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        SafeLogger.i("이메일 회원가입 요청 시작: email=" + SafeLogger.maskEmail(normalizedEmail));

        if (simulateServerError) {
            SafeLogger.e("회원가입 중 서버 오류 시뮬레이션 활성화됨 (HTTP 503)", null);
            return AuthResult.failure(
                    AuthError.serverError(
                            "서버 응답이 지연되거나 연결할 수 없습니다(503). 잠시 후 다시 시도해 주세요.",
                            503
                    )
            );
        }

        if (isRemoteSupabaseConfigured()) {
            return signUpWithRemoteSupabase(normalizedEmail, password);
        }

        return signUpWithLocalFallback(normalizedEmail, password);
    }

    /**
     * 계정 복구(비밀번호 재설정 메일) 요청.
     * 예외/완료: "복구 요청은 계정 존재 여부를 불필요하게 노출하지 않는 안내로 처리."
     */
    @NonNull
    public AuthResult requestPasswordRecovery(@Nullable String email) {
        AuthError emailError = AuthValidator.validateEmail(email);
        if (emailError != null) {
            return AuthResult.failure(emailError);
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        SafeLogger.i("계정 복구 요청 수신: email=" + SafeLogger.maskEmail(normalizedEmail));

        if (simulateServerError) {
            SafeLogger.e("계정 복구 중 서버 오류 발생(503)", null);
            return AuthResult.failure(
                    AuthError.serverError(
                            "복구 요청 처리 중 서버 통신 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
                            503
                    )
            );
        }

        if (isRemoteSupabaseConfigured()) {
            return requestRemotePasswordRecovery(normalizedEmail);
        }

        // 로컬 폴백에서도 계정 존재 여부(가입/미가입)와 무관하게 동일한 안내 문구를 반환한다.
        return AuthResult.recoverySuccess(RECOVERY_NEUTRAL_GUIDANCE);
    }

    /**
     * 현재 세션을 만료 상태로 전환 (세션 만료 후 재진입 테스트 및 검증용).
     */
    public synchronized void expireCurrentSession() {
        putPref(KEY_EXPIRES_AT, "0");
        userCacheManager.onSessionExpiredOrLoggedOut();
    }

    /**
     * 로그아웃 처리.
     */
    public synchronized void signOut() {
        clearStoredSessionTokensOnly();
        userCacheManager.onSessionExpiredOrLoggedOut();
        SafeLogger.i("로그아웃 완료 및 캐시 숨김 적용");
    }

    private AuthResult signInWithLocalFallback(String normalizedEmail, String password) {
        String storedUserId = getPref(ACCOUNT_UID_PREFIX + normalizedEmail, null);
        String storedHash = getPref(ACCOUNT_PW_HASH_PREFIX + normalizedEmail, null);

        if (storedUserId == null || storedHash == null || !storedHash.equals(sha256(password))) {
            SafeLogger.w("로그인 실패: 자격증명 불일치 email=" + SafeLogger.maskEmail(normalizedEmail));
            return AuthResult.failure(
                    AuthError.inputError(
                            AuthError.Field.GENERAL,
                            "이메일 또는 비밀번호가 올바르지 않습니다. 입력 내용을 다시 확인해 주세요."
                    )
            );
        }

        long expiresAt = (System.currentTimeMillis() / 1000L) + (30L * 24L * 3600L);
        AuthSession session = new AuthSession(
                storedUserId,
                normalizedEmail,
                "local_jwt_" + UUID.randomUUID(),
                "local_refresh_" + UUID.randomUUID(),
                expiresAt,
                true
        );

        return finalizeAuthenticatedSession(session);
    }

    private AuthResult signUpWithLocalFallback(String normalizedEmail, String password) {
        String existingUserId = getPref(ACCOUNT_UID_PREFIX + normalizedEmail, null);
        if (existingUserId != null) {
            return AuthResult.failure(
                    AuthError.inputError(
                            AuthError.Field.EMAIL,
                            "이미 가입된 이메일입니다. 로그인 탭을 이용하거나 계정 복구를 진행해 주세요."
                    )
            );
        }

        String newUserId = "user_" + Math.abs(normalizedEmail.hashCode());
        putPref(ACCOUNT_UID_PREFIX + normalizedEmail, newUserId);
        putPref(ACCOUNT_PW_HASH_PREFIX + normalizedEmail, sha256(password));

        long expiresAt = (System.currentTimeMillis() / 1000L) + (30L * 24L * 3600L);
        AuthSession session = new AuthSession(
                newUserId,
                normalizedEmail,
                "local_jwt_" + UUID.randomUUID(),
                "local_refresh_" + UUID.randomUUID(),
                expiresAt,
                true
        );

        return finalizeAuthenticatedSession(session);
    }

    private AuthResult signInWithRemoteSupabase(String normalizedEmail, String password) {
        HttpURLConnection conn = null;
        try {
            String baseUrl = stripTrailingSlash(BuildConfig.SUPABASE_URL);
            URL url = new URL(baseUrl + "/auth/v1/token?grant_type=password");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            JSONObject payload = new JSONObject();
            payload.put("email", normalizedEmail);
            payload.put("password", password);

            writeRequestBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String responseBody = readResponseBody(conn, status >= 400);

            if (status >= 200 && status < 300) {
                AuthSession session = parseSupabaseSession(responseBody, normalizedEmail);
                boolean hasRemoteVehicle = fetchRemoteHasVehicle(session.getAccessToken(), session.getUserId());
                if (hasRemoteVehicle && !userCacheManager.hasRegisteredVehicle(session.getUserId())) {
                    userCacheManager.setRegisteredVehicleForUser(session.getUserId(), "등록 차량");
                }
                return finalizeAuthenticatedSession(session);
            } else if (status >= 500 || status == 429) {
                SafeLogger.e("Supabase Auth 서버 오류 status=" + status, null);
                return AuthResult.failure(
                        AuthError.serverError(
                                "인증 서버가 일시적으로 응답하지 않습니다(HTTP " + status + "). 잠시 후 다시 시도해 주세요.",
                                status
                        )
                );
            } else {
                SafeLogger.w("Supabase Auth 입력/자격증명 오류 status=" + status);
                // 데모 빠른 테스트 계정(car@fuelog.com, new@fuelog.com)이 원격 DB에 아직 없을 경우 로컬 시드 폴백 허용
                if (("car@fuelog.com".equals(normalizedEmail) || "new@fuelog.com".equals(normalizedEmail))
                        && "fuelog1234".equals(password)) {
                    SafeLogger.i("데모 시드 계정 로컬 폴백 전환: " + SafeLogger.maskEmail(normalizedEmail));
                    return signInWithLocalFallback(normalizedEmail, password);
                }
                String detailMsg = extractSupabaseErrorMessage(
                        responseBody,
                        "이메일 또는 비밀번호가 일치하지 않습니다."
                );
                return AuthResult.failure(
                        AuthError.inputError(AuthError.Field.GENERAL, detailMsg)
                );
            }
        } catch (IOException e) {
            SafeLogger.e("Supabase 로그인 네트워크 오류", e);
            return AuthResult.failure(
                    AuthError.serverError(
                            "네트워크 연결이 불안정하여 서버에 접속할 수 없습니다. 인터넷 연결을 확인해 주세요.",
                            0
                    )
            );
        } catch (Exception e) {
            SafeLogger.e("Supabase 로그인 처리 중 예외", e);
            return AuthResult.failure(
                    AuthError.serverError(
                            "인증 응답을 처리하는 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
                            500
                    )
            );
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private AuthResult signUpWithRemoteSupabase(String normalizedEmail, String password) {
        HttpURLConnection conn = null;
        try {
            String baseUrl = stripTrailingSlash(BuildConfig.SUPABASE_URL);
            URL url = new URL(baseUrl + "/auth/v1/signup");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            JSONObject dataMeta = new JSONObject();
            dataMeta.put("terms_accepted", true);
            dataMeta.put("terms_accepted_at", System.currentTimeMillis() / 1000L);

            JSONObject payload = new JSONObject();
            payload.put("email", normalizedEmail);
            payload.put("password", password);
            payload.put("data", dataMeta);

            writeRequestBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String responseBody = readResponseBody(conn, status >= 400);

            if (status >= 200 && status < 300) {
                AuthSession session = parseSupabaseSession(responseBody, normalizedEmail);
                return finalizeAuthenticatedSession(session);
            } else if (status >= 500 || status == 429) {
                return AuthResult.failure(
                        AuthError.serverError(
                                "회원가입 서버 처리 중 일시적인 오류가 발생했습니다(HTTP " + status + ").",
                                status
                        )
                );
            } else {
                return AuthResult.failure(
                        AuthError.inputError(
                                AuthError.Field.EMAIL,
                                "회원가입 입력 정보를 다시 확인해 주세요(이미 사용 중인 이메일이거나 사용할 수 없는 비밀번호입니다)."
                        )
                );
            }
        } catch (IOException e) {
            SafeLogger.e("Supabase 회원가입 네트워크 오류", e);
            return AuthResult.failure(
                    AuthError.serverError(
                            "네트워크 연결 오류로 회원가입 요청을 완료하지 못했습니다.",
                            0
                    )
            );
        } catch (Exception e) {
            SafeLogger.e("Supabase 회원가입 응답 파싱 오류", e);
            return AuthResult.failure(
                    AuthError.serverError(
                            "서버 응답 처리 중 오류가 발생했습니다.",
                            500
                    )
            );
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private AuthResult requestRemotePasswordRecovery(String normalizedEmail) {
        HttpURLConnection conn = null;
        try {
            String baseUrl = stripTrailingSlash(BuildConfig.SUPABASE_URL);
            URL url = new URL(baseUrl + "/auth/v1/recover");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);

            JSONObject payload = new JSONObject();
            payload.put("email", normalizedEmail);
            writeRequestBody(conn, payload.toString());

            int status = conn.getResponseCode();
            if (status >= 500 || status == 429) {
                return AuthResult.failure(
                        AuthError.serverError(
                                "복구 메일 서버가 일시적으로 혼잡합니다(HTTP " + status + "). 잠시 후 다시 시도해 주세요.",
                                status
                        )
                );
            }
            // 200 또는 400/404 여부와 상관없이 계정 존재 여부를 노출하지 않는 동일 안내 반환
            return AuthResult.recoverySuccess(RECOVERY_NEUTRAL_GUIDANCE);
        } catch (IOException e) {
            SafeLogger.e("Supabase 계정 복구 네트워크 오류", e);
            return AuthResult.failure(
                    AuthError.serverError(
                            "네트워크 연결을 확인할 수 없습니다. 연결 후 다시 시도해 주세요.",
                            0
                    )
            );
        } catch (Exception e) {
            SafeLogger.e("Supabase 계정 복구 처리 오류", e);
            return AuthResult.failure(
                    AuthError.serverError(
                            "복구 요청 처리 중 일시적인 오류가 발생했습니다.",
                            500
                    )
            );
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private boolean fetchRemoteHasVehicle(String accessToken, String userId) {
        HttpURLConnection conn = null;
        try {
            String baseUrl = stripTrailingSlash(BuildConfig.SUPABASE_URL);
            URL url = new URL(baseUrl + "/rest/v1/vehicles?select=id&limit=1");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                String body = readResponseBody(conn, false);
                JSONArray array = new JSONArray(body);
                return array.length() > 0;
            }
        } catch (Exception ignored) {
            // 테이블 미생성 등 예외 시 로컬 캐시 기준 폴백
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return userCacheManager.hasRegisteredVehicle(userId);
    }

    private AuthSession parseSupabaseSession(String jsonBody, String fallbackEmail) throws Exception {
        JSONObject root = new JSONObject(jsonBody);
        String accessToken = root.optString("access_token", "supabase_session_" + UUID.randomUUID());
        String refreshToken = root.optString("refresh_token", "");
        long expiresIn = root.optLong("expires_in", 3600L);
        long expiresAt = root.optLong("expires_at", (System.currentTimeMillis() / 1000L) + expiresIn);

        JSONObject userObj = root.optJSONObject("user");
        String userId;
        String email;
        if (userObj != null) {
            userId = userObj.optString("id", "user_" + Math.abs(fallbackEmail.hashCode()));
            email = userObj.optString("email", fallbackEmail);
        } else {
            // Confirm email 활성화 상태에서 /auth/v1/signup 응답은 루트에 id, email이 위치함
            userId = root.optString("id", "user_" + Math.abs(fallbackEmail.hashCode()));
            email = root.optString("email", fallbackEmail);
        }

        return new AuthSession(userId, email, accessToken, refreshToken, expiresAt, true);
    }

    private static String extractSupabaseErrorMessage(String responseBody, String defaultMsg) {
        if (responseBody == null || responseBody.isEmpty()) {
            return defaultMsg;
        }
        try {
            JSONObject obj = new JSONObject(responseBody);
            String code = obj.optString("error_code", obj.optString("code", ""));
            if ("email_not_confirmed".equalsIgnoreCase(code)) {
                return "이메일 인증 대기 중입니다. 발송된 인증 메일의 링크를 확인하거나 Supabase(Auth -> Email)에서 Confirm email을 꺼주세요.";
            }
            if ("invalid_credentials".equalsIgnoreCase(code)) {
                return "이메일 또는 비밀번호가 일치하지 않습니다.";
            }
            if ("user_already_exists".equalsIgnoreCase(code) || "email_exists".equalsIgnoreCase(code)) {
                return "이미 가입된 이메일입니다. 로그인 탭을 이용해 주세요.";
            }
        } catch (Exception ignored) {
        }
        return defaultMsg;
    }

    private synchronized AuthResult finalizeAuthenticatedSession(@NonNull AuthSession session) {
        putPref(KEY_USER_ID, session.getUserId());
        putPref(KEY_EMAIL, session.getEmail());
        putPref(KEY_ACCESS_TOKEN, session.getAccessToken());
        putPref(KEY_REFRESH_TOKEN, session.getRefreshToken());
        putPref(KEY_EXPIRES_AT, Long.toString(session.getExpiresAtEpochSeconds()));
        putPref(KEY_TERMS_ACCEPTED, Boolean.toString(session.isTermsAccepted()));

        boolean previousAccountCacheHidden =
                userCacheManager.onUserAuthenticated(session.getUserId(), session.getEmail());
        boolean hasVehicle = userCacheManager.hasRegisteredVehicle(session.getUserId());

        SafeLogger.i("세션 수립 완료: user=" + session.getUserId()
                + ", hasVehicle=" + hasVehicle
                + ", prevCacheHidden=" + previousAccountCacheHidden);

        return AuthResult.loginSuccess(session, hasVehicle, previousAccountCacheHidden);
    }

    private synchronized void clearStoredSessionTokensOnly() {
        removePref(KEY_ACCESS_TOKEN);
        removePref(KEY_REFRESH_TOKEN);
        removePref(KEY_EXPIRES_AT);
    }

    private static String stripTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static void writeRequestBody(HttpURLConnection conn, String body) throws IOException {
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readResponseBody(HttpURLConnection conn, boolean errorStream) throws IOException {
        InputStream stream = errorStream ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encoded.length);
            for (byte b : encoded) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private void putPref(String key, String value) {
        if (prefs != null) {
            prefs.edit().putString(key, value).apply();
        } else {
            memoryPrefs.put(key, value);
        }
    }

    @Nullable
    private String getPref(String key, @Nullable String defaultValue) {
        if (prefs != null) {
            return prefs.getString(key, defaultValue);
        }
        return memoryPrefs.containsKey(key) ? memoryPrefs.get(key) : defaultValue;
    }

    private void removePref(String key) {
        if (prefs != null) {
            prefs.edit().remove(key).apply();
        } else {
            memoryPrefs.remove(key);
        }
    }
}
