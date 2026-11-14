package com.pleos.mobile_app_fuelog.data.home;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * M03 홈 화면에서 선택 가능한 차량 프로필.
 */
public final class VehicleProfile {

    private final String vehicleId;
    private final String plateNumber;
    private final String fuelType;
    private final String displayName;
    private final boolean sharedVehicle;
    private final String role;
    private final boolean paired;
    private final String installationId;

    public VehicleProfile(
            @NonNull String vehicleId,
            @NonNull String plateNumber,
            @NonNull String fuelType,
            @NonNull String displayName,
            boolean sharedVehicle
    ) {
        this(vehicleId, plateNumber, fuelType, displayName, sharedVehicle, "OWNER", true, null);
    }

    public VehicleProfile(
            @NonNull String vehicleId,
            @NonNull String plateNumber,
            @NonNull String fuelType,
            @NonNull String displayName,
            boolean sharedVehicle,
            @Nullable String role,
            boolean paired,
            @Nullable String installationId
    ) {
        this.vehicleId = vehicleId;
        this.plateNumber = plateNumber;
        this.fuelType = fuelType;
        this.displayName = displayName;
        this.sharedVehicle = sharedVehicle;
        this.role = role != null ? role : "OWNER";
        this.paired = paired;
        this.installationId = installationId;
    }

    @NonNull
    public String getVehicleId() {
        return vehicleId;
    }

    @NonNull
    public String getPlateNumber() {
        return plateNumber;
    }

    @NonNull
    public String getFuelType() {
        return fuelType;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    public boolean isSharedVehicle() {
        return sharedVehicle;
    }

    @NonNull
    public String getRole() {
        return role;
    }

    public boolean isPaired() {
        return paired;
    }

    @Nullable
    public String getInstallationId() {
        return installationId;
    }
}
