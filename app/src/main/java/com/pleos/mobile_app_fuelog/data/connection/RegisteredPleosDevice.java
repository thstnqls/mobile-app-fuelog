package com.pleos.mobile_app_fuelog.data.connection;

import androidx.annotation.NonNull;

/**
 * M04 등록 기기 아이템 (Pleos 차량 인포테인먼트/안드로이드 기기).
 */
public final class RegisteredPleosDevice {

    private final String deviceId;
    private final String deviceName;
    private final String macAddress;
    private final String vehicleAlias;
    private final boolean qrPairingSupported;
    private boolean trustedAutoRecommend;

    public RegisteredPleosDevice(
            @NonNull String deviceId,
            @NonNull String deviceName,
            @NonNull String macAddress,
            @NonNull String vehicleAlias,
            boolean qrPairingSupported,
            boolean trustedAutoRecommend
    ) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.macAddress = macAddress;
        this.vehicleAlias = vehicleAlias;
        this.qrPairingSupported = qrPairingSupported;
        this.trustedAutoRecommend = trustedAutoRecommend;
    }

    @NonNull
    public String getDeviceId() {
        return deviceId;
    }

    @NonNull
    public String getDeviceName() {
        return deviceName;
    }

    @NonNull
    public String getMacAddress() {
        return macAddress;
    }

    @NonNull
    public String getVehicleAlias() {
        return vehicleAlias;
    }

    public boolean isQrPairingSupported() {
        return qrPairingSupported;
    }

    public boolean isTrustedAutoRecommend() {
        return trustedAutoRecommend;
    }

    public void setTrustedAutoRecommend(boolean trustedAutoRecommend) {
        this.trustedAutoRecommend = trustedAutoRecommend;
    }
}
