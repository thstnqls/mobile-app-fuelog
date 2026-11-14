package com.pleos.mobile_app_fuelog.data.home;

import androidx.annotation.NonNull;

/**
 * M03 최근 운행 기록 아이템.
 */
public final class TripRecord {

    private final String tripId;
    private final String vehicleId;
    private final String dateTimeLabel;
    private final String routeSummary;
    private final double distanceKm;
    private final int durationMinutes;
    private final String attributionBadge;
    private final long estimatedFuelConsumptionWon;
    private final ExpenseRecord.Ownership ownership;

    public TripRecord(
            @NonNull String tripId,
            @NonNull String vehicleId,
            @NonNull String dateTimeLabel,
            @NonNull String routeSummary,
            double distanceKm,
            int durationMinutes,
            @NonNull String attributionBadge,
            long estimatedFuelConsumptionWon,
            @NonNull ExpenseRecord.Ownership ownership
    ) {
        this.tripId = tripId;
        this.vehicleId = vehicleId;
        this.dateTimeLabel = dateTimeLabel;
        this.routeSummary = routeSummary;
        this.distanceKm = distanceKm;
        this.durationMinutes = durationMinutes;
        this.attributionBadge = attributionBadge;
        this.estimatedFuelConsumptionWon = estimatedFuelConsumptionWon;
        this.ownership = ownership;
    }

    @NonNull
    public String getTripId() {
        return tripId;
    }

    @NonNull
    public String getVehicleId() {
        return vehicleId;
    }

    @NonNull
    public String getDateTimeLabel() {
        return dateTimeLabel;
    }

    @NonNull
    public String getRouteSummary() {
        return routeSummary;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    @NonNull
    public String getAttributionBadge() {
        return attributionBadge;
    }

    public long getEstimatedFuelConsumptionWon() {
        return estimatedFuelConsumptionWon;
    }

    @NonNull
    public ExpenseRecord.Ownership getOwnership() {
        return ownership;
    }
}
