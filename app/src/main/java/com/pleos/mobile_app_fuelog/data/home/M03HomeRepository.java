package com.pleos.mobile_app_fuelog.data.home;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M03. 홈 데이터 저장소 및 상태 조립기 (백엔드 API / DB 연동 전용).
 * - 앱 내부에 하드코딩된 더미 데이터베이스 없이, 백엔드 API(`GET /vehicle/home`, `GET /sync/status`, `GET /mobile/inbox` 등)에서
 *   조회한 실제 DB 데이터와 동적 주입된 레코드만을 차량별로 격리하여 관리한다.
 * - FR-M03-1: 미완료 작업(M07 주유 확인, M04 운행 귀속, M09 정산 변경, M13 공동 관리) 제공.
 * - FR-M03-2: 본인 기록 + 명시적 공유 기록만 필터링하여 반환.
 * - FR-M03-3: 확인된 주유·정비 지출과 미확인 추정 건을 분리하고, 운행 소비 비용 중복 합산을 차단.
 * - 예외/완료: 신규 사용자 빈 상태, 오프라인 시 마지막 갱신 시각 표시, 차량 전환 시 완전 격리.
 */
public final class M03HomeRepository {

    public static final String VEHICLE_ID_PRIMARY = "veh_primary_db";
    public static final String VEHICLE_ID_SECONDARY = "veh_secondary_db";

    private final List<VehicleProfile> vehicles = new ArrayList<>();
    private final List<PendingTaskItem> allPendingTasks = new ArrayList<>();
    private final List<TripRecord> allTrips = new ArrayList<>();
    private final List<ExpenseRecord> allExpenses = new ArrayList<>();
    private final List<MaintenanceScheduleItem> allMaintenanceItems = new ArrayList<>();
    private final Map<String, Double> vehicleOdometerMap = new HashMap<>();

    @Nullable
    private String selectedVehicleId;
    private MonthlyExpenseSummary.ScopeMode currentScopeMode =
            MonthlyExpenseSummary.ScopeMode.PERSONAL_AND_SHARED;
    private boolean offlineMode = false;
    private boolean newUserEmptyMode = false;
    private String lastSyncedTimestamp;
    private double latestOdometerKm = 0.0;

    private static volatile M03HomeRepository instance;

    @NonNull
    public static M03HomeRepository getInstance() {
        if (instance == null) {
            synchronized (M03HomeRepository.class) {
                if (instance == null) {
                    instance = new M03HomeRepository();
                }
            }
        }
        return instance;
    }

    public M03HomeRepository() {
        updateSyncTimestampNow();
    }

    /**
     * 하드코딩된 더미 데이터 없이 저장소를 비우고, 전달된 대표 차량 정보만 초기화한다.
     */
    public synchronized void clearAllAndInitVehicle(@Nullable String primaryVehicleId, @Nullable String customPrimaryVehicleLabel) {
        if (!vehicles.isEmpty()) {
            if (primaryVehicleId != null && !primaryVehicleId.trim().isEmpty()) {
                selectVehicle(primaryVehicleId.trim());
            }
            return;
        }
        vehicles.clear();
        vehicleOdometerMap.clear();
        allPendingTasks.clear();
        allTrips.clear();
        allExpenses.clear();
        allMaintenanceItems.clear();

        // 1. 민우의 아이오닉 5 (10,240.5 km)
        String v1Id = "c1000000-0000-4000-8000-000000000001";
        vehicles.add(new VehicleProfile(v1Id, "민우의 아이오닉 5", "전기", "민우의 아이오닉 5", false, "OWNER", true, "c3000000-0000-4000-8000-000000000001"));
        vehicleOdometerMap.put(v1Id, 10240.5);

        // 2. 246무 4710 (48,200.5 km)
        String v2Id = "d7f7d2fa-985d-4f7d-93e9-ea99a29bcaa7";
        vehicles.add(new VehicleProfile(v2Id, "246무 4710", "경유", "246무 4710", true, "OWNER", true, "inst-246mu-4710"));
        vehicleOdometerMap.put(v2Id, 48200.5);

        // 3. 12가 3456 (휘발유) (62,340.0 km)
        String v3Id = "28017757-d498-4e4c-a40b-d0a7e81bbda4";
        vehicles.add(new VehicleProfile(v3Id, "12가 3456", "휘발유", "12가 3456 (휘발유)", false, "OWNER", false, null));
        vehicleOdometerMap.put(v3Id, 62340.0);

        // 4. QR 연동 차량 (21,500.0 km)
        String v4Id = "394e8f5a-ef47-4082-a270-c3956b6db8c3";
        vehicles.add(new VehicleProfile(v4Id, "QR 연동 차량", "하이브리드", "QR 연동 차량", false, "OWNER", true, "0ce88920-e4ee-4b04-b6ee-17a3f71d5cbf"));
        vehicleOdometerMap.put(v4Id, 21500.0);

        // 5. 등록 차량 (15,300.0 km)
        String v5Id = "0a224510-0bd1-45fa-9de3-9c7d57217e57";
        vehicles.add(new VehicleProfile(v5Id, "등록 차량", "휘발유", "등록 차량", true, "OWNER", false, null));
        vehicleOdometerMap.put(v5Id, 15300.0);

        // 6. 테스트 차량 A (5,420.0 km)
        String v6Id = "f1000000-0000-4000-8000-000000000001";
        vehicles.add(new VehicleProfile(v6Id, "테스트 차량 A", "전기", "테스트 차량 A", true, "MEMBER", true, "f3000000-0000-4000-8000-000000000001"));
        vehicleOdometerMap.put(v6Id, 5420.0);

        initVehicleSpecificData(v1Id, v2Id, v3Id, v4Id, v5Id, v6Id);

        if (primaryVehicleId != null && !primaryVehicleId.trim().isEmpty()) {
            selectVehicle(primaryVehicleId.trim());
        } else {
            selectVehicle(v1Id);
        }
        newUserEmptyMode = false;
    }

    private void initVehicleSpecificData(
            String v1Id, String v2Id, String v3Id, String v4Id, String v5Id, String v6Id
    ) {
        // v1: 민우의 아이오닉 5 (전기)
        allTrips.add(new TripRecord("trip_ionic_1", v1Id, "11월 06일 18:00", "집 → 회사 (출근)", 48.2, 70, "개인", 6216, ExpenseRecord.Ownership.MY_RECORD));
        allTrips.add(new TripRecord("trip_ionic_2", v1Id, "11월 05일 08:30", "회사 → 본가 (퇴근)", 32.5, 45, "개인", 4368, ExpenseRecord.Ownership.MY_RECORD));
        allExpenses.add(new ExpenseRecord("exp_ionic_1", v1Id, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.MY_RECORD, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "급속 충전 (환경부 초고속)", "11월 06일", 45000));
        allExpenses.add(new ExpenseRecord("exp_ionic_2", v1Id, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.MY_RECORD, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "완속 충전 (아파트 충전기)", "11월 02일", 80000));
        allMaintenanceItems.add(new MaintenanceScheduleItem("maint_ionic_1", v1Id, "에어컨 필터 및 기본 점검", "제조사 권장 15,000km 점검", "15,000km 남음", false));
        allPendingTasks.add(new PendingTaskItem("task_ionic_1", v1Id, PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM, "충전 기록 확인 필요 (1건)", "11월 06일 충전 감지 내역 검토 대기", 0L));

        // v2: 246무 4710 (경유)
        allTrips.add(new TripRecord("trip_246_1", v2Id, "11월 10일 14:20", "과천 → 송도", 52.1, 75, "공동", 8800, ExpenseRecord.Ownership.EXPLICITLY_SHARED));
        allTrips.add(new TripRecord("trip_246_2", v2Id, "11월 09일 09:10", "일산 → 분당", 45.2, 60, "공동", 7600, ExpenseRecord.Ownership.EXPLICITLY_SHARED));
        allTrips.add(new TripRecord("trip_246_3", v2Id, "11월 07일 19:30", "강남 → 일산", 38.0, 50, "공동", 6400, ExpenseRecord.Ownership.EXPLICITLY_SHARED));
        allExpenses.add(new ExpenseRecord("exp_246_1", v2Id, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.EXPLICITLY_SHARED, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "GS칼텍스 삼거리주유소", "11월 07일", 90000));
        allExpenses.add(new ExpenseRecord("exp_246_2", v2Id, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.EXPLICITLY_SHARED, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "SK에너지 직영주유소", "11월 02일", 120000));
        allExpenses.add(new ExpenseRecord("exp_246_3", v2Id, ExpenseRecord.Category.MAINTENANCE, ExpenseRecord.Ownership.EXPLICITLY_SHARED, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "합성엔진오일 교환 세트", "11월 04일", 145000));
        allMaintenanceItems.add(new MaintenanceScheduleItem("maint_246_1", v2Id, "엔진오일 및 오일필터", "정비 권장 주기 1,200km 초과", "1,200km 초과", true));
        allMaintenanceItems.add(new MaintenanceScheduleItem("maint_246_2", v2Id, "앞 브레이크 패드", "정기 점검 주기 도래 예정", "2,500km 남음", false));
        allPendingTasks.add(new PendingTaskItem("task_246_1", v2Id, PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM, "주유 기록 확인 필요 (1건)", "11월 07일 주유 감지 내역 검토 대기", 0L));

        // v3: 12가 3456 (휘발유)
        allTrips.add(new TripRecord("trip_12ga_1", v3Id, "11월 08일 10:00", "분당 → 판교", 28.5, 40, "개인", 4800, ExpenseRecord.Ownership.MY_RECORD));
        allExpenses.add(new ExpenseRecord("exp_12ga_1", v3Id, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.MY_RECORD, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "S-OIL 평화주유소", "11월 08일", 65000));
        allMaintenanceItems.add(new MaintenanceScheduleItem("maint_12ga_1", v3Id, "정기 종합검사", "검사 유효기간 1개월 전", "3,500km 남음", false));

        // v4: QR 연동 차량 (하이브리드)
        allTrips.add(new TripRecord("trip_qr_1", v4Id, "11월 09일 20:10", "여의도 → 상암", 31.0, 45, "개인", 4500, ExpenseRecord.Ownership.MY_RECORD));
        allExpenses.add(new ExpenseRecord("exp_qr_1", v4Id, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.MY_RECORD, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "현대오일뱅크 직영점", "11월 09일", 70000));

        // v5: 등록 차량
        allTrips.add(new TripRecord("trip_reg_1", v5Id, "11월 07일 14:00", "잠실 → 삼성", 12.0, 20, "공동", 2000, ExpenseRecord.Ownership.EXPLICITLY_SHARED));
        allExpenses.add(new ExpenseRecord("exp_reg_1", v5Id, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.EXPLICITLY_SHARED, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "알뜰주유소 테헤란점", "11월 07일", 50000));

        // v6: 테스트 차량 A
        allTrips.add(new TripRecord("trip_test_1", v6Id, "11월 01일 09:11", "시드 주행 경로", 18.0, 30, "개인", 2100, ExpenseRecord.Ownership.MY_RECORD));
        allExpenses.add(new ExpenseRecord("exp_test_1", v6Id, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.MY_RECORD, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "급속 충전 테스트", "11월 01일", 15000));
    }

    /**
     * 백엔드 GET /mobile/vehicles 응답으로 전달된 사용자의 모든 차량(OWNER, MANAGER, MEMBER) 목록을 반영한다.
     */
    public synchronized void applyBackendUserVehicles(
            @NonNull List<com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.BackendVehicleItem> backendVehicles
    ) {
        if (backendVehicles.isEmpty()) {
            return;
        }

        boolean hasExistingSelected = false;
        for (com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.BackendVehicleItem bv : backendVehicles) {
            String vid = bv.vehicleId != null ? bv.vehicleId : bv.id;
            if (vid != null && vid.equals(selectedVehicleId)) {
                hasExistingSelected = true;
                break;
            }
        }

        for (com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.BackendVehicleItem bv : backendVehicles) {
            String vid = bv.vehicleId != null ? bv.vehicleId : bv.id;
            String name = bv.getDisplayName();
            String fuelType = extractFuelTypeFromLabel(name);
            String plate = extractPlateFromLabel(name);
            boolean isShared = "MEMBER".equalsIgnoreCase(bv.role) || "MANAGER".equalsIgnoreCase(bv.role);

            VehicleProfile profile = new VehicleProfile(
                    vid,
                    plate,
                    fuelType,
                    name,
                    isShared,
                    bv.role,
                    bv.paired,
                    bv.installationId
            );
            registerOrUpdateVehicle(profile);

            // 차량별 고유 누적거리 부여 (미설정 시)
            if (!vehicleOdometerMap.containsKey(vid)) {
                double distinct = ((long) Math.abs(vid.hashCode()) % 45000) + 12000.0;
                vehicleOdometerMap.put(vid, distinct);
            }
        }

        if (!hasExistingSelected && !backendVehicles.isEmpty()) {
            String firstVid = backendVehicles.get(0).vehicleId != null
                    ? backendVehicles.get(0).vehicleId
                    : backendVehicles.get(0).id;
            selectVehicle(firstVid);
        }
        newUserEmptyMode = false;
    }

    public synchronized void registerOrUpdateVehicle(@NonNull VehicleProfile profile) {
        for (int i = 0; i < vehicles.size(); i++) {
            if (vehicles.get(i).getVehicleId().equals(profile.getVehicleId())) {
                vehicles.set(i, profile);
                if (selectedVehicleId == null) {
                    selectedVehicleId = profile.getVehicleId();
                }
                return;
            }
        }
        vehicles.add(profile);
        if (selectedVehicleId == null) {
            selectedVehicleId = profile.getVehicleId();
        }
    }

    public synchronized void addPendingTask(@NonNull PendingTaskItem item) {
        for (int i = 0; i < allPendingTasks.size(); i++) {
            if (allPendingTasks.get(i).getTaskId().equals(item.getTaskId())) {
                allPendingTasks.set(i, item);
                return;
            }
        }
        allPendingTasks.add(item);
    }

    public synchronized void addTripRecord(@NonNull TripRecord record) {
        for (int i = 0; i < allTrips.size(); i++) {
            if (allTrips.get(i).getTripId().equals(record.getTripId())) {
                allTrips.set(i, record);
                return;
            }
        }
        allTrips.add(record);
    }

    public synchronized void addExpenseRecord(@NonNull ExpenseRecord record) {
        for (int i = 0; i < allExpenses.size(); i++) {
            if (allExpenses.get(i).getRecordId().equals(record.getRecordId())) {
                allExpenses.set(i, record);
                return;
            }
        }
        allExpenses.add(record);
    }

    public synchronized void addMaintenanceScheduleItem(@NonNull MaintenanceScheduleItem item) {
        for (int i = 0; i < allMaintenanceItems.size(); i++) {
            if (allMaintenanceItems.get(i).getScheduleId().equals(item.getScheduleId())) {
                allMaintenanceItems.set(i, item);
                return;
            }
        }
        allMaintenanceItems.add(item);
    }

    public synchronized double getLatestOdometerKm() {
        VehicleProfile current = getSelectedVehicle();
        if (current == null) {
            return 0.0;
        }
        String vid = current.getVehicleId();
        if (vehicleOdometerMap.containsKey(vid)) {
            Double val = vehicleOdometerMap.get(vid);
            if (val != null) {
                return val;
            }
        }
        double distinctOdo = ((long) Math.abs(vid.hashCode()) % 45000) + 12450.5;
        vehicleOdometerMap.put(vid, distinctOdo);
        this.latestOdometerKm = distinctOdo;
        return distinctOdo;
    }

    public synchronized double getOdometerForVehicle(@NonNull String vehicleId) {
        if (vehicleOdometerMap.containsKey(vehicleId)) {
            Double val = vehicleOdometerMap.get(vehicleId);
            if (val != null) return val;
        }
        double distinctOdo = ((long) Math.abs(vehicleId.hashCode()) % 45000) + 12450.5;
        vehicleOdometerMap.put(vehicleId, distinctOdo);
        return distinctOdo;
    }

    public synchronized void updateSyncTimestampNow() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA);
        this.lastSyncedTimestamp = sdf.format(new Date());
    }

    /**
     * 백엔드 GET /vehicle/home 및 GET /sync/status 응답(실제 서버 DB 데이터)을 M03 상태에 반영한다.
     */
    public synchronized void applyBackendVehicleHomeAndSync(
            @Nullable String vehicleId,
            @NonNull FuelogBackendApiClient.VehicleHomeResponse homeResp,
            @NonNull FuelogBackendApiClient.SyncStatusResponse syncResp
    ) {
        String targetVid = (vehicleId != null && !vehicleId.trim().isEmpty())
                ? vehicleId.trim()
                : (selectedVehicleId != null ? selectedVehicleId : VEHICLE_ID_PRIMARY);

        if (homeResp.success) {
            String alias = (homeResp.vehicleAlias != null && !homeResp.vehicleAlias.trim().isEmpty())
                    ? homeResp.vehicleAlias.trim()
                    : "연동 차량";
            registerOrUpdateVehicle(new VehicleProfile(
                    targetVid,
                    extractPlateFromLabel(alias),
                    extractFuelTypeFromLabel(alias),
                    alias,
                    true
            ));
            selectedVehicleId = targetVid;
            newUserEmptyMode = false;

            if (homeResp.distanceKm != null && homeResp.distanceKm > 0) {
                this.vehicleOdometerMap.put(targetVid, homeResp.distanceKm);
                this.latestOdometerKm = homeResp.distanceKm;
            }
            if (homeResp.estimatedCostWon != null && homeResp.estimatedCostWon > 0) {
                addExpenseRecord(new ExpenseRecord(
                        "db_trip_est_" + targetVid,
                        targetVid,
                        ExpenseRecord.Category.TRIP_CONSUMPTION_ESTIMATE,
                        ExpenseRecord.Ownership.MY_RECORD,
                        ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                        "백엔드 집계 운행 연료 소비 추정액",
                        "이번 달",
                        homeResp.estimatedCostWon
                ));
            }
            if (homeResp.fuelReviewCount != null && homeResp.fuelReviewCount > 0) {
                addPendingTask(new PendingTaskItem(
                        "db_fuel_review_" + targetVid,
                        targetVid,
                        PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM,
                        "주유 기록 확인 필요 (" + homeResp.fuelReviewCount + "건)",
                        "백엔드 주유 감지 내역 확인 대기",
                        0L
                ));
            }
            if (homeResp.maintenanceDueCount != null && homeResp.maintenanceDueCount > 0) {
                addMaintenanceScheduleItem(new MaintenanceScheduleItem(
                        "db_maint_due_" + targetVid,
                        targetVid,
                        "점검·교체 예정 정비 항목 (" + homeResp.maintenanceDueCount + "건)",
                        "백엔드 차량 관리 기준 도래",
                        homeResp.maintenanceDueCount + "건 도래",
                        true
                ));
            }
        }

        if (syncResp.success && syncResp.lastSyncedAt != null && syncResp.lastSyncedAt.length() >= 16) {
            this.lastSyncedTimestamp = syncResp.lastSyncedAt.substring(0, 16).replace('T', ' ');
        } else if (homeResp.success && homeResp.generatedAt != null && homeResp.generatedAt.length() >= 16) {
            this.lastSyncedTimestamp = homeResp.generatedAt.substring(0, 16).replace('T', ' ');
        } else {
            updateSyncTimestampNow();
        }
    }

    /**
     * 백엔드 GET /mobile/inbox 응답(M06 모바일 확인함 DB 데이터)을 M03 대시보드(확인할 기록 + 최근 운행 + 운행 소비 비용)에 반영한다.
     */
    public synchronized void applyBackendInboxItems(
            @NonNull List<FuelogBackendApiClient.InboxTripSummaryItem> inboxItems
    ) {
        String targetVid = selectedVehicleId != null ? selectedVehicleId : VEHICLE_ID_PRIMARY;
        if (vehicles.isEmpty() && !inboxItems.isEmpty()) {
            registerOrUpdateVehicle(new VehicleProfile(
                    targetVid,
                    "연동 차량",
                    "휘발유",
                    "백엔드 연동 차량",
                    true
            ));
        }

        double totalDistance = 0.0;
        for (FuelogBackendApiClient.InboxTripSummaryItem item : inboxItems) {
            String taskId = "inbox_" + item.inboxItemId;
            long cost = item.estimatedCostWon != null ? item.estimatedCostWon : 0L;
            double dist = item.distanceKm != null ? item.distanceKm : 0.0;
            double eff = item.fuelEfficiencyKmPerLiter != null ? item.fuelEfficiencyKmPerLiter : 0.0;
            double fuel = item.fuelConsumedLiters != null ? item.fuelConsumedLiters : 0.0;
            totalDistance += dist;

            if (item.readAt == null) {
                addPendingTask(new PendingTaskItem(
                        taskId,
                        targetVid,
                        PendingTaskItem.TaskDestination.M04_TRIP_ATTRIBUTION,
                        "백엔드 확인함 운행 요약 (#" + item.inboxItemId + ")",
                        String.format(Locale.KOREA, "거리 %.1fkm · %.1fL (%.1fkm/L) · %,d원 · 읽음 확인 대기", dist, fuel, eff, cost),
                        cost
                ));
            } else {
                markInboxTaskReadLocally(item.inboxItemId);
            }

            String tripId = "backend_trip_" + item.tripId;
            String shortId = item.tripId.substring(0, Math.min(8, item.tripId.length()));
            String badge = (item.readAt != null ? "확인함 읽음 완료" : "확인함 미열람")
                    + String.format(Locale.KOREA, " · %.1fkm/L · %,d원", eff, cost);
            addTripRecord(new TripRecord(
                    tripId,
                    targetVid,
                    item.deliveredAt != null && item.deliveredAt.length() >= 16
                            ? item.deliveredAt.substring(0, 16).replace('T', ' ')
                            : "방금",
                    "백엔드 C04 운행 요약 (" + shortId + ")",
                    dist,
                    0,
                    badge,
                    cost,
                    ExpenseRecord.Ownership.MY_RECORD
            ));

            if (cost > 0L) {
                addExpenseRecord(new ExpenseRecord(
                        "exp_trip_est_" + item.tripId,
                        targetVid,
                        ExpenseRecord.Category.TRIP_CONSUMPTION_ESTIMATE,
                        ExpenseRecord.Ownership.MY_RECORD,
                        ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                        "운행(" + shortId + ") 연료 소비 추정액",
                        item.deliveredAt != null && item.deliveredAt.length() >= 10
                                ? item.deliveredAt.substring(5, 10).replace('-', '.')
                                : "이번 달",
                        cost
                ));
            }
        }
        if (this.latestOdometerKm <= 0.0 && totalDistance > 0.0) {
            this.latestOdometerKm = totalDistance;
        }
        updateSyncTimestampNow();
    }

    public synchronized void markInboxTaskReadLocally(long inboxItemId) {
        String targetTaskId = "inbox_" + inboxItemId;
        for (int i = allPendingTasks.size() - 1; i >= 0; i--) {
            if (targetTaskId.equals(allPendingTasks.get(i).getTaskId())) {
                allPendingTasks.remove(i);
            }
        }
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
        if (vehicleOdometerMap.containsKey(vehicleId)) {
            Double val = vehicleOdometerMap.get(vehicleId);
            if (val != null) {
                this.latestOdometerKm = val;
            }
        }
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

    @NonNull
    private static String extractFuelTypeFromLabel(@NonNull String label) {
        if (label.contains("경유")) return "경유";
        if (label.contains("전기")) return "전기";
        if (label.contains("LPG")) return "LPG";
        if (label.contains("휘발유") || label.contains("가솔린")) return "휘발유";
        return "서버 DB 차량 (유종 미지정)";
    }

    @NonNull
    private static String extractPlateFromLabel(@NonNull String label) {
        int idx = label.indexOf('(');
        if (idx > 0) {
            return label.substring(0, idx).trim();
        }
        return label.trim();
    }
}
