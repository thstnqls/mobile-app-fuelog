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
import java.util.List;
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
            } else {
                payload.put("new_vehicle_name", newVehicleName != null ? newVehicleName.trim() : "내 차");
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

    /**
     * [M02 QR 코드 인식 기반 차량 연동 파이프라인]
     * 카메라로 스캔한 C01 QR JSON 페이로드({"v":1,"type":"fuelog.vehicle_pairing","request_id":"...","proof":"..."}
     * 또는 {"type":"fuelog.vehicle-registration","version":1,"requestId":"...","nonce":"..."})를 검증·승인하거나,
     * 차량 단말(C01)의 QR 요청을 발급·스캔·확정하여 실제 DB에 차량을 연동한다.
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
        String targetVehicleName = fallbackVehicleName.trim().isEmpty()
                ? "QR 연동 차량"
                : fallbackVehicleName.trim();

        // 1) 스캔된 QR JSON 페이로드가 전달된 경우 파싱 후 백엔드 QR 검증 API 호출
        if (scannedQrPayload != null && !scannedQrPayload.trim().isEmpty()) {
            String raw = scannedQrPayload.trim();
            try {
                if (raw.startsWith("{")) {
                    JSONObject qrJson = new JSONObject(raw);
                    if (qrJson.has("vehicle_name") && !qrJson.optString("vehicle_name").isEmpty()) {
                        targetVehicleName = qrJson.optString("vehicle_name");
                    }
                    String reqId = qrJson.optString("request_id", qrJson.optString("requestId", ""));
                    String proof = qrJson.optString("proof", "");
                    String nonce = qrJson.optString("nonce", "");

                    if (!reqId.isEmpty() && !proof.isEmpty()) {
                        previewPairingQr(reqId, proof);
                        PairingStatusResponse intentResp = submitPairingIntent(reqId, proof, null, targetVehicleName);
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
        conn.setRequestMethod(method);
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
        conn.setRequestMethod(method);
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
        conn.setRequestMethod(method);
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
