package com.pleos.mobile_app_fuelog.data.home;

import androidx.annotation.NonNull;

/**
 * M03 홈 화면에서 선택 가능한 차량 프로필.
 */
public final class VehicleProfile {

    private final String vehicleId;
    private final String plateNumber;
    private final String fuelType;
    private final String displayName;
    private final boolean sharedVehicle;

    public VehicleProfile(
            @NonNull String vehicleId,
            @NonNull String plateNumber,
            @NonNull String fuelType,
            @NonNull String displayName,
            boolean sharedVehicle
    ) {
        this.vehicleId = vehicleId;
        this.plateNumber = plateNumber;
        this.fuelType = fuelType;
        this.displayName = displayName;
        this.sharedVehicle = sharedVehicle;
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
}
