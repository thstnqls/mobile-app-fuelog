package com.pleos.mobile_app_fuelog.data.home;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * FR-M03-2 & FR-M03-3 월 실제 입력 지출 집계 결과.
 * - FR-M03-2: 본인 기록(MY_RECORD)과 명시적으로 공유받은 기록(EXPLICITLY_SHARED)만 집계하며,
 *             개인 지출만 보기 / 개인+차량 공동 지출 보기 범위를 명시한다.
 * - FR-M03-3: 월 지출 합계(confirmedTotalWon)는 사용자 확인 거래(USER_CONFIRMED)만 합산하고,
 *             미확인 추정 건(UNCONFIRMED_ESTIMATE)은 별도 건수/금액으로 분리 표현한다.
 *             운행 소비 비용(TRIP_CONSUMPTION_ESTIMATE)은 중복 합산하지 않는다.
 */
public final class MonthlyExpenseSummary {

    public enum ScopeMode {
        PERSONAL_AND_SHARED("개인 + 차량 공동 지출 (본인 기록 + 명시적 공유 기록)"),
        PERSONAL_ONLY("개인 지출만 (본인 기록만 집계)");

        private final String displayDescription;

        ScopeMode(String displayDescription) {
            this.displayDescription = displayDescription;
        }

        @NonNull
        public String getDisplayDescription() {
            return displayDescription;
        }
    }

    private final String vehicleId;
    private final ScopeMode scopeMode;
    private final long confirmedTotalWon;
    private final long confirmedFuelWon;
    private final long confirmedMaintenanceWon;
    private final int confirmedCount;
    private final int unconfirmedCount;
    private final long unconfirmedEstimatedWon;
    private final long excludedTripConsumptionWon;

    public MonthlyExpenseSummary(
            @NonNull String vehicleId,
            @NonNull ScopeMode scopeMode,
            long confirmedTotalWon,
            long confirmedFuelWon,
            long confirmedMaintenanceWon,
            int confirmedCount,
            int unconfirmedCount,
            long unconfirmedEstimatedWon,
            long excludedTripConsumptionWon
    ) {
        this.vehicleId = vehicleId;
        this.scopeMode = scopeMode;
        this.confirmedTotalWon = confirmedTotalWon;
        this.confirmedFuelWon = confirmedFuelWon;
        this.confirmedMaintenanceWon = confirmedMaintenanceWon;
        this.confirmedCount = confirmedCount;
        this.unconfirmedCount = unconfirmedCount;
        this.unconfirmedEstimatedWon = unconfirmedEstimatedWon;
        this.excludedTripConsumptionWon = excludedTripConsumptionWon;
    }

    @NonNull
    public static MonthlyExpenseSummary calculate(
            @NonNull String targetVehicleId,
            @NonNull ScopeMode scopeMode,
            @NonNull List<ExpenseRecord> allRecords
    ) {
        long confirmedFuel = 0L;
        long confirmedMaint = 0L;
        int confirmedCnt = 0;
        int unconfirmedCnt = 0;
        long unconfirmedWon = 0L;
        long excludedTripWon = 0L;

        for (ExpenseRecord record : allRecords) {
            // 1) 선택된 차량 데이터만 집계 (이전 차량 데이터 혼입 방지)
            if (!targetVehicleId.equals(record.getVehicleId())) {
                continue;
            }

            // 2) FR-M03-2: 비공유 타인 기록(UNSHARED_OTHER)은 언제나 집계 제외
            if (record.getOwnership() == ExpenseRecord.Ownership.UNSHARED_OTHER) {
                continue;
            }
            if (scopeMode == ScopeMode.PERSONAL_ONLY
                    && record.getOwnership() != ExpenseRecord.Ownership.MY_RECORD) {
                continue;
            }

            // 3) FR-M03-3: 운행 소비 추정 비용은 월 실제 지출에 절대 중복 합산하지 않음
            if (record.getCategory() == ExpenseRecord.Category.TRIP_CONSUMPTION_ESTIMATE) {
                excludedTripWon += record.getAmountWon();
                continue;
            }

            // 4) FR-M03-3: 사용자 확인 거래 vs 미확인 추정 건 분리
            if (record.getConfirmationState() == ExpenseRecord.ConfirmationState.USER_CONFIRMED) {
                confirmedCnt++;
                if (record.getCategory() == ExpenseRecord.Category.FUEL) {
                    confirmedFuel += record.getAmountWon();
                } else if (record.getCategory() == ExpenseRecord.Category.MAINTENANCE) {
                    confirmedMaint += record.getAmountWon();
                }
            } else {
                unconfirmedCnt++;
                unconfirmedWon += record.getAmountWon();
            }
        }

        return new MonthlyExpenseSummary(
                targetVehicleId,
                scopeMode,
                confirmedFuel + confirmedMaint,
                confirmedFuel,
                confirmedMaint,
                confirmedCnt,
                unconfirmedCnt,
                unconfirmedWon,
                excludedTripWon
        );
    }

    @NonNull
    public String getVehicleId() {
        return vehicleId;
    }

    @NonNull
    public ScopeMode getScopeMode() {
        return scopeMode;
    }

    public long getConfirmedTotalWon() {
        return confirmedTotalWon;
    }

    public long getConfirmedFuelWon() {
        return confirmedFuelWon;
    }

    public long getConfirmedMaintenanceWon() {
        return confirmedMaintenanceWon;
    }

    public int getConfirmedCount() {
        return confirmedCount;
    }

    public int getUnconfirmedCount() {
        return unconfirmedCount;
    }

    public long getUnconfirmedEstimatedWon() {
        return unconfirmedEstimatedWon;
    }

    public long getExcludedTripConsumptionWon() {
        return excludedTripConsumptionWon;
    }
}
