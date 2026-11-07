package com.pleos.mobile_app_fuelog.data.connection;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * FR-M04-2: 서버가 발급한 짧은 수명의 차량(vehicleId)·운행(tripSessionId)·챌린지(challengeNonce)에 묶인 증명.
 * - 증명 재사용(Replay), 다른 차량/운행 세션으로의 전용, 만료 후 사용을 방지한다.
 */
public final class TripAuthProof {

    public static final long DEFAULT_TTL_SECONDS = 60L;

    private final String proofId;
    private final String userId;
    private final String userEmail;
    private final String phoneDeviceId;
    private final String vehicleId;
    private final String tripSessionId;
    private final String challengeNonce;
    private final long issuedAtEpochSeconds;
    private final long expiresAtEpochSeconds;
    private final String signatureHash;

    public TripAuthProof(
            @NonNull String proofId,
            @NonNull String userId,
            @NonNull String userEmail,
            @NonNull String phoneDeviceId,
            @NonNull String vehicleId,
            @NonNull String tripSessionId,
            @NonNull String challengeNonce,
            long issuedAtEpochSeconds,
            long expiresAtEpochSeconds,
            @NonNull String signatureHash
    ) {
        this.proofId = proofId;
        this.userId = userId;
        this.userEmail = userEmail;
        this.phoneDeviceId = phoneDeviceId;
        this.vehicleId = vehicleId;
        this.tripSessionId = tripSessionId;
        this.challengeNonce = challengeNonce;
        this.issuedAtEpochSeconds = issuedAtEpochSeconds;
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
        this.signatureHash = signatureHash;
    }

    @NonNull
    public static TripAuthProof issueShortLivedProof(
            @NonNull String userId,
            @NonNull String userEmail,
            @NonNull String phoneDeviceId,
            @NonNull String vehicleId,
            @NonNull String tripSessionId,
            @NonNull String challengeNonce,
            long nowEpochSeconds
    ) {
        String proofId = "prf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long expiresAt = nowEpochSeconds + DEFAULT_TTL_SECONDS;
        String sig = computeBindingSignature(
                proofId, userId, phoneDeviceId, vehicleId, tripSessionId, challengeNonce, expiresAt
        );
        return new TripAuthProof(
                proofId,
                userId,
                userEmail,
                phoneDeviceId,
                vehicleId,
                tripSessionId,
                challengeNonce,
                nowEpochSeconds,
                expiresAt,
                sig
        );
    }

    public boolean isExpired(long currentEpochSeconds) {
        return currentEpochSeconds >= expiresAtEpochSeconds;
    }

    public boolean matchesTarget(
            @NonNull String expectedVehicleId,
            @NonNull String expectedTripSessionId,
            @NonNull String expectedChallengeNonce
    ) {
        if (!vehicleId.equals(expectedVehicleId)
                || !tripSessionId.equals(expectedTripSessionId)
                || !challengeNonce.equals(expectedChallengeNonce)) {
            return false;
        }
        String expectedSig = computeBindingSignature(
                proofId, userId, phoneDeviceId, vehicleId, tripSessionId, challengeNonce, expiresAtEpochSeconds
        );
        return expectedSig.equals(signatureHash);
    }

    @NonNull
    public String toJsonPayload() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "FUELOG_QR_PROOF");
            obj.put("proof_id", proofId);
            obj.put("user_id", userId);
            obj.put("user_email", userEmail);
            obj.put("phone_device_id", phoneDeviceId);
            obj.put("vehicle_id", vehicleId);
            obj.put("trip_session_id", tripSessionId);
            obj.put("challenge_nonce", challengeNonce);
            obj.put("issued_at", issuedAtEpochSeconds);
            obj.put("expires_at", expiresAtEpochSeconds);
            obj.put("sig", signatureHash);
            return obj.toString();
        } catch (Exception e) {
            return "{\"type\":\"FUELOG_QR_PROOF\",\"proof_id\":\"" + proofId + "\"}";
        }
    }

    @NonNull
    private static String computeBindingSignature(
            String proofId,
            String userId,
            String phoneDeviceId,
            String vehicleId,
            String tripSessionId,
            String challengeNonce,
            long expiresAt
    ) {
        String raw = proofId + "|" + userId + "|" + phoneDeviceId + "|"
                + vehicleId + "|" + tripSessionId + "|" + challengeNonce + "|" + expiresAt;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12 && i < hash.length; i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    @NonNull
    public String getProofId() {
        return proofId;
    }

    @NonNull
    public String getUserId() {
        return userId;
    }

    @NonNull
    public String getUserEmail() {
        return userEmail;
    }

    @NonNull
    public String getPhoneDeviceId() {
        return phoneDeviceId;
    }

    @NonNull
    public String getVehicleId() {
        return vehicleId;
    }

    @NonNull
    public String getTripSessionId() {
        return tripSessionId;
    }

    @NonNull
    public String getChallengeNonce() {
        return challengeNonce;
    }

    public long getIssuedAtEpochSeconds() {
        return issuedAtEpochSeconds;
    }

    public long getExpiresAtEpochSeconds() {
        return expiresAtEpochSeconds;
    }
}
