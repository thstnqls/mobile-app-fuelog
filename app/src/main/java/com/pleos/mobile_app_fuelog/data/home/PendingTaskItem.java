package com.pleos.mobile_app_fuelog.data.home;

import androidx.annotation.NonNull;

/**
 * FR-M03-1: 홈 화면 최상단에 배치되는 미완료 작업(주유 확인, 운행 귀속, 정산 변경 등).
 * 탭하면 M07 / M04 / M09 / M13 목적 화면으로 이동한다.
 */
public final class PendingTaskItem {

    public enum TaskDestination {
        M07_FUEL_CONFIRM("M07", "주유 확인"),
        M04_TRIP_ATTRIBUTION("M04", "운행 귀속"),
        M09_SETTLEMENT_CHANGE("M09", "정산 변경"),
        M13_SHARE_MANAGEMENT("M13", "공동 관리·규칙 확인");

        private final String screenCode;
        private final String badgeLabel;

        TaskDestination(String screenCode, String badgeLabel) {
            this.screenCode = screenCode;
            this.badgeLabel = badgeLabel;
        }

        @NonNull
        public String getScreenCode() {
            return screenCode;
        }

        @NonNull
        public String getBadgeLabel() {
            return badgeLabel;
        }
    }

    private final String taskId;
    private final String vehicleId;
    private final TaskDestination destination;
    private final String title;
    private final String subtitle;
    private final long estimatedAmountWon;

    public PendingTaskItem(
            @NonNull String taskId,
            @NonNull String vehicleId,
            @NonNull TaskDestination destination,
            @NonNull String title,
            @NonNull String subtitle,
            long estimatedAmountWon
    ) {
        this.taskId = taskId;
        this.vehicleId = vehicleId;
        this.destination = destination;
        this.title = title;
        this.subtitle = subtitle;
        this.estimatedAmountWon = estimatedAmountWon;
    }

    @NonNull
    public String getTaskId() {
        return taskId;
    }

    @NonNull
    public String getVehicleId() {
        return vehicleId;
    }

    @NonNull
    public TaskDestination getDestination() {
        return destination;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    @NonNull
    public String getSubtitle() {
        return subtitle;
    }

    public long getEstimatedAmountWon() {
        return estimatedAmountWon;
    }
}
