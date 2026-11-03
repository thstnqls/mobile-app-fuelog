package com.pleos.mobile_app_fuelog.data.home;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * M03. 홈 데이터 저장소 및 상태 조립기.
 * - 차량 선택 및 전환 시 이전 차량 데이터가 절대 섞이지 않도록 차량별 격리 조회를 수행한다.
 * - FR-M03-1: 미완료 작업(M07 주유 확인, M04 운행 귀속, M09 정산 변경, M13 공동 관리) 제공.
 * - FR-M03-2: 본인 기록 + 명시적 공유 기록만 필터링하여 반환.
 * - FR-M03-3: 확인된 주유·정비 지출과 미확인 추정 건을 분리하고, 운행 소비 비용 중복 합산을 차단.
 * - 예외/완료: 신규 사용자 빈 상태, 오프라인 시 마지막 갱신 시각 표시, 차량 전환 시 완전 격리.
 */
public final class M03HomeRepository {

    public static final String VEHICLE_ID_PRIMARY = "veh_primary_12ga3456";
    public static final String VEHICLE_ID_SECONDARY = "veh_secondary_88ha9012";

    private final List<VehicleProfile> vehicles = new ArrayList<>();
    private final List<PendingTaskItem> allPendingTasks = new ArrayList<>();
    private final List<TripRecord> allTrips = new ArrayList<>();
    private final List<ExpenseRecord> allExpenses = new ArrayList<>();
    private final List<MaintenanceScheduleItem> allMaintenanceItems = new ArrayList<>();

    @Nullable
    private String selectedVehicleId;
    private MonthlyExpenseSummary.ScopeMode currentScopeMode =
            MonthlyExpenseSummary.ScopeMode.PERSONAL_AND_SHARED;
    private boolean offlineMode = false;
    private boolean newUserEmptyMode = false;
    private String lastSyncedTimestamp;

    public M03HomeRepository() {
        updateSyncTimestampNow();
        seedDefaultData(null);
    }

    public synchronized void seedDefaultData(@Nullable String customPrimaryVehicleLabel) {
        vehicles.clear();
        allPendingTasks.clear();
        allTrips.clear();
        allExpenses.clear();
        allMaintenanceItems.clear();

        String primaryDisplay = (customPrimaryVehicleLabel != null && !customPrimaryVehicleLabel.trim().isEmpty())
                ? customPrimaryVehicleLabel.trim()
                : "12가 3456 (휘발유)";

        vehicles.add(new VehicleProfile(
                VEHICLE_ID_PRIMARY,
                "12가 3456",
                "휘발유",
                primaryDisplay,
                true
        ));
        vehicles.add(new VehicleProfile(
                VEHICLE_ID_SECONDARY,
                "88하 9012",
                "경유",
                "88하 9012 (경유 · 세컨드카)",
                false
        ));

        if (selectedVehicleId == null) {
            selectedVehicleId = VEHICLE_ID_PRIMARY;
        }

        // === 차량 1 (VEHICLE_ID_PRIMARY) 데이터 ===
        allPendingTasks.add(new PendingTaskItem(
                "task_m07_1",
                VEHICLE_ID_PRIMARY,
                PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM,
                "주유 결제 알림 확인 필요 (SK강남주유소)",
                "카드 승인 68,000원 · 주유량(L) 및 단가 확정 대기",
                68000L
        ));
        allPendingTasks.add(new PendingTaskItem(
                "task_m04_1",
                VEHICLE_ID_PRIMARY,
                PendingTaskItem.TaskDestination.M04_TRIP_ATTRIBUTION,
                "최근 운행 1건 목적·귀속 미지정",
                "판교 테크노밸리 → 양재 IC (18.4km) · 개인/업무/공동 귀속 선택",
                0L
        ));
        allPendingTasks.add(new PendingTaskItem(
                "task_m09_1",
                VEHICLE_ID_PRIMARY,
                PendingTaskItem.TaskDestination.M09_SETTLEMENT_CHANGE,
                "11월 공동 운행 정산 비율 변경 요청",
                "공동 사용자(김민수) 정산 변경안 확인 대기",
                45000L
        ));
        allPendingTasks.add(new PendingTaskItem(
                "task_m13_1",
                VEHICLE_ID_PRIMARY,
                PendingTaskItem.TaskDestination.M13_SHARE_MANAGEMENT,
                "차량 공동 지출 공유 범위 확인",
                "주유·정비 영수증 자동 공유 규칙 점검",
                0L
        ));

        allTrips.add(new TripRecord(
                "trip_1",
                VEHICLE_ID_PRIMARY,
                "오늘 08:40",
                "역삼역 → 판교역 (출근 운행)",
                16.8,
                34,
                "본인 기록 · 귀속 완료",
                2100L,
                ExpenseRecord.Ownership.MY_RECORD
        ));
        allTrips.add(new TripRecord(
                "trip_2",
                VEHICLE_ID_PRIMARY,
                "어제 19:15",
                "판교 테크노밸리 → 양재 IC",
                18.4,
                41,
                "명시적 공유 · 귀속 확인 필요",
                2300L,
                ExpenseRecord.Ownership.EXPLICITLY_SHARED
        ));
        // 비공유 타인 운행 기록 (FR-M03-2에 의해 노출/집계 제외 대상)
        allTrips.add(new TripRecord(
                "trip_unshared_hidden",
                VEHICLE_ID_PRIMARY,
                "어제 23:00",
                "비공유 타인 단독 운행 (집계 제외)",
                50.0,
                60,
                "비공유 타인 기록",
                6500L,
                ExpenseRecord.Ownership.UNSHARED_OTHER
        ));

        // 차량 1 이번 달 지출 내역
        // 1) 본인 확인 주유: 72,000원
        allExpenses.add(new ExpenseRecord(
                "exp_p_1",
                VEHICLE_ID_PRIMARY,
                ExpenseRecord.Category.FUEL,
                ExpenseRecord.Ownership.MY_RECORD,
                ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                "GS칼텍스 역삼점 주유 (43.5L)",
                "11.01",
                72000L
        ));
        // 2) 명시적 공유 확인 정비: 58,000원
        allExpenses.add(new ExpenseRecord(
                "exp_p_2",
                VEHICLE_ID_PRIMARY,
                ExpenseRecord.Category.MAINTENANCE,
                ExpenseRecord.Ownership.EXPLICITLY_SHARED,
                ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                "엔진오일 및 에어클리너 교환 (공동 지출)",
                "11.02",
                58000L
        ));
        // 3) 미확인 추정 주유 건: 68,000원 (별도 수/금액으로 표현)
        allExpenses.add(new ExpenseRecord(
                "exp_p_3",
                VEHICLE_ID_PRIMARY,
                ExpenseRecord.Category.FUEL,
                ExpenseRecord.Ownership.MY_RECORD,
                ExpenseRecord.ConfirmationState.UNCONFIRMED_ESTIMATE,
                "SK강남주유소 카드 승인 추정 (미확인)",
                "11.03",
                68000L
        ));
        // 4) 운행 소비 추정 비용: 4,400원 (FR-M03-3: 월 지출에 중복 합산하지 않음)
        allExpenses.add(new ExpenseRecord(
                "exp_p_trip_est",
                VEHICLE_ID_PRIMARY,
                ExpenseRecord.Category.TRIP_CONSUMPTION_ESTIMATE,
                ExpenseRecord.Ownership.MY_RECORD,
                ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                "이번 달 운행 연료 소비 추정액",
                "11.03",
                4400L
        ));
        // 5) 비공유 타인 지출: 99,000원 (FR-M03-2: 집계 제외)
        allExpenses.add(new ExpenseRecord(
                "exp_p_unshared",
                VEHICLE_ID_PRIMARY,
                ExpenseRecord.Category.FUEL,
                ExpenseRecord.Ownership.UNSHARED_OTHER,
                ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                "비공유 타인 주유 기록",
                "11.02",
                99000L
        ));

        allMaintenanceItems.add(new MaintenanceScheduleItem(
                "maint_p_1",
                VEHICLE_ID_PRIMARY,
                "와이퍼 블레이드 교체 예정",
                "권장 주기 도래 (6개월 경과)",
                "D-5",
                true
        ));
        allMaintenanceItems.add(new MaintenanceScheduleItem(
                "maint_p_2",
                VEHICLE_ID_PRIMARY,
                "타이어 위치 교환",
                "목표 주행거리 40,000km 기준",
                "1,120km 남음",
                false
        ));

        // === 차량 2 (VEHICLE_ID_SECONDARY: 88하 9012 경유) 데이터 (차량 전환 격리 검증용) ===
        allPendingTasks.add(new PendingTaskItem(
                "task_sec_m07",
                VEHICLE_ID_SECONDARY,
                PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM,
                "[88하 9012] 경유 주유 영수증 확인 필요",
                "현대오일뱅크 54,000원 · 사용자 확인 대기",
                54000L
        ));

        allTrips.add(new TripRecord(
                "trip_sec_1",
                VEHICLE_ID_SECONDARY,
                "11.01 14:20",
                "수원 광교 → 인천 송도",
                42.5,
                52,
                "본인 기록 · 귀속 완료",
                4800L,
                ExpenseRecord.Ownership.MY_RECORD
        ));

        allExpenses.add(new ExpenseRecord(
                "exp_sec_1",
                VEHICLE_ID_SECONDARY,
                ExpenseRecord.Category.FUEL,
                ExpenseRecord.Ownership.MY_RECORD,
                ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                "S-OIL 광교점 경유 주유 (35.0L)",
                "11.01",
                51000L
        ));
        allExpenses.add(new ExpenseRecord(
                "exp_sec_unconf",
                VEHICLE_ID_SECONDARY,
                ExpenseRecord.Category.FUEL,
                ExpenseRecord.Ownership.MY_RECORD,
                ExpenseRecord.ConfirmationState.UNCONFIRMED_ESTIMATE,
                "현대오일뱅크 경유 결제 추정 (미확인)",
                "11.02",
                54000L
        ));

        allMaintenanceItems.add(new MaintenanceScheduleItem(
                "maint_sec_1",
                VEHICLE_ID_SECONDARY,
                "요소수(AdBlue) 보충 점검",
                "경유차 배출가스 저감장치 정기 점검",
                "450km 남음",
                true
        ));
    }

    public synchronized void updateSyncTimestampNow() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA);
        this.lastSyncedTimestamp = sdf.format(new Date());
    }

    @NonNull
    public synchronized String getLastSyncedTimestamp() {
        return lastSyncedTimestamp;
    }

    public synchronized boolean isOfflineMode() {
        return offlineMode;
    }

    public synchronized void setOfflineMode(boolean offlineMode) {
        this.offlineMode = offlineMode;
        if (!offlineMode) {
            updateSyncTimestampNow();
        }
    }

    public synchronized boolean isNewUserEmptyMode() {
        return newUserEmptyMode;
    }

    public synchronized void setNewUserEmptyMode(boolean newUserEmptyMode) {
        this.newUserEmptyMode = newUserEmptyMode;
    }

    @NonNull
    public synchronized List<VehicleProfile> getVehicles() {
        if (newUserEmptyMode) {
            return Collections.emptyList();
        }
        return new ArrayList<>(vehicles);
    }

    @Nullable
    public synchronized VehicleProfile getSelectedVehicle() {
        if (newUserEmptyMode || selectedVehicleId == null) {
            return null;
        }
        for (VehicleProfile v : vehicles) {
            if (v.getVehicleId().equals(selectedVehicleId)) {
                return v;
            }
        }
        return vehicles.isEmpty() ? null : vehicles.get(0);
    }

    /**
     * 예외/완료: 차량을 바꾸면 이전 차량 데이터가 남아 섞이지 않는다.
     */
    public synchronized void selectVehicle(@NonNull String vehicleId) {
        this.selectedVehicleId = vehicleId;
    }

    @NonNull
    public synchronized MonthlyExpenseSummary.ScopeMode getCurrentScopeMode() {
        return currentScopeMode;
    }

    public synchronized void setCurrentScopeMode(@NonNull MonthlyExpenseSummary.ScopeMode scopeMode) {
        this.currentScopeMode = scopeMode;
    }

    /**
     * 선택된 차량의 미완료 작업(FR-M03-1)만 반환한다.
     */
    @NonNull
    public synchronized List<PendingTaskItem> getPendingTasksForSelectedVehicle() {
        VehicleProfile current = getSelectedVehicle();
        if (current == null) {
            return Collections.emptyList();
        }
        List<PendingTaskItem> result = new ArrayList<>();
        for (PendingTaskItem item : allPendingTasks) {
            if (current.getVehicleId().equals(item.getVehicleId())) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 선택된 차량의 최근 운행 내역(FR-M03-2: 본인 기록 및 명시적 공유 기록만)을 반환한다.
     */
    @NonNull
    public synchronized List<TripRecord> getRecentTripsForSelectedVehicle() {
        VehicleProfile current = getSelectedVehicle();
        if (current == null) {
            return Collections.emptyList();
        }
        List<TripRecord> result = new ArrayList<>();
        for (TripRecord trip : allTrips) {
            if (!current.getVehicleId().equals(trip.getVehicleId())) {
                continue;
            }
            if (trip.getOwnership() == ExpenseRecord.Ownership.UNSHARED_OTHER) {
                continue;
            }
            if (currentScopeMode == MonthlyExpenseSummary.ScopeMode.PERSONAL_ONLY
                    && trip.getOwnership() != ExpenseRecord.Ownership.MY_RECORD) {
                continue;
            }
            result.add(trip);
        }
        return result;
    }

    /**
     * 선택된 차량의 이번 달 지출 집계(FR-M03-2, FR-M03-3)를 계산한다.
     */
    @NonNull
    public synchronized MonthlyExpenseSummary getMonthlyExpenseSummaryForSelectedVehicle() {
        VehicleProfile current = getSelectedVehicle();
        String vehicleId = current != null ? current.getVehicleId() : "none";
        if (current == null) {
            return new MonthlyExpenseSummary(
                    vehicleId,
                    currentScopeMode,
                    0L, 0L, 0L, 0, 0, 0L, 0L
            );
        }
        return MonthlyExpenseSummary.calculate(vehicleId, currentScopeMode, allExpenses);
    }

    /**
     * 선택된 차량의 확인된 지출 상세 리스트를 반환한다.
     */
    @NonNull
    public synchronized List<ExpenseRecord> getConfirmedExpensesForSelectedVehicle() {
        VehicleProfile current = getSelectedVehicle();
        if (current == null) {
            return Collections.emptyList();
        }
        List<ExpenseRecord> result = new ArrayList<>();
        for (ExpenseRecord record : allExpenses) {
            if (!current.getVehicleId().equals(record.getVehicleId())) {
                continue;
            }
            if (record.getOwnership() == ExpenseRecord.Ownership.UNSHARED_OTHER) {
                continue;
            }
            if (currentScopeMode == MonthlyExpenseSummary.ScopeMode.PERSONAL_ONLY
                    && record.getOwnership() != ExpenseRecord.Ownership.MY_RECORD) {
                continue;
            }
            if (record.getCategory() == ExpenseRecord.Category.TRIP_CONSUMPTION_ESTIMATE) {
                continue;
            }
            if (record.getConfirmationState() == ExpenseRecord.ConfirmationState.USER_CONFIRMED) {
                result.add(record);
            }
        }
        return result;
    }

    /**
     * 선택된 차량의 정비 예정 목록을 반환한다.
     */
    @NonNull
    public synchronized List<MaintenanceScheduleItem> getMaintenanceSchedulesForSelectedVehicle() {
        VehicleProfile current = getSelectedVehicle();
        if (current == null) {
            return Collections.emptyList();
        }
        List<MaintenanceScheduleItem> result = new ArrayList<>();
        for (MaintenanceScheduleItem item : allMaintenanceItems) {
            if (current.getVehicleId().equals(item.getVehicleId())) {
                result.add(item);
            }
        }
        return result;
    }
}
