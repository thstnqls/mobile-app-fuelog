package com.pleos.mobile_app_fuelog.data.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pleos.mobile_app_fuelog.BuildConfig;
import com.pleos.mobile_app_fuelog.core.security.SafeLogger;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Fuelog 백엔드 API 클라이언트 (http://sheetnews.ddns.net:3001/swagger-ui/index.html#/ 기준 전체 엔드포인트 통합).
 *
 * - 모바일 경로(/auth/me, /mobile/*, /pairing-requests/preview, /pairing-requests/{id}/intent, /trip-user/revoke):
 *   Supabase JWT(Authorization: Bearer <access_token>) 사용
 * - 차량 페어링 경로(/installations, /installations/me, /pairing-requests/*):
 *   X-Installation-Id 및 X-Installation-Secret 헤더 사용
 * - 차량 운행·홈·C03·C04 경로(/connection/*, /vehicle/home, /sync/status, /trip-user/*, /recording-intervals/*, /mobile-inbox/trips/*):
 *   설치 자격 증명(Authorization: Bearer <installation_secret>) 및 X-Trip-User-Session / X-Trip-User-Candidate 헤더 사용
 */
public final class FuelogBackendApiClient {

    public static final String DEFAULT_BACKEND_URL = "http://sheetnews.ddns.net:3001";

    private static volatile FuelogBackendApiClient instance;
    private final AuthRepository authRepository;

    public FuelogBackendApiClient(@NonNull AuthRepository authRepository) {
        this.authRepository = authRepository;
    }

    public static FuelogBackendApiClient getInstance(@NonNull AuthRepository authRepository) {
        if (instance == null) {
            synchronized (FuelogBackendApiClient.class) {
                if (instance == null) {
                    instance = new FuelogBackendApiClient(authRepository);
                }
            }
        }
        return instance;
    }

    @NonNull
    public static String getBaseUrl() {
        String configured = BuildConfig.BACKEND_BASE_URL;
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_BACKEND_URL;
        }
        String trimmed = configured.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    // =========================================================================
    // 1. [인증] GET /auth/me — 내 JWT 정보 조회
    // =========================================================================

    public static final class MeResponse {
        public final String userId;
        public final String email;
        @Nullable
        public final String phone;
        public final String role;
        public final boolean isAnonymous;
        public final String sessionId;
        public final String issuedAt;
        public final String expiresAt;

        public MeResponse(
                String userId,
                String email,
                @Nullable String phone,
                String role,
                boolean isAnonymous,
                String sessionId,
                String issuedAt,
                String expiresAt
        ) {
            this.userId = userId;
            this.email = email;
            this.phone = phone;
            this.role = role;
            this.isAnonymous = isAnonymous;
            this.sessionId = sessionId;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
        }
    }

    @Nullable
    public MeResponse fetchAuthMe() {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return null;
        }
        return fetchAuthMeWithToken(token);
    }

    @Nullable
    public MeResponse fetchAuthMeWithToken(@NonNull String accessToken) {
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/auth/me", "GET", accessToken);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return new MeResponse(
                        json.optString("user_id", ""),
                        json.isNull("email") ? "" : json.optString("email", ""),
                        json.isNull("phone") ? null : json.optString("phone", null),
                        json.optString("role", "authenticated"),
                        json.optBoolean("is_anonymous", false),
                        json.isNull("session_id") ? "" : json.optString("session_id", ""),
                        json.optString("issued_at", ""),
                        json.optString("expires_at", "")
                );
            } else {
                SafeLogger.w("GET /auth/me 실패 status=" + status);
            }
        } catch (Exception e) {
            SafeLogger.w("GET /auth/me 통신 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    // =========================================================================
    // 2. [차량 페어링 & 설치 관리]
    //    - POST /installations
    //    - GET  /installations/me
    //    - POST /pairing-requests
    //    - GET  /pairing-requests/current
    //    - POST /pairing-requests/preview
    //    - POST /pairing-requests/{id}/intent
    //    - GET  /pairing-requests/{id}
    //    - POST /pairing-requests/{id}/confirm
    //    - POST /pairing-requests/{id}/cancel
    // =========================================================================

    public static final class InstallationIssuedResponse {
        public final String installationId;
        public final String installationSecret;

        public InstallationIssuedResponse(String installationId, String installationSecret) {
            this.installationId = installationId;
            this.installationSecret = installationSecret;
        }
    }

    public static final class InstallationMeResponse {
        public final String installationId;
        @Nullable
        public final String vehicleId;
        public final boolean paired;

        public InstallationMeResponse(String installationId, @Nullable String vehicleId, boolean paired) {
            this.installationId = installationId;
            this.vehicleId = vehicleId;
            this.paired = paired;
        }
    }

    public static final class PairingQrIssuedResponse {
        public final String id;
        public final String expiresAt;
        public final String requestId;
        public final String proof;

        public PairingQrIssuedResponse(String id, String expiresAt, String requestId, String proof) {
            this.id = id;
            this.expiresAt = expiresAt;
            this.requestId = requestId;
            this.proof = proof;
        }
    }

    public static final class PairingStatusResponse {
        public final int httpStatus;
        public final String id;
        public final String status;
        @Nullable
        public final String vehicleId;
        @Nullable
        public final String selectedVehicleName;
        @Nullable
        public final String confirmationCode;
        @Nullable
        public final String errorMessage;

        public PairingStatusResponse(
                int httpStatus,
                String id,
                String status,
                @Nullable String vehicleId,
                @Nullable String selectedVehicleName,
                @Nullable String confirmationCode,
                @Nullable String errorMessage
        ) {
            this.httpStatus = httpStatus;
            this.id = id;
            this.status = status;
            this.vehicleId = vehicleId;
            this.selectedVehicleName = selectedVehicleName;
            this.confirmationCode = confirmationCode;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return httpStatus >= 200 && httpStatus < 300;
        }
    }

    /**
     * POST /installations — 차량 앱 최초 설치 등록 (설치 UUID + 32바이트 base64url 비밀값 발급)
     */
    @Nullable
    public InstallationIssuedResponse createInstallation() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(getBaseUrl() + "/installations");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                String instId = json.optString("installation_id", "");
                String secret = json.optString("installation_secret", "");
                if (!instId.isEmpty() && !secret.isEmpty()) {
                    return new InstallationIssuedResponse(instId, secret);
                }
            }
        } catch (Exception e) {
            SafeLogger.w("POST /installations 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * GET /installations/me — 차량 앱 설치 연결 상태 조회
     */
    @Nullable
    public InstallationMeResponse fetchInstallationMe(
            @NonNull String installationId,
            @NonNull String installationSecret
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationHeaderConnection("/installations/me", "GET", installationId, installationSecret);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return new InstallationMeResponse(
                        json.optString("installation_id", installationId),
                        json.isNull("vehicle_id") ? null : json.optString("vehicle_id", null),
                        json.optBoolean("paired", false)
                );
            }
        } catch (Exception e) {
            SafeLogger.w("GET /installations/me 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * POST /pairing-requests — 차량 앱 QR 요청 발급·재발급
     */
    @Nullable
    public PairingQrIssuedResponse issuePairingRequest(
            @NonNull String installationId,
            @NonNull String installationSecret,
            @NonNull String idempotencyKey
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationHeaderConnection("/pairing-requests", "POST", installationId, installationSecret);
            conn.setRequestProperty("Idempotency-Key", idempotencyKey);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                String id = json.optString("id", "");
                String expiresAt = json.optString("expires_at", "");
                JSONObject qr = json.optJSONObject("qr");
                String reqId = qr != null ? qr.optString("request_id", id) : id;
                String proof = qr != null ? qr.optString("proof", "") : "";
                if (!id.isEmpty() && !proof.isEmpty()) {
                    return new PairingQrIssuedResponse(id, expiresAt, reqId, proof);
                }
            }
        } catch (Exception e) {
            SafeLogger.w("POST /pairing-requests 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * GET /pairing-requests/current — 차량 앱 현재 페어링 요청 조회
     */
    @NonNull
    public PairingStatusResponse fetchCurrentPairingRequest(
            @NonNull String installationId,
            @NonNull String installationSecret
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationHeaderConnection("/pairing-requests/current", "GET", installationId, installationSecret);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                return parsePairingStatus(status, "", body);
            }
            return new PairingStatusResponse(status, "", "failed", null, null, null, "HTTP " + status);
        } catch (Exception e) {
            SafeLogger.w("GET /pairing-requests/current 예외: " + e.getMessage());
            return new PairingStatusResponse(0, "", "error", null, null, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /pairing-requests/preview — 모바일 QR 미리보기·검증
     */
    public boolean previewPairingQr(@NonNull String requestId, @NonNull String proof) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) return false;
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/pairing-requests/preview", "POST", token);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("request_id", requestId);
            payload.put("proof", proof);
            writeBody(conn, payload.toString());
            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("POST /pairing-requests/preview 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /pairing-requests/{id}/intent — 모바일 차량 선택·연결 의사 제출 (새 차량 이름 또는 기존 vehicle_id)
     */
    @NonNull
    public PairingStatusResponse submitPairingIntent(
            @NonNull String requestId,
            @NonNull String proof,
            @Nullable String existingVehicleId,
            @Nullable String newVehicleName
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return new PairingStatusResponse(401, requestId, "unauthorized", null, null, null, "로그인이 필요합니다.");
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/pairing-requests/" + requestId + "/intent", "POST", token);
            conn.setRequestProperty("Idempotency-Key", "intent-" + UUID.randomUUID());
            conn.setDoOutput(true);

            JSONObject payload = new JSONObject();
            payload.put("proof", proof);
            if (existingVehicleId != null && !existingVehicleId.trim().isEmpty()) {
                payload.put("vehicle_id", existingVehicleId.trim());
            } else if (newVehicleName != null && !newVehicleName.trim().isEmpty()) {
                payload.put("new_vehicle_name", newVehicleName.trim());
            }
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                return parsePairingStatus(status, requestId, body);
            }
            return new PairingStatusResponse(status, requestId, "failed", null, null, null, "HTTP " + status);
        } catch (Exception e) {
            SafeLogger.w("POST /pairing-requests/{id}/intent 예외: " + e.getMessage());
            return new PairingStatusResponse(0, requestId, "error", null, null, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * GET /pairing-requests/{id} — 페어링 요청 상태 조회 (모바일 Supabase Bearer JWT)
     */
    @NonNull
    public PairingStatusResponse getPairingRequestStatus(@NonNull String requestId) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return new PairingStatusResponse(401, requestId, "unauthorized", null, null, null, "로그인이 필요합니다.");
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/pairing-requests/" + requestId, "GET", token);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                return parsePairingStatus(status, requestId, body);
            }
            return new PairingStatusResponse(status, requestId, "failed", null, null, null, "HTTP " + status);
        } catch (Exception e) {
            SafeLogger.w("GET /pairing-requests/{id} 예외: " + e.getMessage());
            return new PairingStatusResponse(0, requestId, "error", null, null, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /pairing-requests/{id}/confirm — 차량 화면에서 연결 최종 확인 (실제 차량 레코드 및 설치 연결 확정)
     */
    @NonNull
    public PairingStatusResponse confirmPairingRequest(
            @NonNull String installationId,
            @NonNull String installationSecret,
            @NonNull String requestId,
            @NonNull String confirmationCode
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationHeaderConnection(
                    "/pairing-requests/" + requestId + "/confirm",
                    "POST",
                    installationId,
                    installationSecret
            );
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("confirmation_code", confirmationCode);
            payload.put("confirmationCode", confirmationCode);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                return parsePairingStatus(status, requestId, body);
            }
            return new PairingStatusResponse(status, requestId, "failed", null, null, null, "HTTP " + status);
        } catch (Exception e) {
            SafeLogger.w("POST /pairing-requests/{id}/confirm 예외: " + e.getMessage());
            return new PairingStatusResponse(0, requestId, "error", null, null, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /pairing-requests/{id}/confirm — 모바일 앱에서 6자리 코드로 연결 최종 확인 (Supabase Bearer JWT)
     */
    @NonNull
    public PairingStatusResponse confirmPairingRequestWithMobileJwt(
            @NonNull String requestId,
            @NonNull String confirmationCode
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return new PairingStatusResponse(401, requestId, "unauthorized", null, null, null, "로그인이 필요합니다.");
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection(
                    "/pairing-requests/" + requestId + "/confirm",
                    "POST",
                    token
            );
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("confirmation_code", confirmationCode);
            payload.put("confirmationCode", confirmationCode);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                return parsePairingStatus(status, requestId, body);
            }
            return new PairingStatusResponse(status, requestId, "failed", null, null, null, "HTTP " + status + ": " + body);
        } catch (Exception e) {
            SafeLogger.w("POST /pairing-requests/{id}/confirm (Mobile JWT) 예외: " + e.getMessage());
            return new PairingStatusResponse(0, requestId, "error", null, null, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /pairing-requests/{id}/cancel — 차량 앱 페어링 요청 취소
     */
    @NonNull
    public PairingStatusResponse cancelPairingRequest(
            @NonNull String installationId,
            @NonNull String installationSecret,
            @NonNull String requestId
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationHeaderConnection(
                    "/pairing-requests/" + requestId + "/cancel",
                    "POST",
                    installationId,
                    installationSecret
            );
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                return parsePairingStatus(status, requestId, body);
            }
            return new PairingStatusResponse(status, requestId, "failed", null, null, null, "HTTP " + status);
        } catch (Exception e) {
            SafeLogger.w("POST /pairing-requests/{id}/cancel 예외: " + e.getMessage());
            return new PairingStatusResponse(0, requestId, "error", null, null, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================================
    // 3. [C01 차량 연결 & M02 모바일 연결]
    //    - POST /connection/requests
    //    - POST /connection/requests/status
    //    - POST /mobile/connection/inspect
    //    - POST /mobile/connection/approve
    //    - POST /connection/reset
    // =========================================================================

    public static final class C01ConnectionRequestResponse {
        public final String requestId;
        public final String status;
        @Nullable
        public final Integer remainingSeconds;
        @Nullable
        public final String nonce;

        public C01ConnectionRequestResponse(
                String requestId,
                String status,
                @Nullable Integer remainingSeconds,
                @Nullable String nonce
        ) {
            this.requestId = requestId;
            this.status = status;
            this.remainingSeconds = remainingSeconds;
            this.nonce = nonce;
        }
    }

    /**
     * POST /connection/requests — C01 요청 발급 또는 재시도
     */
    @Nullable
    public C01ConnectionRequestResponse issueC01ConnectionRequest(
            @NonNull String installationSecret,
            @NonNull String operationId,
            @Nullable String requestId
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/connection/requests", "POST", installationSecret, null, null);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("operationId", operationId);
            if (requestId != null && !requestId.isEmpty()) {
                payload.put("requestId", requestId);
            } else {
                payload.put("requestId", JSONObject.NULL);
            }
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return new C01ConnectionRequestResponse(
                        json.optString("requestId", ""),
                        json.optString("status", ""),
                        json.isNull("remainingSeconds") ? null : json.optInt("remainingSeconds"),
                        json.isNull("nonce") ? null : json.optString("nonce", null)
                );
            }
        } catch (Exception e) {
            SafeLogger.w("POST /connection/requests 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * POST /connection/requests/status — C01 요청 상태 조회
     */
    @Nullable
    public C01ConnectionRequestResponse fetchC01ConnectionRequestStatus(
            @NonNull String installationSecret,
            @NonNull String operationId,
            @NonNull String requestId
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/connection/requests/status", "POST", installationSecret, null, null);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("operationId", operationId);
            payload.put("requestId", requestId);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return new C01ConnectionRequestResponse(
                        json.optString("requestId", requestId),
                        json.optString("status", ""),
                        json.isNull("remainingSeconds") ? null : json.optInt("remainingSeconds"),
                        json.isNull("nonce") ? null : json.optString("nonce", null)
                );
            }
        } catch (Exception e) {
            SafeLogger.w("POST /connection/requests/status 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * POST /mobile/connection/inspect — M02 모바일 연결 QR 검증
     */
    public int inspectMobileConnectionQr(@NonNull String requestId, @NonNull String nonce) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) return -1;
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/connection/inspect", "POST", token);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("type", "fuelog.vehicle-registration");
            payload.put("version", 1);
            payload.put("requestId", requestId);
            payload.put("nonce", nonce);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return json.optInt("remainingSeconds", 0);
            }
        } catch (Exception e) {
            SafeLogger.w("POST /mobile/connection/inspect 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return -1;
    }

    /**
     * POST /mobile/connection/approve — M02 기존 차량 연결 승인
     */
    public boolean approveMobileConnection(
            @NonNull String requestId,
            @NonNull String nonce,
            @NonNull String vehicleId,
            @NonNull String idempotencyKey
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) return false;
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/connection/approve", "POST", token);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("requestId", requestId);
            payload.put("nonce", nonce);
            payload.put("vehicleId", vehicleId);
            payload.put("idempotencyKey", idempotencyKey);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("POST /mobile/connection/approve 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /connection/reset — C07 차량 연결 초기화
     */
    public boolean resetVehicleConnection(
            @NonNull String installationSecret,
            @NonNull String vehicleId
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) return false;
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/connection/reset", "POST", installationSecret, null, null);
            conn.setRequestProperty("X-User-Authorization", "Bearer " + token);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("vehicleId", vehicleId);
            writeBody(conn, payload.toString());
            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("POST /connection/reset 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================================
    // 4. [C02 차량 홈 · 수동 기록 · 동기화 상태]
    //    - GET  /vehicle/home
    //    - GET  /sync/status
    //    - POST /recording-intervals
    //    - POST /recording-intervals/{id}/samples
    //    - POST /recording-intervals/{id}/finish
    // =========================================================================

    public static final class VehicleHomeResponse {
        public final boolean success;
        public final String vehicleAlias;
        public final long userSessionVersion;
        public final String recordingStatus;
        @Nullable
        public final Double distanceKm;
        @Nullable
        public final Long estimatedCostWon;
        @Nullable
        public final Integer fuelReviewCount;
        @Nullable
        public final Integer maintenanceDueCount;
        @Nullable
        public final String lastSampleAt;
        @Nullable
        public final String syncState;
        @Nullable
        public final String generatedAt;

        public VehicleHomeResponse(
                boolean success,
                String vehicleAlias,
                long userSessionVersion,
                String recordingStatus,
                @Nullable Double distanceKm,
                @Nullable Long estimatedCostWon,
                @Nullable Integer fuelReviewCount,
                @Nullable Integer maintenanceDueCount,
                @Nullable String lastSampleAt,
                @Nullable String syncState,
                @Nullable String generatedAt
        ) {
            this.success = success;
            this.vehicleAlias = vehicleAlias;
            this.userSessionVersion = userSessionVersion;
            this.recordingStatus = recordingStatus;
            this.distanceKm = distanceKm;
            this.estimatedCostWon = estimatedCostWon;
            this.fuelReviewCount = fuelReviewCount;
            this.maintenanceDueCount = maintenanceDueCount;
            this.lastSampleAt = lastSampleAt;
            this.syncState = syncState;
            this.generatedAt = generatedAt;
        }
    }

    public static final class SyncStatusResponse {
        public final boolean success;
        @Nullable
        public final String intervalId;
        public final long lastAcceptedSequence;
        @Nullable
        public final String lastSyncedAt;
        @Nullable
        public final String state;

        public SyncStatusResponse(
                boolean success,
                @Nullable String intervalId,
                long lastAcceptedSequence,
                @Nullable String lastSyncedAt,
                @Nullable String state
        ) {
            this.success = success;
            this.intervalId = intervalId;
            this.lastAcceptedSequence = lastAcceptedSequence;
            this.lastSyncedAt = lastSyncedAt;
            this.state = state;
        }
    }

    public static final class RecordingIntervalResponse {
        public final String id;
        public final String vehicleId;
        public final String localIntervalId;
        public final String status;
        public final String startedAt;
        @Nullable
        public final String endedAt;
        public final long lastAcceptedSequence;

        public RecordingIntervalResponse(
                String id,
                String vehicleId,
                String localIntervalId,
                String status,
                String startedAt,
                @Nullable String endedAt,
                long lastAcceptedSequence
        ) {
            this.id = id;
            this.vehicleId = vehicleId;
            this.localIntervalId = localIntervalId;
            this.status = status;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.lastAcceptedSequence = lastAcceptedSequence;
        }
    }

    /**
     * GET /vehicle/home — C02 차량 홈 조회
     */
    @NonNull
    public VehicleHomeResponse fetchVehicleHome(
            @NonNull String installationSecret,
            @Nullable String scopedToken
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/vehicle/home", "GET", installationSecret, scopedToken, null);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                Double dist = json.isNull("distanceKm") ? null : json.optDouble("distanceKm");
                Long estCost = null;
                JSONObject costObj = json.optJSONObject("estimatedCost");
                if (costObj != null && !costObj.isNull("amount")) {
                    estCost = Math.round(costObj.optDouble("amount"));
                } else if (costObj != null && !costObj.isNull("value")) {
                    estCost = Math.round(costObj.optDouble("value"));
                }
                Integer fuelReviewCount = json.isNull("fuelReviewCount") ? null : json.optInt("fuelReviewCount");
                Integer maintenanceDueCount = json.isNull("maintenanceDueCount") ? null : json.optInt("maintenanceDueCount");
                JSONObject syncObj = json.optJSONObject("syncStatus");
                String syncState = syncObj != null ? syncObj.optString("state", null) : null;
                return new VehicleHomeResponse(
                        true,
                        json.optString("vehicleAlias", "내 차"),
                        json.optLong("userSessionVersion", 0L),
                        json.optString("recordingStatus", "unknown"),
                        dist,
                        estCost,
                        fuelReviewCount,
                        maintenanceDueCount,
                        json.isNull("lastSampleAt") ? null : json.optString("lastSampleAt", null),
                        syncState,
                        json.isNull("generatedAt") ? null : json.optString("generatedAt", null)
                );
            }
        } catch (Exception e) {
            SafeLogger.w("GET /vehicle/home 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return new VehicleHomeResponse(false, "", 0L, "unknown", null, null, null, null, null, null, null);
    }

    /**
     * GET /sync/status — 서버 수신 상태 조회
     */
    @NonNull
    public SyncStatusResponse fetchSyncStatus(@NonNull String installationSecret) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/sync/status", "GET", installationSecret, null, null);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                JSONObject stateObj = json.optJSONObject("state");
                return new SyncStatusResponse(
                        true,
                        json.isNull("intervalId") ? null : json.optString("intervalId", null),
                        json.optLong("lastAcceptedSequence", 0L),
                        json.isNull("lastSyncedAt") ? null : json.optString("lastSyncedAt", null),
                        stateObj != null ? stateObj.optString("state", null) : null
                );
            }
        } catch (Exception e) {
            SafeLogger.w("GET /sync/status 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return new SyncStatusResponse(false, null, 0L, null, null);
    }

    /**
     * POST /recording-intervals — 앱 수동 기록 구간 시작
     */
    @Nullable
    public RecordingIntervalResponse startRecordingInterval(
            @NonNull String installationSecret,
            @Nullable String scopedToken,
            @NonNull String localIntervalId,
            @NonNull String startedAtIso
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/recording-intervals", "POST", installationSecret, scopedToken, null);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("localIntervalId", localIntervalId);
            payload.put("startedAt", startedAtIso);
            payload.put("source", "manual_app");
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return new RecordingIntervalResponse(
                        json.optString("id", ""),
                        json.optString("vehicleId", ""),
                        json.optString("localIntervalId", localIntervalId),
                        json.optString("status", "recording"),
                        json.optString("startedAt", startedAtIso),
                        json.isNull("endedAt") ? null : json.optString("endedAt", null),
                        json.optLong("lastAcceptedSequence", 0L)
                );
            }
        } catch (Exception e) {
            SafeLogger.w("POST /recording-intervals 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * POST /recording-intervals/{id}/samples — 거리 샘플 배치 업로드
     */
    public boolean uploadRecordingSamples(
            @NonNull String installationSecret,
            @NonNull String intervalId,
            @Nullable String candidateId,
            @NonNull String batchId,
            double distanceKm,
            double fuelLiters
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection(
                    "/recording-intervals/" + intervalId + "/samples",
                    "POST",
                    installationSecret,
                    null,
                    candidateId
            );
            conn.setDoOutput(true);
            JSONArray samples = new JSONArray();
            JSONObject sample1 = new JSONObject();
            sample1.put("sequence", 1);
            sample1.put("observedAt", Instant.now().toString());
            sample1.put("distanceKm", distanceKm);
            sample1.put("fuelLiters", fuelLiters);
            samples.put(sample1);

            JSONObject payload = new JSONObject();
            payload.put("batchId", batchId);
            payload.put("samples", samples);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("POST /recording-intervals/{id}/samples 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /recording-intervals/{id}/finish — 기록 구간 종료
     */
    public boolean finishRecordingInterval(
            @NonNull String installationSecret,
            @NonNull String intervalId,
            @NonNull String operationId,
            @NonNull String endedAtIso,
            long lastSequence
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection(
                    "/recording-intervals/" + intervalId + "/finish",
                    "POST",
                    installationSecret,
                    null,
                    null
            );
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("operationId", operationId);
            payload.put("endedAt", endedAtIso);
            payload.put("lastSequence", lastSequence);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("POST /recording-intervals/{id}/finish 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================================
    // 5. [M02/M04/C03 운행 사용자 확인·동의·후보 조회·검증·폐기]
    //    - POST   /trip-user/challenges
    //    - POST   /mobile/trip-user/approve
    //    - POST   /trip-user/session
    //    - DELETE /trip-user/session
    //    - POST   /mobile/trip-user/consent
    //    - POST   /trip-user/candidates
    //    - GET    /trip-user/events
    //    - POST   /trip-user/verify
    //    - POST   /trip-user/revoke
    // =========================================================================

    public static final class TripUserChallengeResponse {
        public final String challengeId;
        public final String status;
        public final String expiresAt;
        @Nullable
        public final String proof;

        public TripUserChallengeResponse(
                String challengeId,
                String status,
                String expiresAt,
                @Nullable String proof
        ) {
            this.challengeId = challengeId;
            this.status = status;
            this.expiresAt = expiresAt;
            this.proof = proof;
        }
    }

    public static final class TripUserSessionResponse {
        public final String status;
        public final String challengeId;
        @Nullable
        public final String displayName;
        @Nullable
        public final String subjectId;
        public final long sessionVersion;
        @Nullable
        public final String expiresAt;
        @Nullable
        public final String scopedToken;

        public TripUserSessionResponse(
                String status,
                String challengeId,
                @Nullable String displayName,
                @Nullable String subjectId,
                long sessionVersion,
                @Nullable String expiresAt,
                @Nullable String scopedToken
        ) {
            this.status = status;
            this.challengeId = challengeId;
            this.displayName = displayName;
            this.subjectId = subjectId;
            this.sessionVersion = sessionVersion;
            this.expiresAt = expiresAt;
            this.scopedToken = scopedToken;
        }
    }

    public static final class MobileConsentResponse {
        public final int httpStatus;
        @Nullable
        public final String consentId;
        @Nullable
        public final String candidateId;
        @Nullable
        public final String intervalId;
        @Nullable
        public final String status;
        @Nullable
        public final String expiresAt;

        public MobileConsentResponse(
                int httpStatus,
                @Nullable String consentId,
                @Nullable String candidateId,
                @Nullable String intervalId,
                @Nullable String status,
                @Nullable String expiresAt
        ) {
            this.httpStatus = httpStatus;
            this.consentId = consentId;
            this.candidateId = candidateId;
            this.intervalId = intervalId;
            this.status = status;
            this.expiresAt = expiresAt;
        }

        public boolean isSuccess() {
            return httpStatus >= 200 && httpStatus < 300 && consentId != null;
        }
    }

    public static final class TripUserVerifiedResponse {
        public final boolean success;
        public final String intervalId;
        public final String candidateId;
        public final String subjectId;
        public final boolean vehicleMember;
        public final boolean mobileConsented;
        public final long expiresAtEpochMillis;

        public TripUserVerifiedResponse(
                boolean success,
                String intervalId,
                String candidateId,
                String subjectId,
                boolean vehicleMember,
                boolean mobileConsented,
                long expiresAtEpochMillis
        ) {
            this.success = success;
            this.intervalId = intervalId;
            this.candidateId = candidateId;
            this.subjectId = subjectId;
            this.vehicleMember = vehicleMember;
            this.mobileConsented = mobileConsented;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }
    }

    /**
     * POST /trip-user/challenges — 운행 사용자 확인 QR 발급 (C02 설치 전체 확인 또는 C03 현재 구간 후보 확인)
     */
    @Nullable
    public TripUserChallengeResponse issueTripUserChallenge(
            @NonNull String installationSecret,
            @Nullable String installationId,
            @Nullable String intervalId,
            @NonNull String operationId
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/trip-user/challenges", "POST", installationSecret, null, null);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            if (installationId != null && !installationId.isEmpty()) {
                payload.put("installationId", installationId);
            }
            if (intervalId != null && !intervalId.isEmpty()) {
                payload.put("intervalId", intervalId);
            }
            payload.put("operationId", operationId);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return new TripUserChallengeResponse(
                        json.optString("challengeId", ""),
                        json.optString("status", "PENDING"),
                        json.optString("expiresAt", ""),
                        json.isNull("proof") ? null : json.optString("proof", null)
                );
            }
        } catch (Exception e) {
            SafeLogger.w("POST /trip-user/challenges 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * POST /mobile/trip-user/approve — 운행 사용자 QR 승인 (C02)
     */
    public boolean approveTripUserQr(
            @NonNull String challengeId,
            @NonNull String proof,
            @NonNull String vehicleId
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/trip-user/approve", "POST", token);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("challengeId", challengeId);
            payload.put("proof", proof);
            payload.put("vehicleId", vehicleId);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("POST /mobile/trip-user/approve 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /trip-user/session — 운행 사용자 세션 조회 (scopedToken 획득)
     */
    @Nullable
    public TripUserSessionResponse fetchTripUserSession(
            @NonNull String installationSecret,
            @NonNull String challengeId
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/trip-user/session", "POST", installationSecret, null, null);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("challengeId", challengeId);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return new TripUserSessionResponse(
                        json.optString("status", ""),
                        json.optString("challengeId", challengeId),
                        json.isNull("displayName") ? null : json.optString("displayName", null),
                        json.isNull("subjectId") ? null : json.optString("subjectId", null),
                        json.optLong("sessionVersion", 0L),
                        json.isNull("expiresAt") ? null : json.optString("expiresAt", null),
                        json.isNull("scopedToken") ? null : json.optString("scopedToken", null)
                );
            }
        } catch (Exception e) {
            SafeLogger.w("POST /trip-user/session 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * DELETE /trip-user/session — 운행 사용자 세션 폐기
     */
    public boolean deleteTripUserSession(
            @NonNull String installationSecret,
            @NonNull String scopedToken
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/trip-user/session", "DELETE", installationSecret, scopedToken, null);
            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("DELETE /trip-user/session 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /mobile/trip-user/consent — C03 이번 구간 사용자 모바일 동의
     */
    @NonNull
    public MobileConsentResponse submitTripUserConsent(
            @NonNull String vehicleId,
            @NonNull String installationId,
            @NonNull String intervalId,
            @NonNull String challengeId,
            @NonNull String proof,
            @NonNull String operationId
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return new MobileConsentResponse(401, null, null, intervalId, "UNAUTHORIZED", null);
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/trip-user/consent", "POST", token);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("vehicleId", vehicleId);
            payload.put("installationId", installationId);
            payload.put("intervalId", intervalId);
            payload.put("challengeId", challengeId);
            payload.put("proof", proof);
            payload.put("operationId", operationId);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return new MobileConsentResponse(
                        status,
                        json.optString("consentId", null),
                        json.optString("candidateId", null),
                        json.optString("intervalId", intervalId),
                        json.optString("status", "ACTIVE"),
                        json.optString("expiresAt", null)
                );
            }
            return new MobileConsentResponse(status, null, null, intervalId, "HTTP_" + status, null);
        } catch (Exception e) {
            SafeLogger.w("POST /mobile/trip-user/consent 예외: " + e.getMessage());
            return new MobileConsentResponse(0, null, null, intervalId, "NETWORK_ERROR", null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /trip-user/candidates — 현재 구간의 유효 후보 조회
     */
    public int fetchTripUserCandidatesCount(
            @NonNull String installationSecret,
            @NonNull String intervalId
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/trip-user/candidates", "POST", installationSecret, null, null);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("intervalId", intervalId);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                JSONArray arr = json.optJSONArray("candidates");
                return arr != null ? arr.length() : 0;
            }
        } catch (Exception e) {
            SafeLogger.w("POST /trip-user/candidates 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return 0;
    }

    /**
     * GET /trip-user/events?cursor={cursor} — 후보 변경 이벤트 조회
     */
    public long fetchTripUserEventsRevision(@NonNull String installationSecret, long cursor) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/trip-user/events?cursor=" + cursor, "GET", installationSecret, null, null);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return json.optLong("revision", cursor);
            }
        } catch (Exception e) {
            SafeLogger.w("GET /trip-user/events 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return cursor;
    }

    /**
     * POST /trip-user/verify — 차량 화면에서 후보 최종 확인
     */
    @NonNull
    public TripUserVerifiedResponse verifyTripUserCandidate(
            @NonNull String installationSecret,
            @NonNull String intervalId,
            @NonNull String candidateId,
            @NonNull String operationId
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection("/trip-user/verify", "POST", installationSecret, null, null);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("intervalId", intervalId);
            payload.put("candidateId", candidateId);
            payload.put("operationId", operationId);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return new TripUserVerifiedResponse(
                        true,
                        json.optString("intervalId", intervalId),
                        json.optString("candidateId", candidateId),
                        json.optString("subjectId", ""),
                        json.optBoolean("vehicleMember", true),
                        json.optBoolean("mobileConsented", true),
                        json.optLong("expiresAtEpochMillis", 0L)
                );
            }
        } catch (Exception e) {
            SafeLogger.w("POST /trip-user/verify 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return new TripUserVerifiedResponse(false, intervalId, candidateId, "", false, false, 0L);
    }

    /**
     * POST /trip-user/revoke — 후보·향후 사용 권한 폐기 (모바일 사용자 Supabase JWT 또는 설치 Bearer 사용)
     */
    public boolean revokeTripUserConsent(@NonNull String consentId) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/trip-user/revoke", "POST", token);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("consentId", consentId);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("POST /trip-user/revoke 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================================
    // 6. [C04 운행 요약 업로드·조회 & M06 모바일 확인함 전달·조회·읽음 처리]
    //    - PUT   /recording-intervals/{tripId}
    //    - GET   /recording-intervals/{tripId}/summary
    //    - PUT   /mobile-inbox/trips/{tripId}
    //    - GET   /mobile/inbox?cursor={cursor}
    //    - PATCH /mobile/inbox/{itemId}
    // =========================================================================

    public static final class C04TripSummaryResponse {
        public final boolean success;
        public final String tripId;
        public final long revision;
        @Nullable
        public final Double distanceKm;
        @Nullable
        public final Double fuelConsumedLiters;
        @Nullable
        public final Double fuelEfficiencyKmPerLiter;
        @Nullable
        public final Long estimatedCostWon;
        @Nullable
        public final String priceSource;
        @Nullable
        public final String startedAt;
        @Nullable
        public final String endedAt;
        public final boolean missing;
        public final boolean endEstimated;

        public C04TripSummaryResponse(
                boolean success,
                String tripId,
                long revision,
                @Nullable Double distanceKm,
                @Nullable Double fuelConsumedLiters,
                @Nullable Double fuelEfficiencyKmPerLiter,
                @Nullable Long estimatedCostWon,
                @Nullable String priceSource,
                @Nullable String startedAt,
                @Nullable String endedAt,
                boolean missing,
                boolean endEstimated
        ) {
            this.success = success;
            this.tripId = tripId;
            this.revision = revision;
            this.distanceKm = distanceKm;
            this.fuelConsumedLiters = fuelConsumedLiters;
            this.fuelEfficiencyKmPerLiter = fuelEfficiencyKmPerLiter;
            this.estimatedCostWon = estimatedCostWon;
            this.priceSource = priceSource;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.missing = missing;
            this.endEstimated = endEstimated;
        }
    }

    public static final class InboxTripSummaryItem {
        public final long inboxItemId;
        public final String tripId;
        public final long tripRevision;
        public final String deliveredAt;
        @Nullable
        public final String readAt;
        @Nullable
        public final Double distanceKm;
        @Nullable
        public final Double fuelConsumedLiters;
        @Nullable
        public final Double fuelEfficiencyKmPerLiter;
        @Nullable
        public final Long estimatedCostWon;
        @Nullable
        public final String priceSource;
        @Nullable
        public final String startedAt;
        @Nullable
        public final String endedAt;

        public InboxTripSummaryItem(
                long inboxItemId,
                String tripId,
                long tripRevision,
                String deliveredAt,
                @Nullable String readAt,
                @Nullable Double distanceKm,
                @Nullable Double fuelConsumedLiters,
                @Nullable Double fuelEfficiencyKmPerLiter,
                @Nullable Long estimatedCostWon,
                @Nullable String priceSource,
                @Nullable String startedAt,
                @Nullable String endedAt
        ) {
            this.inboxItemId = inboxItemId;
            this.tripId = tripId;
            this.tripRevision = tripRevision;
            this.deliveredAt = deliveredAt;
            this.readAt = readAt;
            this.distanceKm = distanceKm;
            this.fuelConsumedLiters = fuelConsumedLiters;
            this.fuelEfficiencyKmPerLiter = fuelEfficiencyKmPerLiter;
            this.estimatedCostWon = estimatedCostWon;
            this.priceSource = priceSource;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
        }
    }

    public static final class InboxPageResponse {
        public final boolean success;
        public final int httpStatus;
        public final List<InboxTripSummaryItem> items;
        @Nullable
        public final Long nextCursor;

        public InboxPageResponse(
                boolean success,
                int httpStatus,
                List<InboxTripSummaryItem> items,
                @Nullable Long nextCursor
        ) {
            this.success = success;
            this.httpStatus = httpStatus;
            this.items = items;
            this.nextCursor = nextCursor;
        }
    }

    /**
     * PUT /recording-intervals/{tripId} — 오프라인/운행 요약 멱등 업로드 (C04)
     */
    public boolean uploadTripSummary(
            @NonNull String installationSecret,
            @NonNull String tripId,
            long revision,
            @Nullable String userConsentId,
            @NonNull String startedAtIso,
            @NonNull String endedAtIso,
            double distanceKm,
            double startFuelLiters,
            double endFuelLiters,
            double unitPriceWon,
            @NonNull String priceSource
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection(
                    "/recording-intervals/" + tripId,
                    "PUT",
                    installationSecret,
                    null,
                    null
            );
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("tripId", tripId);
            payload.put("revision", revision);
            payload.put("source", "manual_app");
            if (userConsentId != null && !userConsentId.isEmpty()) {
                payload.put("userConsentId", userConsentId);
            } else {
                payload.put("userConsentId", JSONObject.NULL);
            }
            payload.put("startedAt", startedAtIso);
            payload.put("endedAt", endedAtIso);
            payload.put("endEstimated", false);
            payload.put("distanceKm", distanceKm);
            payload.put("distanceValid", true);
            payload.put("startFuelLiters", startFuelLiters);
            payload.put("endFuelLiters", endFuelLiters);
            payload.put("fuelEndpointsValid", true);
            payload.put("missing", false);
            payload.put("refueled", false);
            payload.put("ownerChanged", false);
            payload.put("unitPrice", unitPriceWon);
            payload.put("currency", "KRW");
            payload.put("priceSource", priceSource);
            payload.put("priceObservedAt", startedAtIso);
            payload.put("priceValid", true);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("PUT /recording-intervals/{tripId} 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * GET /recording-intervals/{tripId}/summary — 저장된 운행 요약 조회 (C04)
     */
    @NonNull
    public C04TripSummaryResponse fetchTripSummary(
            @NonNull String installationSecret,
            @NonNull String tripId,
            @Nullable String candidateId
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection(
                    "/recording-intervals/" + tripId + "/summary",
                    "GET",
                    installationSecret,
                    null,
                    candidateId
            );
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return parseC04SummaryJson(tripId, json);
            }
        } catch (Exception e) {
            SafeLogger.w("GET /recording-intervals/{tripId}/summary 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return new C04TripSummaryResponse(false, tripId, 1L, null, null, null, null, null, null, null, false, false);
    }

    /**
     * PUT /mobile-inbox/trips/{tripId} — 모바일 확인함에 운행 전달 (C04 -> M06)
     */
    public long deliverTripToMobileInbox(
            @NonNull String installationSecret,
            @NonNull String tripId,
            @Nullable String candidateId,
            @NonNull String operationId,
            long tripRevision
    ) {
        HttpURLConnection conn = null;
        try {
            conn = openInstallationBearerConnection(
                    "/mobile-inbox/trips/" + tripId,
                    "PUT",
                    installationSecret,
                    null,
                    candidateId
            );
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("operationId", operationId);
            payload.put("tripRevision", tripRevision);
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject json = new JSONObject(body);
                return json.optLong("inboxItemId", 1L);
            }
        } catch (Exception e) {
            SafeLogger.w("PUT /mobile-inbox/trips/{tripId} 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return -1L;
    }

    /** 모바일 사용자의 소유·참여 차량 모델 */
    public static class BackendVehicleItem {
        public final String id;
        public final String vehicleId;
        public final String name;
        public final String vehicleName;
        public final String role;
        public final String createdAt;
        public final boolean paired;
        public final String installationId;

        public BackendVehicleItem(
                String id,
                String vehicleId,
                String name,
                String vehicleName,
                String role,
                String createdAt,
                boolean paired,
                String installationId
        ) {
            this.id = id;
            this.vehicleId = vehicleId != null ? vehicleId : id;
            this.name = name;
            this.vehicleName = vehicleName != null ? vehicleName : name;
            this.role = role != null ? role : "OWNER";
            this.createdAt = createdAt;
            this.paired = paired;
            this.installationId = installationId;
        }

        @NonNull
        public String getDisplayName() {
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
            if (vehicleName != null && !vehicleName.trim().isEmpty()) {
                return vehicleName.trim();
            }
            return "차량 " + (vehicleId != null && vehicleId.length() >= 8 ? vehicleId.substring(0, 8) : "");
        }
    }

    /**
     * GET /mobile/vehicles — 모바일 사용자의 소유·관리·참여 전체 차량 목록 조회 (Supabase Bearer JWT)
     */
    @NonNull
    public List<BackendVehicleItem> fetchMobileVehicles() {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return Collections.emptyList();
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles", "GET", token);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONArray arr = new JSONArray(body);
                List<BackendVehicleItem> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String id = obj.optString("id", "");
                    String vehicleId = obj.optString("vehicleId", id);
                    String name = obj.optString("name", "");
                    String vehicleName = obj.optString("vehicleName", name);
                    String role = obj.optString("role", "OWNER");
                    String createdAt = obj.optString("createdAt", "");
                    boolean paired = obj.optBoolean("paired", false);
                    String installationId = obj.isNull("installationId") ? null : obj.optString("installationId", null);
                    list.add(new BackendVehicleItem(id, vehicleId, name, vehicleName, role, createdAt, paired, installationId));
                }
                SafeLogger.i("GET /mobile/vehicles 조회 성공: " + list.size() + "대 수신");
                return list;
            } else {
                SafeLogger.w("GET /mobile/vehicles 실패 status=" + status + ", body=" + body);
            }
        } catch (Exception e) {
            SafeLogger.w("GET /mobile/vehicles 예외: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return Collections.emptyList();
    }

    /**
     * GET /mobile/inbox?cursor={cursor} — 내 운행 확인함 조회 (Supabase Bearer JWT)
     */
    @NonNull
    public InboxPageResponse fetchMobileInbox(long cursor) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return new InboxPageResponse(false, 401, new ArrayList<>(), null);
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/inbox?cursor=" + cursor, "GET", token);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject root = new JSONObject(body);
                JSONArray arr = root.optJSONArray("items");
                List<InboxTripSummaryItem> list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject itemObj = arr.getJSONObject(i);
                        long inboxItemId = itemObj.optLong("inboxItemId", 0L);
                        String tripId = itemObj.optString("tripId", "");
                        long tripRevision = itemObj.optLong("tripRevision", 1L);
                        String deliveredAt = itemObj.optString("deliveredAt", "");
                        String readAt = itemObj.isNull("readAt") ? null : itemObj.optString("readAt", null);

                        Double distanceKm = null;
                        Double fuelLiters = null;
                        Double efficiency = null;
                        Long estimatedCost = null;
                        String priceSource = null;
                        String startedAt = null;
                        String endedAt = null;

                        JSONObject summary = itemObj.optJSONObject("summary");
                        if (summary != null) {
                            C04TripSummaryResponse parsed = parseC04SummaryJson(tripId, summary);
                            distanceKm = parsed.distanceKm;
                            fuelLiters = parsed.fuelConsumedLiters;
                            efficiency = parsed.fuelEfficiencyKmPerLiter;
                            estimatedCost = parsed.estimatedCostWon;
                            priceSource = parsed.priceSource;
                            startedAt = parsed.startedAt;
                            endedAt = parsed.endedAt;
                        }

                        list.add(new InboxTripSummaryItem(
                                inboxItemId,
                                tripId,
                                tripRevision,
                                deliveredAt,
                                readAt,
                                distanceKm,
                                fuelLiters,
                                efficiency,
                                estimatedCost,
                                priceSource,
                                startedAt,
                                endedAt
                        ));
                    }
                }
                Long nextCursor = root.isNull("nextCursor") ? null : root.optLong("nextCursor");
                return new InboxPageResponse(true, status, list, nextCursor);
            }
            return new InboxPageResponse(false, status, new ArrayList<>(), null);
        } catch (Exception e) {
            SafeLogger.w("GET /mobile/inbox 예외: " + e.getMessage());
            return new InboxPageResponse(false, 0, new ArrayList<>(), null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * PATCH /mobile/inbox/{itemId} — 내 확인함 항목 읽음 처리 (Supabase Bearer JWT)
     */
    public boolean markInboxItemAsRead(long itemId) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/inbox/" + itemId, "PATCH", token);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("readAt", Instant.now().toString());
            writeBody(conn, payload.toString());
            int status = conn.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("PATCH /mobile/inbox/" + itemId + " 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static final class FuelCandidateDetailResponse {
        public final boolean success;
        public final int httpStatus;
        @Nullable public final String id;
        @Nullable public final String status; // CANDIDATE, CONFIRMED, REJECTED
        public final long revision;
        public final boolean capacityVerified;
        @Nullable public final Double estimatedLiters;
        @Nullable public final Double estimatedAmount;
        @Nullable public final Long priceAt;
        @NonNull public final List<String> stationCandidates;
        @Nullable public final String intervalId;
        @Nullable public final Long startAt;
        @Nullable public final Long endAt;
        @Nullable public final Double increasePercent;
        @Nullable public final String correctedPlace;
        @Nullable public final Double correctedLiters;
        @Nullable public final Double correctedAmount;
        @Nullable public final String errorMessage;

        public FuelCandidateDetailResponse(
                boolean success,
                int httpStatus,
                @Nullable String id,
                @Nullable String status,
                long revision,
                boolean capacityVerified,
                @Nullable Double estimatedLiters,
                @Nullable Double estimatedAmount,
                @Nullable Long priceAt,
                @NonNull List<String> stationCandidates,
                @Nullable String intervalId,
                @Nullable Long startAt,
                @Nullable Long endAt,
                @Nullable Double increasePercent,
                @Nullable String correctedPlace,
                @Nullable Double correctedLiters,
                @Nullable Double correctedAmount,
                @Nullable String errorMessage
        ) {
            this.success = success;
            this.httpStatus = httpStatus;
            this.id = id;
            this.status = status;
            this.revision = revision;
            this.capacityVerified = capacityVerified;
            this.estimatedLiters = estimatedLiters;
            this.estimatedAmount = estimatedAmount;
            this.priceAt = priceAt;
            this.stationCandidates = stationCandidates;
            this.intervalId = intervalId;
            this.startAt = startAt;
            this.endAt = endAt;
            this.increasePercent = increasePercent;
            this.correctedPlace = correctedPlace;
            this.correctedLiters = correctedLiters;
            this.correctedAmount = correctedAmount;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return success && httpStatus >= 200 && httpStatus < 300;
        }
    }

    /**
     * GET /mobile/fuel-candidates/{id} — 내 주유 후보 상세 조회 (Supabase Bearer JWT)
     */
    @NonNull
    public FuelCandidateDetailResponse fetchFuelCandidateDetail(@NonNull String candidateId) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return new FuelCandidateDetailResponse(false, 401, candidateId, null, 0L, false, null, null, null, Collections.emptyList(), null, null, null, null, null, null, null, "인증 토큰이 없습니다.");
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/fuel-candidates/" + candidateId, "GET", token);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                return parseFuelCandidateJson(status, candidateId, body);
            }
            return new FuelCandidateDetailResponse(false, status, candidateId, null, 0L, false, null, null, null, Collections.emptyList(), null, null, null, null, null, null, null, "HTTP " + status + ": " + body);
        } catch (Exception e) {
            SafeLogger.w("GET /mobile/fuel-candidates/" + candidateId + " 예외: " + e.getMessage());
            return new FuelCandidateDetailResponse(false, 0, candidateId, null, 0L, false, null, null, null, Collections.emptyList(), null, null, null, null, null, null, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * PATCH /mobile/fuel-candidates/{id} — 주유 후보 확인·기각 및 보정 (M07)
     */
    @NonNull
    public FuelCandidateDetailResponse patchFuelCandidate(
            @NonNull String candidateId,
            @NonNull String action, // CONFIRM or REJECT
            long expectedRevision,
            @Nullable String place,
            @Nullable Double liters,
            @Nullable Double amount
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return new FuelCandidateDetailResponse(false, 401, candidateId, null, expectedRevision, false, null, null, null, Collections.emptyList(), null, null, null, null, null, null, null, "인증 토큰이 없습니다.");
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/fuel-candidates/" + candidateId, "PATCH", token);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("operationId", "patch-" + UUID.randomUUID());
            payload.put("expectedRevision", expectedRevision);
            payload.put("action", action);
            if (place != null) {
                payload.put("place", place);
            }
            if (liters != null) {
                payload.put("liters", liters);
            }
            if (amount != null) {
                payload.put("amount", amount);
            }
            writeBody(conn, payload.toString());

            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                return parseFuelCandidateJson(status, candidateId, body);
            }
            return new FuelCandidateDetailResponse(false, status, candidateId, null, expectedRevision, false, null, null, null, Collections.emptyList(), null, null, null, null, null, null, null, "HTTP " + status + ": " + body);
        } catch (Exception e) {
            SafeLogger.w("PATCH /mobile/fuel-candidates/" + candidateId + " 예외: " + e.getMessage());
            return new FuelCandidateDetailResponse(false, 0, candidateId, null, expectedRevision, false, null, null, null, Collections.emptyList(), null, null, null, null, null, null, null, e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @NonNull
    private static FuelCandidateDetailResponse parseFuelCandidateJson(int status, @NonNull String fallbackId, @NonNull String body) {
        try {
            JSONObject obj = new JSONObject(body);
            String id = obj.optString("id", fallbackId);
            String candidateStatus = obj.optString("status", "CANDIDATE");
            long revision = obj.optLong("revision", 1L);
            boolean capacityVerified = obj.optBoolean("capacityVerified", false);
            Double estLiters = obj.isNull("estimatedLiters") ? null : obj.optDouble("estimatedLiters");
            Double estAmount = obj.isNull("estimatedAmount") ? null : obj.optDouble("estimatedAmount");
            Long priceAt = obj.isNull("priceAt") ? null : obj.optLong("priceAt");
            List<String> stations = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("stationCandidates");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    stations.add(arr.getString(i));
                }
            }
            String intervalId = obj.isNull("intervalId") ? null : obj.optString("intervalId");
            Long startAt = obj.isNull("startAt") ? null : obj.optLong("startAt");
            Long endAt = obj.isNull("endAt") ? null : obj.optLong("endAt");
            Double increasePercent = obj.isNull("increasePercent") ? null : obj.optDouble("increasePercent");
            String correctedPlace = obj.isNull("correctedPlace") ? null : obj.optString("correctedPlace");
            Double correctedLiters = obj.isNull("correctedLiters") ? null : obj.optDouble("correctedLiters");
            Double correctedAmount = obj.isNull("correctedAmount") ? null : obj.optDouble("correctedAmount");

            return new FuelCandidateDetailResponse(
                    true, status, id, candidateStatus, revision, capacityVerified,
                    estLiters, estAmount, priceAt, stations, intervalId,
                    startAt, endAt, increasePercent, correctedPlace, correctedLiters, correctedAmount, null
            );
        } catch (Exception e) {
            return new FuelCandidateDetailResponse(true, status, fallbackId, "CANDIDATE", 1L, false, null, null, null, Collections.emptyList(), null, null, null, null, null, null, null, null);
        }
    }

    // =========================================================================
    // 6-2. [M11 / M12 정비 계획 및 이력 API (MobileMaintenanceController 연동)]
    // =========================================================================

    public static final class MaintenanceEventItem {
        public final String id;
        public final String itemId;
        public final String serviceDate;
        public final Double odometerKm;
        public final String status;
        public final boolean shared;
        public final boolean detailsShared;
        public final long cost;
        public final String currency;
        @Nullable public final String memo;
        @Nullable public final String shop;
        @Nullable public final String receiptUrl;
        public final long revision;

        public MaintenanceEventItem(
                String id, String itemId, String serviceDate, Double odometerKm,
                String status, boolean shared, boolean detailsShared,
                long cost, String currency, @Nullable String memo, @Nullable String shop, @Nullable String receiptUrl,
                long revision
        ) {
            this.id = id;
            this.itemId = itemId;
            this.serviceDate = serviceDate;
            this.odometerKm = odometerKm;
            this.status = status;
            this.shared = shared;
            this.detailsShared = detailsShared;
            this.cost = cost;
            this.currency = currency;
            this.memo = memo;
            this.shop = shop;
            this.receiptUrl = receiptUrl;
            this.revision = revision;
        }

        public MaintenanceEventItem(
                String id, String itemId, String serviceDate, Double odometerKm,
                String status, boolean shared, boolean detailsShared,
                long cost, String currency, @Nullable String memo, @Nullable String shop, @Nullable String receiptUrl
        ) {
            this(id, itemId, serviceDate, odometerKm, status, shared, detailsShared, cost, currency, memo, shop, receiptUrl, 1L);
        }
    }

    public static final class MaintenancePlanItem {
        public final String id;
        public final String name;
        public final boolean shared;
        public final boolean active;
        @Nullable public final Double intervalKm;
        @Nullable public final Integer intervalMonths;
        public final long revision;
        @Nullable public final String lastServiceDate;
        @Nullable public final Double lastServiceKm;
        @Nullable public final String nextDueDate;
        @Nullable public final Double nextDueKm;
        @Nullable public final String status;
        @Nullable public final String riskLevel;

        public MaintenancePlanItem(
                String id, String name, boolean shared, boolean active,
                @Nullable Double intervalKm, @Nullable Integer intervalMonths, long revision,
                @Nullable String lastServiceDate, @Nullable Double lastServiceKm,
                @Nullable String nextDueDate, @Nullable Double nextDueKm,
                @Nullable String status, @Nullable String riskLevel
        ) {
            this.id = id;
            this.name = name;
            this.shared = shared;
            this.active = active;
            this.intervalKm = intervalKm;
            this.intervalMonths = intervalMonths;
            this.revision = revision;
            this.lastServiceDate = lastServiceDate;
            this.lastServiceKm = lastServiceKm;
            this.nextDueDate = nextDueDate;
            this.nextDueKm = nextDueKm;
            this.status = status;
            this.riskLevel = riskLevel;
        }

        public MaintenancePlanItem(
                String id, String name, boolean shared, boolean active,
                @Nullable Double intervalKm, @Nullable Integer intervalMonths, long revision
        ) {
            this(id, name, shared, active, intervalKm, intervalMonths, revision, null, null, null, null, null, null);
        }

        /**
         * 다음 예정일 기준 D-Day 텍스트 계산 (예: "D-30", "D-DAY", "D+5")
         */
        @NonNull
        public String getDDayText() {
            if (nextDueDate == null || nextDueDate.trim().isEmpty()) {
                return "-";
            }
            try {
                java.time.LocalDate today = java.time.LocalDate.now();
                java.time.LocalDate target = java.time.LocalDate.parse(nextDueDate.trim());
                long days = java.time.temporal.ChronoUnit.DAYS.between(today, target);
                if (days == 0) {
                    return "D-DAY";
                } else if (days > 0) {
                    return "D-" + days;
                } else {
                    return "D+" + Math.abs(days) + " (기한초과)";
                }
            } catch (Exception e) {
                return nextDueDate;
            }
        }

        /**
         * 남은 거리 텍스트 계산
         */
        @NonNull
        public String getRemainingDistanceText(@Nullable Double currentOdometerKm) {
            if (nextDueKm == null) {
                return "-";
            }
            if (currentOdometerKm == null || currentOdometerKm <= 0.0) {
                return String.format(Locale.KOREA, "%,d km 예정", Math.round(nextDueKm));
            }
            double diff = nextDueKm - currentOdometerKm;
            if (diff >= 0) {
                return String.format(Locale.KOREA, "%,d km 남음", Math.round(diff));
            } else {
                return String.format(Locale.KOREA, "%,d km 초과", Math.round(Math.abs(diff)));
            }
        }

        /**
         * 설정 주기 포맷팅 (예: "10,000 km / 12개월마다")
         */
        @NonNull
        public String getIntervalText() {
            if (intervalKm != null && intervalMonths != null) {
                return String.format(Locale.KOREA, "%,d km / %d개월 주기", Math.round(intervalKm), intervalMonths);
            } else if (intervalKm != null) {
                return String.format(Locale.KOREA, "%,d km 주기", Math.round(intervalKm));
            } else if (intervalMonths != null) {
                return intervalMonths + "개월 주기";
            } else {
                return "주기 미설정";
            }
        }

        /**
         * 위험도 한글 라벨
         */
        @NonNull
        public String getRiskLabel() {
            if ("DANGER".equalsIgnoreCase(riskLevel)) {
                return "교체 시급";
            } else if ("OVERDUE".equalsIgnoreCase(riskLevel)) {
                return "교체 권장";
            } else if ("NORMAL".equalsIgnoreCase(riskLevel)) {
                return "정상";
            } else {
                return "점검 필요";
            }
        }

        /**
         * 게이지 진행률 (0 ~ 100 %)
         */
        public int getProgressPercent(@Nullable Double currentOdometerKm) {
            if ("DANGER".equalsIgnoreCase(riskLevel)) {
                return 100;
            } else if ("OVERDUE".equalsIgnoreCase(riskLevel)) {
                return 95;
            }
            if (intervalKm != null && intervalKm > 0 && nextDueKm != null && currentOdometerKm != null) {
                double remaining = nextDueKm - currentOdometerKm;
                if (remaining <= 0) return 100;
                double used = intervalKm - remaining;
                if (used <= 0) return 10;
                int percent = (int) Math.round((used / intervalKm) * 100.0);
                return Math.max(5, Math.min(100, percent));
            }
            return 50;
        }
    }

    /**
     * GET /mobile/vehicles/{vehicleId}/maintenance-events — 차량 정비 이력 목록 조회
     */
    @NonNull
    public List<MaintenanceEventItem> fetchMaintenanceEvents(@NonNull String vehicleId) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return Collections.emptyList();
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + vehicleId + "/maintenance-events", "GET", token);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject root = new JSONObject(body);
                JSONArray arr = root.optJSONArray("items");
                List<MaintenanceEventItem> list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        String id = obj.optString("id", "");
                        String itemId = obj.optString("itemId", "");
                        String serviceDate = obj.optString("serviceDate", "");
                        Double odo = obj.isNull("odometerKm") ? null : obj.optDouble("odometerKm");
                        String eventStatus = obj.optString("status", "CONFIRMED");
                        boolean shared = obj.optBoolean("shared", true);
                        boolean detailsShared = obj.optBoolean("detailsShared", false);
                        long cost = 0L;
                        String currency = "KRW";
                        String memo = null;
                        String shop = null;
                        String receiptUrl = null;
                        JSONObject details = obj.optJSONObject("details");
                        if (details != null) {
                            cost = details.optLong("cost", 0L);
                            currency = details.optString("currency", "KRW");
                            memo = details.isNull("memo") ? null : details.optString("memo");
                            shop = details.isNull("shop") ? null : details.optString("shop");
                            receiptUrl = details.isNull("receiptUrl") ? null : details.optString("receiptUrl");
                        }
                        long revision = obj.optLong("revision", 1L);
                        list.add(new MaintenanceEventItem(
                                id, itemId, serviceDate, odo, eventStatus, shared, detailsShared,
                                cost, currency, memo, shop, receiptUrl, revision
                        ));
                    }
                }
                SafeLogger.i("GET /mobile/vehicles/" + vehicleId + "/maintenance-events 성공: " + list.size() + "건 수신");
                return list;
            }
        } catch (Exception e) {
            SafeLogger.w("GET maintenance-events 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return Collections.emptyList();
    }

    /**
     * GET /mobile/vehicles/{vehicleId}/maintenance-plans — 차량 정비 계획 목록 조회
     */
    @NonNull
    public List<MaintenancePlanItem> fetchMaintenancePlans(@NonNull String vehicleId) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return Collections.emptyList();
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + vehicleId + "/maintenance-plans", "GET", token);
            int status = conn.getResponseCode();
            String body = readBody(conn, status >= 400);
            if (status >= 200 && status < 300) {
                JSONObject root = new JSONObject(body);
                JSONArray arr = root.optJSONArray("items");
                List<MaintenancePlanItem> list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        String id = obj.optString("id", "");
                        String name = obj.optString("name", "");
                        boolean shared = obj.optBoolean("shared", true);
                        boolean active = obj.optBoolean("active", true);
                        Double intervalKm = obj.isNull("intervalKm") ? null : obj.optDouble("intervalKm");
                        Integer intervalMonths = obj.isNull("intervalMonths") ? null : obj.optInt("intervalMonths");
                        long rev = obj.optLong("revision", 1L);
                        String lastServiceDate = obj.isNull("lastServiceDate") ? null : obj.optString("lastServiceDate", null);
                        Double lastServiceKm = obj.isNull("lastServiceKm") ? null : obj.optDouble("lastServiceKm");
                        String nextDueDate = obj.isNull("nextDueDate") ? null : obj.optString("nextDueDate", null);
                        Double nextDueKm = obj.isNull("nextDueKm") ? null : obj.optDouble("nextDueKm");
                        String statusStr = obj.isNull("status") ? null : obj.optString("status", null);
                        String riskLevel = obj.isNull("riskLevel") ? null : obj.optString("riskLevel", null);
                        list.add(new MaintenancePlanItem(
                                id, name, shared, active, intervalKm, intervalMonths, rev,
                                lastServiceDate, lastServiceKm, nextDueDate, nextDueKm, statusStr, riskLevel
                        ));
                    }
                }
                return list;
            }
        } catch (Exception e) {
            SafeLogger.w("GET maintenance-plans 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return Collections.emptyList();
    }

    /**
     * PUT /mobile/vehicles/{vehicleId}/maintenance-plans/{itemId} — 정비 주기 설정 / 수정
     */
    public boolean putMaintenancePlan(
            @NonNull String vehicleId,
            @NonNull String itemId,
            long revision,
            @NonNull String name,
            @Nullable Double intervalKm,
            @Nullable Integer intervalMonths
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + vehicleId + "/maintenance-plans/" + itemId, "PUT", token);
            if (revision > 0) {
                conn.setRequestProperty("If-Match", "\"" + revision + "\"");
            }
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("name", name);
            payload.put("shared", true);
            payload.put("basis", "USER");
            if (intervalKm != null && intervalKm > 0) {
                payload.put("intervalKm", intervalKm);
            }
            if (intervalMonths != null && intervalMonths > 0) {
                payload.put("intervalMonths", intervalMonths);
            }
            writeBody(conn, payload.toString());
            int status = conn.getResponseCode();
            String respBody = readBody(conn, status >= 400);
            SafeLogger.i("PUT maintenance-plans status=" + status + ", body=" + respBody);

            if (status == 412) {
                // If-Match revision mismatch: 백엔드의 최신 revision을 조회하여 1회 재시도
                List<MaintenancePlanItem> freshPlans = fetchMaintenancePlans(vehicleId);
                for (MaintenancePlanItem p : freshPlans) {
                    if (itemId.equals(p.id) && p.revision != revision) {
                        return putMaintenancePlan(vehicleId, itemId, p.revision, name, intervalKm, intervalMonths);
                    }
                }
            }
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("PUT maintenance-plans 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * POST /mobile/vehicles/{vehicleId}/maintenance-plans/{itemId}/complete — 사용자 정비 완료 확인 및 다음 회차 생성
     */
    public boolean completeMaintenancePlan(
            @NonNull String vehicleId,
            @NonNull String itemId,
            long revision,
            @NonNull String serviceDate,
            @Nullable Double odometerKm
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + vehicleId + "/maintenance-plans/" + itemId + "/complete", "POST", token);
            if (revision > 0) {
                conn.setRequestProperty("If-Match", "\"" + revision + "\"");
            }
            conn.setRequestProperty("Idempotency-Key", UUID.randomUUID().toString());
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("confirmed", true);
            payload.put("serviceDate", serviceDate);
            if (odometerKm != null && odometerKm >= 0.0) {
                payload.put("odometerKm", odometerKm);
            }
            writeBody(conn, payload.toString());
            int status = conn.getResponseCode();
            String respBody = readBody(conn, status >= 400);
            SafeLogger.i("POST complete maintenance-plans status=" + status + ", body=" + respBody);
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("POST complete maintenance-plans 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * DELETE /mobile/vehicles/{vehicleId}/maintenance-plans/{itemId} — 정비 주기 비활성화
     */
    public boolean deleteMaintenancePlan(
            @NonNull String vehicleId,
            @NonNull String itemId,
            long revision
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + vehicleId + "/maintenance-plans/" + itemId, "DELETE", token);
            if (revision > 0) {
                conn.setRequestProperty("If-Match", "\"" + revision + "\"");
            }
            int status = conn.getResponseCode();
            String respBody = readBody(conn, status >= 400);
            SafeLogger.i("DELETE maintenance-plans status=" + status + ", body=" + respBody);
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("DELETE maintenance-plans 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 특정 정비 항목(예: 엔진오일, 와이퍼 등)에 해당하는 정비 계획 UUID를 반환하거나 없으면 생성한다.
     */
    @NonNull
    public String ensureMaintenancePlanId(@NonNull String vehicleId, @NonNull String itemName) {
        List<MaintenancePlanItem> plans = fetchMaintenancePlans(vehicleId);
        for (MaintenancePlanItem p : plans) {
            if (p.name.equalsIgnoreCase(itemName) && p.active) {
                return p.id;
            }
        }
        String newPlanId = UUID.randomUUID().toString();
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return newPlanId;
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + vehicleId + "/maintenance-plans/" + newPlanId, "PUT", token);
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("name", itemName);
            payload.put("shared", true);
            payload.put("basis", "USER");
            payload.put("intervalKm", 10000.0);
            payload.put("intervalMonths", 12);
            writeBody(conn, payload.toString());
            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                SafeLogger.i("PUT maintenance-plans 성공: " + itemName + " (id=" + newPlanId + ")");
            }
        } catch (Exception e) {
            SafeLogger.w("PUT maintenance-plans 예외: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return newPlanId;
    }

    /**
     * POST /mobile/vehicles/{vehicleId}/maintenance-events — 차량 정비 기록 신규 등록
     */
    public boolean createMaintenanceEvent(
            @NonNull String vehicleId,
            @NonNull String itemName,
            @NonNull String serviceDate,
            @Nullable Double odometerKm,
            long costWon,
            @Nullable String shop,
            @Nullable String memo
    ) {
        return createMaintenanceEvent(vehicleId, itemName, serviceDate, odometerKm, costWon, shop, memo, true, true);
    }

    public boolean createMaintenanceEvent(
            @NonNull String vehicleId,
            @NonNull String itemName,
            @NonNull String serviceDate,
            @Nullable Double odometerKm,
            long costWon,
            @Nullable String shop,
            @Nullable String memo,
            boolean shared,
            boolean detailsShared
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return false;
        }
        String itemId = ensureMaintenancePlanId(vehicleId, itemName);
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + vehicleId + "/maintenance-events", "POST", token);
            conn.setRequestProperty("Idempotency-Key", UUID.randomUUID().toString());
            conn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("itemId", itemId);
            payload.put("serviceDate", serviceDate);
            if (odometerKm != null && odometerKm >= 0.0) {
                payload.put("odometerKm", odometerKm);
            }
            payload.put("status", "CONFIRMED");
            payload.put("shared", shared);
            payload.put("detailsShared", detailsShared);
            payload.put("cost", (double) costWon);
            payload.put("currency", "KRW");
            if (shop != null && !shop.trim().isEmpty()) {
                payload.put("shop", shop.trim());
            }
            if (memo != null && !memo.trim().isEmpty()) {
                payload.put("memo", memo.trim());
            }
            writeBody(conn, payload.toString());
            int status = conn.getResponseCode();
            String respBody = readBody(conn, status >= 400);
            SafeLogger.i("POST maintenance-events status=" + status + ", body=" + respBody);
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("POST maintenance-events 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * PATCH /mobile/vehicles/{vehicleId}/maintenance-events/{eventId} — 정비 기록 수정 (M11)
     */
    public boolean patchMaintenanceEvent(
            @NonNull String vehicleId,
            @NonNull String eventId,
            long revision,
            @NonNull String serviceDate,
            @Nullable Double odometerKm,
            long costWon,
            @Nullable String shop,
            @Nullable String memo,
            boolean shared,
            boolean detailsShared
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + vehicleId + "/maintenance-events/" + eventId, "PATCH", token);
            if (revision > 0) {
                conn.setRequestProperty("If-Match", "\"" + revision + "\"");
            }
            conn.setDoOutput(true);
            JSONObject patch = new JSONObject();
            patch.put("serviceDate", serviceDate);
            if (odometerKm != null && odometerKm >= 0.0) {
                patch.put("odometerKm", odometerKm);
            }
            patch.put("cost", (double) costWon);
            patch.put("currency", "KRW");
            patch.put("shop", shop != null ? shop.trim() : "");
            patch.put("memo", memo != null ? memo.trim() : "");
            patch.put("shared", shared);
            patch.put("detailsShared", detailsShared);
            writeBody(conn, patch.toString());
            int status = conn.getResponseCode();
            String respBody = readBody(conn, status >= 400);
            SafeLogger.i("PATCH maintenance-events status=" + status + ", body=" + respBody);
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("PATCH maintenance-events 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * DELETE /mobile/vehicles/{vehicleId}/maintenance-events/{eventId} — 정비 기록 삭제 (M11)
     */
    public boolean deleteMaintenanceEvent(
            @NonNull String vehicleId,
            @NonNull String eventId,
            long revision
    ) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + vehicleId + "/maintenance-events/" + eventId, "DELETE", token);
            if (revision > 0) {
                conn.setRequestProperty("If-Match", "\"" + revision + "\"");
            }
            int status = conn.getResponseCode();
            String respBody = readBody(conn, status >= 400);
            SafeLogger.i("DELETE maintenance-events status=" + status + ", body=" + respBody);
            return status >= 200 && status < 300;
        } catch (Exception e) {
            SafeLogger.w("DELETE maintenance-events 예외: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // =========================================================================
    // 7. [고수준 종단간(End-to-End) API 오케스트레이션 파이프라인]
    //    - 차량 등록·페어링 확정 파이프라인 (M02 / C01 QR 인식 연동)
    //    - 운행 사용자 인증·C03 동의·검증 파이프라인 (M04)
    //    - 운행 기록·C04 요약 업로드·M06 모바일 확인함 전달 파이프라인 (M03 / M04)
    // =========================================================================

    public static final class EndToEndVehicleSyncResult {
        public final boolean success;
        @Nullable
        public final String installationId;
        @Nullable
        public final String installationSecret;
        @Nullable
        public final String vehicleId;
        @Nullable
        public final String vehicleName;
        @Nullable
        public final String scopedToken;
        @Nullable
        public final String intervalId;
        @Nullable
        public final String consentId;
        @Nullable
        public final String candidateId;

        public EndToEndVehicleSyncResult(
                boolean success,
                @Nullable String installationId,
                @Nullable String installationSecret,
                @Nullable String vehicleId,
                @Nullable String vehicleName,
                @Nullable String scopedToken,
                @Nullable String intervalId,
                @Nullable String consentId,
                @Nullable String candidateId
        ) {
            this.success = success;
            this.installationId = installationId;
            this.installationSecret = installationSecret;
            this.vehicleId = vehicleId;
            this.vehicleName = vehicleName;
            this.scopedToken = scopedToken;
            this.intervalId = intervalId;
            this.consentId = consentId;
            this.candidateId = candidateId;
        }
    }

    public static final class QrIntentCodeResult {
        public final boolean success;
        @Nullable
        public final String requestId;
        @Nullable
        public final String confirmationCode;
        @Nullable
        public final String status;
        @Nullable
        public final String vehicleName;
        @Nullable
        public final String errorMessage;

        public QrIntentCodeResult(
                boolean success,
                @Nullable String requestId,
                @Nullable String confirmationCode,
                @Nullable String status,
                @Nullable String vehicleName,
                @Nullable String errorMessage
        ) {
            this.success = success;
            this.requestId = requestId;
            this.confirmationCode = confirmationCode;
            this.status = status;
            this.vehicleName = vehicleName;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * [1단계: QR 스캔 직후 6자리 비교 확인 코드(confirmation_code) 발급]
     * POST /pairing-requests/preview -> POST /pairing-requests/{id}/intent를 호출하여
     * 차량 디스플레이(C01)와 휴대폰 화면에서 대조할 6자리 비교 코드(예: "583204")를 받아온다.
     */
    @NonNull
    public synchronized QrIntentCodeResult submitQrIntentForConfirmationCode(
            @NonNull UserCacheManager cacheManager,
            @Nullable String scannedQrPayload,
            @NonNull String preferredVehicleName
    ) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null || session.getAccessToken().startsWith("local_jwt_")) {
            return new QrIntentCodeResult(false, null, null, "unauthorized", preferredVehicleName, "로그인이 필요합니다.");
        }
        String targetVehicleName = preferredVehicleName != null ? preferredVehicleName.trim() : "";

        String raw = (scannedQrPayload != null && !scannedQrPayload.trim().isEmpty())
                ? scannedQrPayload.trim()
                : issueInAppVehiclePairingQrJson(cacheManager);

        try {
            if (raw.startsWith("{")) {
                JSONObject rootJson = new JSONObject(raw);
                JSONObject qrJson = rootJson.has("qr") && rootJson.optJSONObject("qr") != null
                        ? rootJson.optJSONObject("qr")
                        : rootJson;

                if (rootJson.has("vehicle_name") && !rootJson.optString("vehicle_name").isEmpty()) {
                    targetVehicleName = rootJson.optString("vehicle_name");
                } else if (qrJson.has("vehicle_name") && !qrJson.optString("vehicle_name").isEmpty()) {
                    targetVehicleName = qrJson.optString("vehicle_name");
                }

                String type = qrJson.optString("type", rootJson.optString("type", ""));
                String userId = session.getUserId();
                String existingVehicleId = cacheManager.getRegisteredVehicleUuid(userId);
                if (existingVehicleId == null || existingVehicleId.isEmpty()) {
                    List<BackendVehicleItem> existingVehicles = fetchMobileVehicles();
                    if (!existingVehicles.isEmpty()) {
                        BackendVehicleItem first = existingVehicles.get(0);
                        existingVehicleId = first.vehicleId != null ? first.vehicleId : first.id;
                        targetVehicleName = first.getDisplayName();
                        cacheManager.setRegisteredVehicleUuid(userId, existingVehicleId);
                        cacheManager.setRegisteredVehicleForUser(userId, targetVehicleName);
                    }
                }

                // =========================================================================
                // Case 1: [C02 / C03 운행 사용자 승인 QR] {"type":"fuelog.trip-user", "challengeId":"...", "proof":"..."}
                // 차량 디스플레이에서 띄운 QR로, 모바일이 POST /mobile/trip-user/approve를 호출해야
                // 차량의 POST /trip-user/session 상태가 "PENDING" -> "ACTIVE"로 전환된다.
                // =========================================================================
                String challengeId = qrJson.optString("challengeId", rootJson.optString("challengeId", ""));
                String proof = qrJson.optString("proof", rootJson.optString("proof", ""));

                if (!challengeId.isEmpty() && !proof.isEmpty() && ("fuelog.trip-user".equals(type) || type.contains("trip-user"))) {
                    if (existingVehicleId == null || existingVehicleId.isEmpty()) {
                        // 차량이 아직 없다면 기본 차량 생성 후 승인
                        EndToEndVehicleSyncResult reg = registerAndConfirmVehicleEndToEnd(cacheManager, targetVehicleName);
                        existingVehicleId = reg.vehicleId != null ? reg.vehicleId : cacheManager.getRegisteredVehicleUuid(userId);
                    }
                    if (existingVehicleId != null && !existingVehicleId.isEmpty()) {
                        boolean approved = approveTripUserQr(challengeId, proof, existingVehicleId);
                        SafeLogger.i("C02/C03 trip-user QR 승인 결과: " + approved + " (challengeId=" + challengeId + ")");
                        if (approved) {
                            return new QrIntentCodeResult(
                                    true,
                                    challengeId,
                                    "승인 완료",
                                    "ACTIVE",
                                    targetVehicleName,
                                    null
                            );
                        } else {
                            return new QrIntentCodeResult(
                                    false,
                                    challengeId,
                                    null,
                                    "APPROVAL_FAILED",
                                    targetVehicleName,
                                    "운행 사용자 승인 요청 실패 (차량 멤버십 또는 만료 확인)"
                            );
                        }
                    }
                }

                // =========================================================================
                // Case 2: [C01 신규 연결 QR] {"type":"fuelog.vehicle-registration", "requestId":"...", "nonce":"..."}
                // 모바일이 POST /mobile/connection/approve를 호출하면 즉시 "COMPLETED" 상태로 완료된다.
                // =========================================================================
                String connReqId = qrJson.optString("requestId", rootJson.optString("requestId", ""));
                String nonce = qrJson.optString("nonce", rootJson.optString("nonce", ""));

                if (!connReqId.isEmpty() && !nonce.isEmpty() && ("fuelog.vehicle-registration".equals(type) || type.contains("vehicle-registration"))) {
                    inspectMobileConnectionQr(connReqId, nonce);
                    if (existingVehicleId == null || existingVehicleId.isEmpty()) {
                        EndToEndVehicleSyncResult reg = registerAndConfirmVehicleEndToEnd(cacheManager, targetVehicleName);
                        existingVehicleId = reg.vehicleId != null ? reg.vehicleId : cacheManager.getRegisteredVehicleUuid(userId);
                    }
                    if (existingVehicleId != null && !existingVehicleId.isEmpty()) {
                        boolean approved = approveMobileConnection(connReqId, nonce, existingVehicleId, "appr-" + UUID.randomUUID());
                        SafeLogger.i("C01 vehicle-registration QR 연결 승인 결과: " + approved + " (reqId=" + connReqId + ")");
                        if (approved) {
                            cacheManager.setRegisteredVehicleForUser(userId, targetVehicleName);
                            return new QrIntentCodeResult(
                                    true,
                                    connReqId,
                                    "연결 완료",
                                    "COMPLETED",
                                    targetVehicleName,
                                    null
                            );
                        } else {
                            return new QrIntentCodeResult(
                                    false,
                                    connReqId,
                                    null,
                                    "APPROVAL_FAILED",
                                    targetVehicleName,
                                    "차량 연결 승인 실패 (차량 소유자/관리자 권한 확인)"
                            );
                        }
                    }
                }

                // =========================================================================
                // Case 3: [C01 페어링 QR] {"type":"fuelog.vehicle_pairing", "request_id":"...", "proof":"..."}
                // 모바일이 POST /pairing-requests/{id}/intent 호출 -> 6자리 confirmation_code 발급
                // 상태가 "pending" -> "awaiting_vehicle_confirmation"으로 변경됨.
                // =========================================================================
                String reqId = qrJson.optString("request_id", qrJson.optString("requestId", rootJson.optString("request_id", rootJson.optString("requestId", ""))));
                if (!reqId.isEmpty() && !proof.isEmpty()) {
                    previewPairingQr(reqId, proof);
                    PairingStatusResponse intentResp = submitPairingIntent(
                            reqId,
                            proof,
                            existingVehicleId,
                            (existingVehicleId != null && !existingVehicleId.isEmpty()) ? null : (targetVehicleName.isEmpty() ? null : targetVehicleName)
                    );
                    if (intentResp.isSuccess() && intentResp.confirmationCode != null) {
                        String resolvedName = intentResp.selectedVehicleName != null
                                ? intentResp.selectedVehicleName
                                : targetVehicleName;
                        return new QrIntentCodeResult(
                                true,
                                reqId,
                                intentResp.confirmationCode,
                                intentResp.status,
                                resolvedName,
                                null
                        );
                    }
                    return new QrIntentCodeResult(
                            false,
                            reqId,
                            null,
                            intentResp.status,
                            targetVehicleName,
                            intentResp.errorMessage != null ? intentResp.errorMessage : "QR 확인 코드 발급 실패"
                    );
                }
            }
        } catch (Exception e) {
            SafeLogger.w("submitQrIntentForConfirmationCode 예외: " + e.getMessage());
            return new QrIntentCodeResult(false, null, null, "error", targetVehicleName, e.getMessage());
        }
        return new QrIntentCodeResult(false, null, null, "invalid_qr", targetVehicleName, "유효한 C01 페어링 QR 코드가 아닙니다.");
    }

    /**
     * [2단계: 6자리 확인 코드 대조 후 차량 연결 최종 확정]
     * 모바일 Supabase Bearer JWT로 POST /pairing-requests/{id}/confirm을 호출하여
     * 6자리 확인 코드를 서버로 전송하고 즉시 vehicle_id를 발급받아 차량 연동을 확정한다.
     * (차량 화면에서 먼저 승인한 경우를 위한 상태 조회 및 설치 헤더 호출도 폴백으로 지원)
     */
    @NonNull
    public synchronized EndToEndVehicleSyncResult confirmQrPairingWithCode(
            @NonNull UserCacheManager cacheManager,
            @NonNull String requestId,
            @NonNull String confirmationCode,
            @NonNull String fallbackVehicleName
    ) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null || session.getAccessToken().startsWith("local_jwt_")) {
            return new EndToEndVehicleSyncResult(false, null, null, null, fallbackVehicleName, null, null, null, null);
        }
        String userId = session.getUserId();

        // 0) 이미 승인 완료된 C02/C03 또는 C01 direct connection인 경우 즉시 동기화 완료
        if ("승인 완료".equals(confirmationCode) || "연결 완료".equals(confirmationCode) || "ACTIVE".equalsIgnoreCase(confirmationCode) || "COMPLETED".equalsIgnoreCase(confirmationCode)) {
            String resolvedVehId = cacheManager.getRegisteredVehicleUuid(userId);
            String resolvedName = cacheManager.getVisibleCachedVehicleName();
            if (resolvedName == null || resolvedName.isEmpty()) resolvedName = fallbackVehicleName;
            return new EndToEndVehicleSyncResult(
                    true,
                    cacheManager.getInstallationId(userId),
                    cacheManager.getInstallationSecret(userId),
                    resolvedVehId,
                    resolvedName,
                    cacheManager.getActiveScopedToken(),
                    cacheManager.getActiveIntervalId(),
                    cacheManager.getActiveConsentId(),
                    cacheManager.getActiveCandidateId()
            );
        }

        // 1) [최신 백엔드 스펙] 모바일 Supabase Bearer JWT로 POST /pairing-requests/{id}/confirm 직접 전송
        PairingStatusResponse confirmed = confirmPairingRequestWithMobileJwt(requestId, confirmationCode);
        if (confirmed.isSuccess() && confirmed.vehicleId != null && !confirmed.vehicleId.isEmpty()) {
            String resolvedName = confirmed.selectedVehicleName != null && !confirmed.selectedVehicleName.isEmpty()
                    ? confirmed.selectedVehicleName
                    : fallbackVehicleName;
            cacheManager.setRegisteredVehicleUuid(userId, confirmed.vehicleId);
            cacheManager.setRegisteredVehicleForUser(userId, resolvedName);
            SafeLogger.i("모바일 JWT 페어링 승인(confirm) 성공: vehId=" + confirmed.vehicleId + ", name=" + resolvedName);
            return new EndToEndVehicleSyncResult(
                    true,
                    cacheManager.getInstallationId(userId),
                    cacheManager.getInstallationSecret(userId),
                    confirmed.vehicleId,
                    resolvedName,
                    cacheManager.getActiveScopedToken(),
                    cacheManager.getActiveIntervalId(),
                    cacheManager.getActiveConsentId(),
                    cacheManager.getActiveCandidateId()
            );
        }

        // 2) 차량 화면(C01)에서 이미 confirm을 눌러 completed 상태가 되었는지 확인 (폴링 조회)
        PairingStatusResponse statusResp = getPairingRequestStatus(requestId);
        for (int i = 0; i < 3 && !"completed".equals(statusResp.status); i++) {
            if ("completed".equals(statusResp.status)) break;
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {}
            statusResp = getPairingRequestStatus(requestId);
        }

        if ("completed".equals(statusResp.status) && statusResp.vehicleId != null && !statusResp.vehicleId.isEmpty()) {
            String resolvedName = statusResp.selectedVehicleName != null && !statusResp.selectedVehicleName.isEmpty()
                    ? statusResp.selectedVehicleName
                    : fallbackVehicleName;
            cacheManager.setRegisteredVehicleUuid(userId, statusResp.vehicleId);
            cacheManager.setRegisteredVehicleForUser(userId, resolvedName);
            SafeLogger.i("차량 화면 confirm 완료 감지: vehId=" + statusResp.vehicleId + ", name=" + resolvedName);
            return new EndToEndVehicleSyncResult(
                    true,
                    cacheManager.getInstallationId(userId),
                    cacheManager.getInstallationSecret(userId),
                    statusResp.vehicleId,
                    resolvedName,
                    cacheManager.getActiveScopedToken(),
                    cacheManager.getActiveIntervalId(),
                    cacheManager.getActiveConsentId(),
                    cacheManager.getActiveCandidateId()
            );
        }

        // 3) 로컬에 해당 C01 설치 자격증명이 있는 경우(차량 단말 시뮬레이션 등) 차량 헤더로 confirm 재시도
        String instId = cacheManager.getInstallationId(userId);
        String instSecret = cacheManager.getInstallationSecret(userId);
        if (instId != null && instSecret != null) {
            PairingStatusResponse vehicleConfirmed = confirmPairingRequest(instId, instSecret, requestId, confirmationCode);
            if (vehicleConfirmed.isSuccess() && vehicleConfirmed.vehicleId != null && !vehicleConfirmed.vehicleId.isEmpty()) {
                String resolvedName = vehicleConfirmed.selectedVehicleName != null && !vehicleConfirmed.selectedVehicleName.isEmpty()
                        ? vehicleConfirmed.selectedVehicleName
                        : fallbackVehicleName;
                cacheManager.setRegisteredVehicleUuid(userId, vehicleConfirmed.vehicleId);
                cacheManager.setRegisteredVehicleForUser(userId, resolvedName);
                return new EndToEndVehicleSyncResult(
                        true,
                        instId,
                        instSecret,
                        vehicleConfirmed.vehicleId,
                        resolvedName,
                        cacheManager.getActiveScopedToken(),
                        cacheManager.getActiveIntervalId(),
                        cacheManager.getActiveConsentId(),
                        cacheManager.getActiveCandidateId()
                );
            }
        }

        return new EndToEndVehicleSyncResult(
                false, instId, instSecret, statusResp.vehicleId, fallbackVehicleName, null, null, null, null
        );
    }

    /**
     * [앱 내(In-App) 차량 QR 코드 발급·인식 준비]
     * 외부 카메라 액티비티로 이탈하지 않고 앱 내에서 즉시 C01 QR 코드를 발급·인식할 수 있도록
     * 미페어링 설치(Installation)를 확보하고 POST /pairing-requests로 유효한 QR JSON 페이로드를 반환한다.
     */
    @NonNull
    public synchronized String issueInAppVehiclePairingQrJson(@NonNull UserCacheManager cacheManager) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null || session.getAccessToken().startsWith("local_jwt_")) {
            return "";
        }
        String userId = session.getUserId();
        String instId = cacheManager.getInstallationId(userId);
        String instSecret = cacheManager.getInstallationSecret(userId);

        boolean needNewInstallation = (instId == null || instSecret == null);
        if (!needNewInstallation) {
            InstallationMeResponse me = fetchInstallationMe(instId, instSecret);
            if (me == null || me.paired) {
                // 이미 다른 차량에 페어링된 설치이거나 만료된 경우 새 차량 QR 등록을 위해 신규 설치 발급
                needNewInstallation = true;
            }
        }

        if (needNewInstallation) {
            InstallationIssuedResponse issued = createInstallation();
            if (issued != null) {
                instId = issued.installationId;
                instSecret = issued.installationSecret;
                cacheManager.setInstallationCredentials(userId, instId, instSecret);
            }
        }

        if (instId != null && instSecret != null) {
            PairingQrIssuedResponse qrIssued = issuePairingRequest(
                    instId,
                    instSecret,
                    "inapp-qr-" + UUID.randomUUID()
            );
            if (qrIssued != null) {
                try {
                    JSONObject qrObj = new JSONObject();
                    qrObj.put("v", 1);
                    qrObj.put("type", "fuelog.vehicle_pairing");
                    qrObj.put("request_id", qrIssued.requestId);
                    qrObj.put("proof", qrIssued.proof);
                    return qrObj.toString();
                } catch (Exception ignored) {
                }
            }
        }
        return "";
    }

    /**
     * [M02 & 관리 탭 앱 내 QR 코드 인식 기반 차량 연동 파이프라인]
     * 앱 내에서 인식한 C01 QR JSON 페이로드({"v":1,"type":"fuelog.vehicle_pairing","request_id":"...","proof":"..."}
     * 또는 {"type":"fuelog.vehicle-registration","version":1,"requestId":"...","nonce":"..."})를 검증·승인하고,
     * 실제 백엔드 DB(vehicles 테이블)에 차량을 등록·연동한다.
     */
    @NonNull
    public synchronized EndToEndVehicleSyncResult recognizeQrAndLinkVehicle(
            @NonNull UserCacheManager cacheManager,
            @Nullable String scannedQrPayload,
            @NonNull String fallbackVehicleName
    ) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null || session.getAccessToken().startsWith("local_jwt_")) {
            return new EndToEndVehicleSyncResult(false, null, null, null, fallbackVehicleName, null, null, null, null);
        }
        String userId = session.getUserId();
        String targetVehicleName = fallbackVehicleName != null ? fallbackVehicleName.trim() : "";

        // 1) 스캔된 QR JSON 페이로드가 전달된 경우 파싱 후 백엔드 QR 검증 API 호출
        if (scannedQrPayload != null && !scannedQrPayload.trim().isEmpty()) {
            String raw = scannedQrPayload.trim();
            try {
                if (raw.startsWith("{")) {
                    JSONObject rootJson = new JSONObject(raw);
                    JSONObject qrJson = rootJson.has("qr") && rootJson.optJSONObject("qr") != null
                            ? rootJson.optJSONObject("qr")
                            : rootJson;

                    if (rootJson.has("vehicle_name") && !rootJson.optString("vehicle_name").isEmpty()) {
                        targetVehicleName = rootJson.optString("vehicle_name");
                    } else if (qrJson.has("vehicle_name") && !qrJson.optString("vehicle_name").isEmpty()) {
                        targetVehicleName = qrJson.optString("vehicle_name");
                    }
                    String reqId = qrJson.optString("request_id", qrJson.optString("requestId", rootJson.optString("request_id", rootJson.optString("requestId", ""))));
                    String proof = qrJson.optString("proof", rootJson.optString("proof", ""));
                    String nonce = qrJson.optString("nonce", rootJson.optString("nonce", ""));

                    String challengeId = qrJson.optString("challengeId", rootJson.optString("challengeId", ""));

                    if (!challengeId.isEmpty() && !proof.isEmpty()) {
                        String existingVehId = cacheManager.getRegisteredVehicleUuid(userId);
                        if (existingVehId == null || existingVehId.isEmpty()) {
                            EndToEndVehicleSyncResult reg = registerAndConfirmVehicleEndToEnd(cacheManager, targetVehicleName);
                            existingVehId = reg.vehicleId != null ? reg.vehicleId : cacheManager.getRegisteredVehicleUuid(userId);
                        }
                        if (existingVehId != null && !existingVehId.isEmpty()) {
                            boolean approved = approveTripUserQr(challengeId, proof, existingVehId);
                            SafeLogger.i("C02/C03 trip-user QR 승인: " + approved + " (challengeId=" + challengeId + ")");
                            if (approved) {
                                cacheManager.setRegisteredVehicleForUser(userId, targetVehicleName);
                                return new EndToEndVehicleSyncResult(
                                        true,
                                        cacheManager.getInstallationId(userId),
                                        cacheManager.getInstallationSecret(userId),
                                        existingVehId,
                                        targetVehicleName,
                                        cacheManager.getActiveScopedToken(),
                                        cacheManager.getActiveIntervalId(),
                                        cacheManager.getActiveConsentId(),
                                        cacheManager.getActiveCandidateId()
                                );
                            }
                        }
                    } else if (!reqId.isEmpty() && !proof.isEmpty()) {
                        previewPairingQr(reqId, proof);
                        String currentVehId = cacheManager.getRegisteredVehicleUuid(userId);
                        PairingStatusResponse intentResp = submitPairingIntent(
                                reqId,
                                proof,
                                currentVehId,
                                (currentVehId != null && !currentVehId.isEmpty()) ? null : (targetVehicleName.isEmpty() ? null : targetVehicleName)
                        );
                        getPairingRequestStatus(reqId);
                        String instId = cacheManager.getInstallationId(userId);
                        String instSecret = cacheManager.getInstallationSecret(userId);
                        if (intentResp.isSuccess() && intentResp.confirmationCode != null
                                && instId != null && instSecret != null) {
                            PairingStatusResponse confirmed = confirmPairingRequest(
                                    instId, instSecret, reqId, intentResp.confirmationCode
                            );
                            if (confirmed.isSuccess() && confirmed.vehicleId != null) {
                                String resolvedName = confirmed.selectedVehicleName != null
                                        ? confirmed.selectedVehicleName
                                        : targetVehicleName;
                                cacheManager.setRegisteredVehicleUuid(userId, confirmed.vehicleId);
                                cacheManager.setRegisteredVehicleForUser(userId, resolvedName);
                                return authenticateAndVerifyTripUserEndToEnd(cacheManager, resolvedName);
                            }
                        } else if (intentResp.isSuccess()) {
                            // 외부 차량 단말(C01)에서 발급한 QR에 대해 모바일 연결 의사(intent) 제출 성공
                            PairingStatusResponse statusResp = getPairingRequestStatus(reqId);
                            String resolvedName = statusResp.selectedVehicleName != null
                                    ? statusResp.selectedVehicleName
                                    : targetVehicleName;
                            if (statusResp.vehicleId != null && !statusResp.vehicleId.isEmpty()) {
                                cacheManager.setRegisteredVehicleUuid(userId, statusResp.vehicleId);
                            }
                            cacheManager.setRegisteredVehicleForUser(userId, resolvedName);
                            return new EndToEndVehicleSyncResult(
                                    true,
                                    cacheManager.getInstallationId(userId),
                                    cacheManager.getInstallationSecret(userId),
                                    statusResp.vehicleId,
                                    resolvedName,
                                    null, null, null, null
                            );
                        }
                    } else if (!reqId.isEmpty() && !nonce.isEmpty()) {
                        int rem = inspectMobileConnectionQr(reqId, nonce);
                        String existingVehId = cacheManager.getRegisteredVehicleUuid(userId);
                        if (rem >= 0 && existingVehId != null) {
                            approveMobileConnection(reqId, nonce, existingVehId, "approve-" + UUID.randomUUID());
                        }
                    }
                }
            } catch (Exception e) {
                SafeLogger.w("QR JSON 파싱 참고: " + e.getMessage());
            }
        }

        // 2) C01 QR 발급 -> 모바일 QR 스캔(preview/intent) -> C01 확인(confirm) -> C02/C03 운행 사용자 인증까지 수행
        return authenticateAndVerifyTripUserEndToEnd(cacheManager, targetVehicleName);
    }

    /**
     * [M02 차량 등록 & C01 QR 페어링 확정 종단간 파이프라인]
     * POST /installations -> POST /pairing-requests -> POST /pairing-requests/preview
     * -> POST /pairing-requests/{id}/intent -> GET /pairing-requests/{id}
     * -> POST /pairing-requests/{id}/confirm -> GET /installations/me
     */
    @NonNull
    public synchronized EndToEndVehicleSyncResult registerAndConfirmVehicleEndToEnd(
            @NonNull UserCacheManager cacheManager,
            @NonNull String vehicleDisplayName
    ) {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null || session.getAccessToken().startsWith("local_jwt_")) {
            return new EndToEndVehicleSyncResult(false, null, null, null, vehicleDisplayName, null, null, null, null);
        }
        String userId = session.getUserId();

        // 1) 이미 페어링 완료된 설치·차량이 있으면 GET /installations/me 및 GET /vehicle/home으로 서버 DB의 실제 차량 이름을 조회
        String instId = cacheManager.getInstallationId(userId);
        String instSecret = cacheManager.getInstallationSecret(userId);
        if (instId != null && instSecret != null) {
            InstallationMeResponse me = fetchInstallationMe(instId, instSecret);
            if (me != null && me.paired && me.vehicleId != null) {
                cacheManager.setRegisteredVehicleUuid(userId, me.vehicleId);
                VehicleHomeResponse home = fetchVehicleHome(instSecret, cacheManager.getActiveScopedToken());
                String serverVehicleName = (home.success && home.vehicleAlias != null && !home.vehicleAlias.trim().isEmpty())
                        ? home.vehicleAlias.trim()
                        : vehicleDisplayName;
                if (serverVehicleName != null && !serverVehicleName.trim().isEmpty()) {
                    cacheManager.setRegisteredVehicleForUser(userId, serverVehicleName);
                }
                return new EndToEndVehicleSyncResult(
                        true, instId, instSecret, me.vehicleId, serverVehicleName,
                        cacheManager.getActiveScopedToken(),
                        cacheManager.getActiveIntervalId(),
                        cacheManager.getActiveConsentId(),
                        cacheManager.getActiveCandidateId()
                );
            }
        }

        // 2) 새 설치 발급 (POST /installations)
        InstallationIssuedResponse issuedInst = createInstallation();
        if (issuedInst == null) {
            return new EndToEndVehicleSyncResult(false, instId, instSecret, null, vehicleDisplayName, null, null, null, null);
        }
        instId = issuedInst.installationId;
        instSecret = issuedInst.installationSecret;
        cacheManager.setInstallationCredentials(userId, instId, instSecret);

        // 3) 차량 페어링 QR 발급 (POST /pairing-requests)
        PairingQrIssuedResponse qrIssued = issuePairingRequest(
                instId,
                instSecret,
                "issue-" + UUID.randomUUID()
        );
        if (qrIssued == null) {
            return new EndToEndVehicleSyncResult(false, instId, instSecret, null, vehicleDisplayName, null, null, null, null);
        }

        // 4) 모바일 QR 미리보기 검증 (POST /pairing-requests/preview)
        previewPairingQr(qrIssued.requestId, qrIssued.proof);

        // 5) 모바일 차량 선택·생성 의사 제출 (POST /pairing-requests/{id}/intent)
        PairingStatusResponse intentResp = submitPairingIntent(
                qrIssued.requestId,
                qrIssued.proof,
                null,
                vehicleDisplayName
        );
        if (!intentResp.isSuccess() || intentResp.confirmationCode == null) {
            return new EndToEndVehicleSyncResult(false, instId, instSecret, null, vehicleDisplayName, null, null, null, null);
        }

        // 6) 페어링 요청 상태 조회 (GET /pairing-requests/{id})
        getPairingRequestStatus(qrIssued.requestId);

        // 7) 차량 화면 최종 확인 (POST /pairing-requests/{id}/confirm) -> 실제 vehicle_id 생성 및 OWNER 등록
        PairingStatusResponse confirmed = confirmPairingRequest(
                instId,
                instSecret,
                qrIssued.requestId,
                intentResp.confirmationCode
        );
        String vehicleId = confirmed.vehicleId;
        if (vehicleId != null && !vehicleId.isEmpty()) {
            cacheManager.setRegisteredVehicleUuid(userId, vehicleId);
            cacheManager.setRegisteredVehicleForUser(
                    userId,
                    confirmed.selectedVehicleName != null ? confirmed.selectedVehicleName : vehicleDisplayName
            );
        }

        // 8) 설치 연결 상태 최종 확인 (GET /installations/me)
        fetchInstallationMe(instId, instSecret);

        return new EndToEndVehicleSyncResult(
                confirmed.isSuccess() && vehicleId != null,
                instId,
                instSecret,
                vehicleId,
                vehicleDisplayName,
                null,
                null,
                null,
                null
        );
    }

    /**
     * [M04 이번 운행 사용자 확인·C02 세션·C03 후보 동의 및 검증 종단간 파이프라인]
     * 1) 차량 페어링 확인 (미연결 시 registerAndConfirmVehicleEndToEnd 수행)
     * 2) POST /trip-user/challenges (C02) -> POST /mobile/trip-user/approve -> POST /trip-user/session (scopedToken 획득)
     * 3) POST /recording-intervals (수동 기록 구간 시작 -> intervalId 획득)
     * 4) POST /trip-user/challenges (C03 intervalId) -> POST /mobile/trip-user/consent (consentId, candidateId 획득)
     * 5) POST /trip-user/candidates & GET /trip-user/events -> POST /trip-user/verify (최종 후보 검증)
     */
    @NonNull
    public synchronized EndToEndVehicleSyncResult authenticateAndVerifyTripUserEndToEnd(
            @NonNull UserCacheManager cacheManager,
            @NonNull String vehicleDisplayName
    ) {
        EndToEndVehicleSyncResult paired = registerAndConfirmVehicleEndToEnd(cacheManager, vehicleDisplayName);
        if (!paired.success || paired.installationId == null || paired.installationSecret == null || paired.vehicleId == null) {
            return paired;
        }
        String instId = paired.installationId;
        String instSecret = paired.installationSecret;
        String vehicleId = paired.vehicleId;

        // 1) C02 운행 사용자 확인 QR 발급 -> 모바일 승인 -> scopedToken 세션 획득
        String scopedToken = cacheManager.getActiveScopedToken();
        TripUserChallengeResponse c02Chal = issueTripUserChallenge(
                instSecret,
                instId,
                null,
                "c02-" + UUID.randomUUID()
        );
        if (c02Chal != null && c02Chal.proof != null) {
            if (approveTripUserQr(c02Chal.challengeId, c02Chal.proof, vehicleId)) {
                TripUserSessionResponse sessionResp = fetchTripUserSession(instSecret, c02Chal.challengeId);
                if (sessionResp != null && sessionResp.scopedToken != null) {
                    scopedToken = sessionResp.scopedToken;
                    cacheManager.setActiveScopedToken(scopedToken);
                }
            }
        }

        // 2) 수동 기록 구간 시작 (POST /recording-intervals)
        String startedAtIso = Instant.now().toString();
        RecordingIntervalResponse interval = startRecordingInterval(
                instSecret,
                scopedToken,
                "local-int-" + UUID.randomUUID(),
                startedAtIso
        );
        if (interval == null || interval.id.isEmpty()) {
            return new EndToEndVehicleSyncResult(
                    true, instId, instSecret, vehicleId, vehicleDisplayName, scopedToken, null, null, null
            );
        }
        String intervalId = interval.id;
        cacheManager.setActiveIntervalId(intervalId);

        // 3) C03 현재 구간용 챌린지 발급 -> 모바일 M04 동의 (POST /mobile/trip-user/consent)
        TripUserChallengeResponse c03Chal = issueTripUserChallenge(
                instSecret,
                null,
                intervalId,
                "c03-" + UUID.randomUUID()
        );
        String consentId = null;
        String candidateId = null;
        if (c03Chal != null && c03Chal.proof != null) {
            MobileConsentResponse consentResp = submitTripUserConsent(
                    vehicleId,
                    instId,
                    intervalId,
                    c03Chal.challengeId,
                    c03Chal.proof,
                    "consent-op-" + UUID.randomUUID()
            );
            if (consentResp.isSuccess()) {
                consentId = consentResp.consentId;
                candidateId = consentResp.candidateId != null ? consentResp.candidateId : consentResp.consentId;
                cacheManager.setActiveConsentId(consentId);
                cacheManager.setActiveCandidateId(candidateId);

                // 4) C03 후보 목록·이벤트 조회 및 최종 검증 (POST /trip-user/candidates, GET /trip-user/events, POST /trip-user/verify)
                fetchTripUserCandidatesCount(instSecret, intervalId);
                fetchTripUserEventsRevision(instSecret, 0L);
                verifyTripUserCandidate(instSecret, intervalId, candidateId, "verify-op-" + UUID.randomUUID());
            }
        }

        return new EndToEndVehicleSyncResult(
                true,
                instId,
                instSecret,
                vehicleId,
                vehicleDisplayName,
                scopedToken,
                intervalId,
                consentId,
                candidateId
        );
    }

    /**
     * [C04 운행 요약 업로드 및 M06 모바일 확인함 전달 종단간 파이프라인]
     * POST /recording-intervals/{id}/samples -> POST /recording-intervals/{id}/finish
     * -> PUT /recording-intervals/{tripId} -> GET /recording-intervals/{tripId}/summary
     * -> PUT /mobile-inbox/trips/{tripId}
     */
    @Nullable
    public synchronized C04TripSummaryResponse recordAndDeliverTripEndToEnd(
            @NonNull UserCacheManager cacheManager,
            @NonNull String vehicleDisplayName,
            double distanceKm,
            double startFuelLiters,
            double endFuelLiters,
            double unitPriceWon,
            @NonNull String priceSource
    ) {
        EndToEndVehicleSyncResult authResult = authenticateAndVerifyTripUserEndToEnd(cacheManager, vehicleDisplayName);
        if (!authResult.success
                || authResult.installationSecret == null
                || authResult.intervalId == null) {
            return null;
        }

        String instSecret = authResult.installationSecret;
        String tripId = authResult.intervalId;
        String candidateId = authResult.candidateId;
        double consumed = Math.max(0.1, startFuelLiters - endFuelLiters);

        // 1) 샘플 배치 업로드 (POST /recording-intervals/{id}/samples)
        uploadRecordingSamples(
                instSecret,
                tripId,
                candidateId,
                "batch-" + UUID.randomUUID(),
                distanceKm,
                consumed
        );

        // 2) 기록 구간 종료 (POST /recording-intervals/{id}/finish)
        Instant tripStart = Instant.now();
        String startedAtIso = tripStart.toString();
        String endedAtIso = tripStart.plusSeconds(1).toString();
        finishRecordingInterval(
                instSecret,
                tripId,
                "finish-" + UUID.randomUUID(),
                endedAtIso,
                1L
        );

        // 3) C04 운행 요약 업로드 (PUT /recording-intervals/{tripId})
        boolean uploaded = uploadTripSummary(
                instSecret,
                tripId,
                1L,
                candidateId,
                startedAtIso,
                endedAtIso,
                distanceKm,
                startFuelLiters,
                endFuelLiters,
                unitPriceWon,
                priceSource
        );
        if (!uploaded) {
            return null;
        }

        // 4) C04 운행 요약 조회 (GET /recording-intervals/{tripId}/summary)
        C04TripSummaryResponse summary = fetchTripSummary(instSecret, tripId, candidateId);

        // 5) M06 모바일 확인함에 운행 전달 (PUT /mobile-inbox/trips/{tripId})
        if (candidateId != null) {
            deliverTripToMobileInbox(
                    instSecret,
                    tripId,
                    candidateId,
                    "mobile-inbox:" + tripId,
                    1L
            );
        }

        return summary;
    }

    /**
     * 기기 연동 해제·권한 철회·로그아웃 시 활성 C03 동의(POST /trip-user/revoke) 및 C02 세션(DELETE /trip-user/session) 폐기
     */
    @Nullable
    public Boolean vehicleConnectionState(String vehicleId) { return vehicleConnectionState(vehicleId, null); }

    @Nullable
    public Boolean vehicleConnectionState(String vehicleId, @Nullable UserCacheManager cache) {
        AuthSession session = authRepository.getCurrentValidSession();
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) return null;
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + UUID.fromString(vehicleId) + "/connection", "GET", token);
            if (conn.getResponseCode() != 200) return null;
            String body = readBody(conn, false);
            JSONObject state = new JSONObject(body);
            AuthSession current = authRepository.getCurrentValidSession();
            if (cache != null && session != null && current != null && session.getUserId().equals(current.getUserId())
                    && vehicleId.equals(cache.getRegisteredVehicleUuid(session.getUserId()))) {
                String name = state.optString("vehicleName", "");
                if (!name.isBlank()) cache.setRegisteredVehicleForUser(session.getUserId(), name);
            }
            return state.getBoolean("paired");
        } catch (Exception failure) { return null; }
        finally { if (conn != null) conn.disconnect(); }
    }

    public boolean renameVehicle(String vehicleId, String name) {
        SafeLogger.w("차량명 수정 기능은 현재 비활성화되어 있습니다 (서버 vehicles.name 직접 참조).");
        return false;
    }

    public boolean disconnectVehicle(String vehicleId, String operationId) {
        String token = authRepository.getValidAccessToken();
        if (token == null || token.startsWith("local_jwt_")) return false;
        HttpURLConnection conn = null;
        try {
            conn = openMobileJwtConnection("/mobile/vehicles/" + UUID.fromString(vehicleId) + "/connection/disconnect", "POST", token);
            conn.setDoOutput(true);
            byte[] body = new JSONObject().put("kind", "USER").put("operationId", operationId).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var output = conn.getOutputStream()) { output.write(body); }
            if (conn.getResponseCode() == 200) return true;
            return Boolean.FALSE.equals(vehicleConnectionState(vehicleId));
        } catch (Exception failure) { return false; }
        finally { if (conn != null) conn.disconnect(); }
    }

    public void revokeActiveSessionAndConsent(@NonNull UserCacheManager cacheManager) {
        String userId = cacheManager.getLastAuthenticatedUserId();
        String consentId = cacheManager.getActiveConsentId();
        if (consentId != null && !consentId.isEmpty()) {
            revokeTripUserConsent(consentId);
            cacheManager.setActiveConsentId(null);
            cacheManager.setActiveCandidateId(null);
        }
        if (userId != null) {
            String instId = cacheManager.getInstallationId(userId);
            String instSecret = cacheManager.getInstallationSecret(userId);
            String vehicleId = cacheManager.getRegisteredVehicleUuid(userId);
            String scopedToken = cacheManager.getActiveScopedToken();
            if (instSecret != null && scopedToken != null) {
                if (!deleteTripUserSession(instSecret, scopedToken) && instId != null && vehicleId != null) {
                    TripUserChallengeResponse c02Chal = issueTripUserChallenge(
                            instSecret, instId, null, "logout-c02-" + UUID.randomUUID()
                    );
                    if (c02Chal != null && c02Chal.proof != null && approveTripUserQr(c02Chal.challengeId, c02Chal.proof, vehicleId)) {
                        TripUserSessionResponse sess = fetchTripUserSession(instSecret, c02Chal.challengeId);
                        if (sess != null && sess.scopedToken != null) {
                            deleteTripUserSession(instSecret, sess.scopedToken);
                        }
                    }
                }
                cacheManager.setActiveScopedToken(null);
            }
        }
    }

    // =========================================================================
    // 내부 파서 및 HTTP 유틸리티
    // =========================================================================

    @NonNull
    private static PairingStatusResponse parsePairingStatus(
            int httpStatus,
            @NonNull String fallbackId,
            @NonNull String body
    ) throws Exception {
        JSONObject json = new JSONObject(body);
        return new PairingStatusResponse(
                httpStatus,
                json.optString("id", fallbackId),
                json.optString("status", ""),
                json.isNull("vehicle_id") ? null : json.optString("vehicle_id", null),
                json.isNull("selected_vehicle_name") ? null : json.optString("selected_vehicle_name", null),
                json.isNull("confirmation_code") ? null : json.optString("confirmation_code", null),
                null
        );
    }

    @NonNull
    private static C04TripSummaryResponse parseC04SummaryJson(
            @NonNull String fallbackTripId,
            @NonNull JSONObject summary
    ) {
        String tripId = summary.optString("tripId", fallbackTripId);
        long revision = summary.optLong("revision", 1L);
        String startedAt = summary.isNull("startedAt") ? null : summary.optString("startedAt", null);
        String endedAt = summary.isNull("endedAt") ? null : summary.optString("endedAt", null);
        String priceSource = summary.isNull("priceSource") ? null : summary.optString("priceSource", null);

        Double distanceKm = null;
        JSONObject distObj = summary.optJSONObject("distanceKm");
        if (distObj != null && !distObj.isNull("value")) {
            distanceKm = distObj.optDouble("value");
        }

        Double fuelLiters = null;
        JSONObject fuelObj = summary.optJSONObject("fuelConsumedLiters");
        if (fuelObj != null && !fuelObj.isNull("value")) {
            fuelLiters = fuelObj.optDouble("value");
        }

        Double efficiency = null;
        JSONObject effObj = summary.optJSONObject("fuelEfficiencyKmPerLiter");
        if (effObj != null && !effObj.isNull("value")) {
            efficiency = effObj.optDouble("value");
        }

        Long estimatedCost = null;
        JSONObject costObj = summary.optJSONObject("estimatedCost");
        if (costObj != null && !costObj.isNull("value")) {
            estimatedCost = Math.round(costObj.optDouble("value"));
        }

        boolean missing = false;
        boolean endEstimated = false;
        JSONObject qualityObj = summary.optJSONObject("quality");
        if (qualityObj != null) {
            missing = qualityObj.optBoolean("missing", false);
            endEstimated = qualityObj.optBoolean("endEstimated", false);
        }

        return new C04TripSummaryResponse(
                true,
                tripId,
                revision,
                distanceKm,
                fuelLiters,
                efficiency,
                estimatedCost,
                priceSource,
                startedAt,
                endedAt,
                missing,
                endEstimated
        );
    }

    @NonNull
    private HttpURLConnection openMobileJwtConnection(
            @NonNull String path,
            @NonNull String method,
            @NonNull String accessToken
    ) throws IOException {
        String normalizedPath = path.startsWith("/") ? path : ("/" + path);
        URL url = new URL(getBaseUrl() + normalizedPath);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
        } catch (java.net.ProtocolException e) {
            if ("PATCH".equalsIgnoreCase(method)) {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            } else {
                throw e;
            }
        }
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Accept", "application/json");
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        }
        return conn;
    }

    @NonNull
    private HttpURLConnection openInstallationHeaderConnection(
            @NonNull String path,
            @NonNull String method,
            @NonNull String installationId,
            @NonNull String installationSecret
    ) throws IOException {
        String normalizedPath = path.startsWith("/") ? path : ("/" + path);
        URL url = new URL(getBaseUrl() + normalizedPath);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
        } catch (java.net.ProtocolException e) {
            if ("PATCH".equalsIgnoreCase(method)) {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            } else {
                throw e;
            }
        }
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("X-Installation-Id", installationId);
        conn.setRequestProperty("X-Installation-Secret", installationSecret);
        conn.setRequestProperty("Accept", "application/json");
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        }
        return conn;
    }

    @NonNull
    private HttpURLConnection openInstallationBearerConnection(
            @NonNull String path,
            @NonNull String method,
            @NonNull String installationSecret,
            @Nullable String scopedUserToken,
            @Nullable String candidateId
    ) throws IOException {
        String normalizedPath = path.startsWith("/") ? path : ("/" + path);
        URL url = new URL(getBaseUrl() + normalizedPath);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
        } catch (java.net.ProtocolException e) {
            if ("PATCH".equalsIgnoreCase(method)) {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            } else {
                throw e;
            }
        }
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("Authorization", "Bearer " + installationSecret);
        if (scopedUserToken != null && !scopedUserToken.isEmpty()) {
            conn.setRequestProperty("X-Trip-User-Session", "Bearer " + scopedUserToken);
        }
        if (candidateId != null && !candidateId.isEmpty()) {
            conn.setRequestProperty("X-Trip-User-Candidate", candidateId);
        }
        conn.setRequestProperty("Accept", "application/json");
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        }
        return conn;
    }

    private static void writeBody(@NonNull HttpURLConnection conn, @NonNull String body) throws IOException {
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    private static String readBody(@NonNull HttpURLConnection conn, boolean errorStream) throws IOException {
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
}
