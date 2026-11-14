package com.pleos.mobile_app_fuelog.data.home;

import androidx.annotation.NonNull;

/**
 * FR-M03-2 & FR-M03-3 지출 및 비용 레코드.
 * - Category: 주유(FUEL), 정비(MAINTENANCE), 운행 소비 추정 비용(TRIP_CONSUMPTION_ESTIMATE)
 * - Ownership: 본인 기록(MY_RECORD), 명시적 공유 기록(EXPLICITLY_SHARED), 비공유 타인 기록(UNSHARED_OTHER)
 * - Confirmation: 사용자 확인 거래(USER_CONFIRMED), 미확인 추정 건(UNCONFIRMED_ESTIMATE)
 */
public final class ExpenseRecord {

    public enum Category {
        FUEL,
        MAINTENANCE,
        /**
         * FR-M03-3: 운행 소비 비용은 월 실제 입력 지출에 중복 합산하지 않는다.
         */
        TRIP_CONSUMPTION_ESTIMATE
    }

    public enum Ownership {
        MY_RECORD,
        EXPLICITLY_SHARED,
        /**
         * FR-M03-2: 본인 기록과 명시적으로 공유받은 기록만 집계하므로 비공유 타인 기록은 제외한다.
         */
        UNSHARED_OTHER
    }

    public enum ConfirmationState {
        USER_CONFIRMED,
        UNCONFIRMED_ESTIMATE
    }

    private final String recordId;
    private final String vehicleId;
    private final Category category;
    private final Ownership ownership;
    private final ConfirmationState confirmationState;
    private final String title;
    private final String dateLabel;
    private final long amountWon;

    public ExpenseRecord(
            @NonNull String recordId,
            @NonNull String vehicleId,
            @NonNull Category category,
            @NonNull Ownership ownership,
            @NonNull ConfirmationState confirmationState,
            @NonNull String title,
            @NonNull String dateLabel,
            long amountWon
    ) {
        this.recordId = recordId;
        this.vehicleId = vehicleId;
        this.category = category;
        this.ownership = ownership;
        this.confirmationState = confirmationState;
        this.title = title;
        this.dateLabel = dateLabel;
        this.amountWon = amountWon;
    }

    @NonNull
    public String getRecordId() {
        return recordId;
    }

    @NonNull
    public String getVehicleId() {
        return vehicleId;
    }

    @NonNull
    public Category getCategory() {
        return category;
    }

    @NonNull
    public Ownership getOwnership() {
        return ownership;
    }

    @NonNull
    public ConfirmationState getConfirmationState() {
        return confirmationState;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    @NonNull
    public String getDateLabel() {
        return dateLabel;
    }

    public long getAmountWon() {
        return amountWon;
    }
}
