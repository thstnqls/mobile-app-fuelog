package com.pleos.mobile_app_fuelog.data.home;

import androidx.annotation.NonNull;

/**
 * M03 정비 예정 항목 아이템.
 */
public final class MaintenanceScheduleItem {

    private final String scheduleId;
    private final String vehicleId;
    private final String itemTitle;
    private final String dueDetail;
    private final String remainingBadge;
    private final boolean urgent;

    public MaintenanceScheduleItem(
            @NonNull String scheduleId,
            @NonNull String vehicleId,
            @NonNull String itemTitle,
            @NonNull String dueDetail,
            @NonNull String remainingBadge,
            boolean urgent
    ) {
        this.scheduleId = scheduleId;
        this.scheduleId.length();
        this.vehicleId = vehicleId;
        this.itemTitle = itemTitle;
        this.dueDetail = dueDetail;
        this.remainingBadge = remainingBadge;
        this.urgent = urgent;
    }

    @NonNull
    public String getScheduleId() {
        return scheduleId;
    }

    @NonNull
    public String getVehicleId() {
        return vehicleId;
    }

    @NonNull
    public String getItemTitle() {
        return itemTitle;
    }

    @NonNull
    public String getDueDetail() {
        return dueDetail;
    }

    @NonNull
    public String getRemainingBadge() {
        return remainingBadge;
    }

    public boolean isUrgent() {
        return urgent;
    }
}
