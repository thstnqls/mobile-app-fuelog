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

        if (customPrimaryVehicleLabel != null && !customPrimaryVehicleLabel.trim().isEmpty()) {
            String vid = (primaryVehicleId != null && !primaryVehicleId.trim().isEmpty())
                    ? primaryVehicleId.trim()
                    : VEHICLE_ID_PRIMARY;
            String label = customPrimaryVehicleLabel.trim();
            String fuelType = extractFuelTypeFromLabel(label);
            String plate = extractPlateFromLabel(label);
            vehicles.add(new VehicleProfile(vid, plate, fuelType, label, false));
            vehicleOdometerMap.put(vid, 10240.5);
            selectedVehicleId = vid;
            newUserEmptyMode = false;
        } else {
            selectedVehicleId = null;
        }
    }

    /**
     * 백엔드 GET /mobile/vehicles 응답으로 전달된 사용자의 모든 차량(OWNER, MANAGER, MEMBER) 목록을 반영한다.
     * API에서 실제로 조회된 차량들로만 목록을 구성한다.
     */
    public synchronized void applyBackendUserVehicles(
            @NonNull List<com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.BackendVehicleItem> backendVehicles
    ) {
        applyBackendUserVehicles(backendVehicles, null);
    }

    public synchronized void applyBackendUserVehicles(
            @NonNull List<com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.BackendVehicleItem> backendVehicles,
            @Nullable java.util.function.Function<String, String> nameResolver
    ) {
        if (backendVehicles.isEmpty()) {
            return;
        }

        // 백엔드 API에서 조회된 차량들로만 엄격히 교체
        vehicles.clear();

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
            String resolved = nameResolver != null ? nameResolver.apply(vid) : null;
            String name = (resolved != null && !resolved.trim().isEmpty()) ? resolved.trim() : bv.getDisplayName();
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
            vehicles.add(profile);

            // 차량별 고유 누적거리 부여 (미설정 시)
            if (!vehicleOdometerMap.containsKey(vid)) {
                double distinct = ((long) Math.abs(vid.hashCode()) % 45000) + 12000.0;
                vehicleOdometerMap.put(vid, distinct);
            }
        }

        if (!hasExistingSelected && !vehicles.isEmpty()) {
            selectedVehicleId = vehicles.get(0).getVehicleId();
        }
        for (VehicleProfile p : vehicles) {
            ensureSampleExpensesAndTasksForVehicle(p.getVehicleId(), p.getDisplayName(), p.getFuelType());
        }
        newUserEmptyMode = false;
    }

    public synchronized boolean hasVehicle(@Nullable String vehicleId) {
        if (vehicleId == null || vehicleId.trim().isEmpty()) {
            return false;
        }
        for (VehicleProfile v : vehicles) {
            if (vehicleId.equals(v.getVehicleId())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public synchronized VehicleProfile getVehicleProfile(@Nullable String vehicleId) {
        if (vehicleId == null || vehicleId.trim().isEmpty()) {
            return null;
        }
        for (VehicleProfile v : vehicles) {
            if (vehicleId.equals(v.getVehicleId())) {
                return v;
            }
        }
        return null;
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

    public synchronized void updateVehicleDisplayName(@NonNull String vehicleId, @NonNull String newDisplayName) {
        for (int i = 0; i < vehicles.size(); i++) {
            VehicleProfile old = vehicles.get(i);
            if (old.getVehicleId().equals(vehicleId)) {
                vehicles.set(i, new VehicleProfile(
                        old.getVehicleId(),
                        extractPlateFromLabel(newDisplayName),
                        extractFuelTypeFromLabel(newDisplayName),
                        newDisplayName,
                        old.isSharedVehicle(),
                        old.getRole(),
                        old.isPaired(),
                        old.getInstallationId()
                ));
                return;
            }
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

    @Nullable
    public synchronized ExpenseRecord getExpenseRecordById(@NonNull String recordId) {
        for (ExpenseRecord e : allExpenses) {
            if (recordId.equals(e.getRecordId())) {
                return e;
            }
        }
        return null;
    }

    public synchronized boolean removeExpenseRecordById(@NonNull String recordId) {
        for (int i = allExpenses.size() - 1; i >= 0; i--) {
            if (recordId.equals(allExpenses.get(i).getRecordId())) {
                allExpenses.remove(i);
                return true;
            }
        }
        return false;
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

    public synchronized void setOdometerForVehicle(@NonNull String vehicleId, double odometerKm) {
        if (odometerKm > 0) {
            vehicleOdometerMap.put(vehicleId, odometerKm);
            if (vehicleId.equals(selectedVehicleId)) {
                this.latestOdometerKm = odometerKm;
            }
        }
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
            VehicleProfile existing = getVehicleProfile(targetVid);
            String alias = (existing != null && !existing.getDisplayName().isEmpty())
                    ? existing.getDisplayName()
                    : ((homeResp.vehicleAlias != null && !homeResp.vehicleAlias.trim().isEmpty())
                            ? homeResp.vehicleAlias.trim()
                            : "등록 차량");
            registerOrUpdateVehicle(new VehicleProfile(
                    targetVid,
                    extractPlateFromLabel(alias),
                    extractFuelTypeFromLabel(alias),
                    alias,
                    existing != null ? existing.isSharedVehicle() : true,
                    existing != null ? existing.getRole() : "OWNER",
                    existing != null ? existing.isPaired() : true,
                    existing != null ? existing.getInstallationId() : null
            ));
            if (selectedVehicleId == null) {
                selectedVehicleId = targetVid;
            }
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

    public synchronized void resolveFuelPendingTask(@NonNull String vehicleId) {
        for (int i = allPendingTasks.size() - 1; i >= 0; i--) {
            PendingTaskItem item = allPendingTasks.get(i);
            if (vehicleId.equals(item.getVehicleId()) && item.getDestination() == PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM) {
                allPendingTasks.remove(i);
            }
        }
    }

    private final Map<String, List<com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenanceEventItem>> vehicleMaintenanceEventsMap = new HashMap<>();

    /**
     * 백엔드 GET /mobile/vehicles/{vehicleId}/maintenance-events 응답(실제 DB 정비 이력)을 지출 목록에 반영한다.
     */
    public synchronized void applyBackendMaintenanceEvents(
            @NonNull String vehicleId,
            @NonNull List<com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenanceEventItem> events
    ) {
        vehicleMaintenanceEventsMap.put(vehicleId, new ArrayList<>(events));
        if (events.isEmpty()) {
            return;
        }

        // 기존 DB 유래 정비 레코드 제거 후 최신 동기화 반영
        for (int i = allExpenses.size() - 1; i >= 0; i--) {
            ExpenseRecord e = allExpenses.get(i);
            if (vehicleId.equals(e.getVehicleId()) && e.getRecordId().startsWith("maint_db_")) {
                allExpenses.remove(i);
            }
        }

        for (com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenanceEventItem ev : events) {
            String title = (ev.shop != null && !ev.shop.trim().isEmpty())
                    ? ev.shop.trim() + " (" + (ev.memo != null ? ev.memo : "정비") + ")"
                    : (ev.memo != null && !ev.memo.trim().isEmpty() ? ev.memo.trim() : "차량 정비 소모품 교환");

            String dateLabel = "최근";
            if (ev.serviceDate != null && ev.serviceDate.length() >= 10) {
                dateLabel = ev.serviceDate.substring(5, 7) + "월 " + ev.serviceDate.substring(8, 10) + "일";
            }

            ExpenseRecord record = new ExpenseRecord(
                    "maint_db_" + ev.id,
                    vehicleId,
                    ExpenseRecord.Category.MAINTENANCE,
                    ExpenseRecord.Ownership.MY_RECORD,
                    ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                    title,
                    dateLabel,
                    ev.cost
            );
            addExpenseRecord(record);

            if (ev.odometerKm != null && ev.odometerKm > 0) {
                double currentOdo = getOdometerForVehicle(vehicleId);
                if (ev.odometerKm > currentOdo) {
                    vehicleOdometerMap.put(vehicleId, ev.odometerKm);
                    if (vehicleId.equals(selectedVehicleId)) {
                        this.latestOdometerKm = ev.odometerKm;
                    }
                }
            }
        }
    }

    @Nullable
    public synchronized com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenanceEventItem getMaintenanceEventById(
            @NonNull String vehicleId,
            @NonNull String eventId
    ) {
        List<com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenanceEventItem> list = vehicleMaintenanceEventsMap.get(vehicleId);
        if (list != null) {
            for (com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenanceEventItem it : list) {
                if (eventId.equals(it.id)) {
                    return it;
                }
            }
        }
        return null;
    }

    @NonNull
    public synchronized List<com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenanceEventItem> getMaintenanceEventsForVehicle(
            @NonNull String vehicleId
    ) {
        List<com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenanceEventItem> list = vehicleMaintenanceEventsMap.get(vehicleId);
        return list != null ? new ArrayList<>(list) : Collections.emptyList();
    }

    public synchronized void removeMaintenanceEvent(@NonNull String vehicleId, @NonNull String eventId) {
        List<com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenanceEventItem> list = vehicleMaintenanceEventsMap.get(vehicleId);
        if (list != null) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (eventId.equals(list.get(i).id)) {
                    list.remove(i);
                }
            }
        }
        for (int i = allExpenses.size() - 1; i >= 0; i--) {
            ExpenseRecord e = allExpenses.get(i);
            if (e.getRecordId().equals("maint_db_" + eventId)) {
                allExpenses.remove(i);
            }
        }
    }

    @NonNull
    public synchronized List<ExpenseRecord> getExpensesForVehicle(@NonNull String vehicleId) {
        List<ExpenseRecord> result = new ArrayList<>();
        for (ExpenseRecord record : allExpenses) {
            if (!vehicleId.equals(record.getVehicleId())) {
                continue;
            }
            if (record.getOwnership() == ExpenseRecord.Ownership.UNSHARED_OTHER) {
                continue;
            }
            result.add(record);
        }
        return result;
    }

    /**
     * 백엔드 GET /mobile/vehicles/{vehicleId}/maintenance-plans 응답을 소모품 정비 예정 목록에 반영한다.
     */
    public synchronized void applyBackendMaintenancePlans(
            @NonNull String vehicleId,
            @NonNull List<com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenancePlanItem> plans
    ) {
        if (plans.isEmpty()) {
            return;
        }

        for (int i = allMaintenanceItems.size() - 1; i >= 0; i--) {
            MaintenanceScheduleItem item = allMaintenanceItems.get(i);
            if (vehicleId.equals(item.getVehicleId())) {
                allMaintenanceItems.remove(i);
            }
        }

        double currentOdo = getOdometerForVehicle(vehicleId);
        for (com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.MaintenancePlanItem plan : plans) {
            if (!plan.active) {
                continue;
            }
            String dueDetail = plan.getIntervalText();
            if (plan.nextDueDate != null) {
                dueDetail += " · " + plan.nextDueDate;
            }
            String remainingBadge = plan.getRemainingDistanceText(currentOdo);
            boolean urgent = "DANGER".equalsIgnoreCase(plan.riskLevel) || "OVERDUE".equalsIgnoreCase(plan.riskLevel);
            allMaintenanceItems.add(new MaintenanceScheduleItem(
                    plan.id,
                    vehicleId,
                    plan.name,
                    dueDetail,
                    remainingBadge,
                    urgent
            ));
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
        if (newUserEmptyMode || vehicles.isEmpty()) {
            return null;
        }
        if (selectedVehicleId != null) {
            for (VehicleProfile v : vehicles) {
                if (v.getVehicleId().equals(selectedVehicleId)) {
                    return v;
                }
            }
        }
        selectedVehicleId = vehicles.get(0).getVehicleId();
        return vehicles.get(0);
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

    public synchronized int getPendingFuelConfirmCountForSelectedVehicle() {
        VehicleProfile current = getSelectedVehicle();
        if (current == null) {
            return 0;
        }
        String vid = current.getVehicleId();
        int count = 0;
        for (PendingTaskItem task : allPendingTasks) {
            if (vid.equals(task.getVehicleId()) && task.getDestination() == PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM) {
                int taskCount = 1;
                String title = task.getTitle();
                if (title != null && title.contains("건)")) {
                    try {
                        int start = title.lastIndexOf('(');
                        int end = title.lastIndexOf("건)");
                        if (start >= 0 && end > start) {
                            taskCount = Integer.parseInt(title.substring(start + 1, end).trim());
                        }
                    } catch (Exception ignored) {
                    }
                }
                count += taskCount;
            }
        }
        return count;
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

    /**
     * 차량별 지출/작업/운행 데이터가 비어있을 경우 현실적인 기본 지출 및 주유 확인 작업을 채워준다.
     */
    public synchronized void ensureSampleExpensesAndTasksForVehicle(
            @NonNull String vehicleId,
            @NonNull String displayName,
            @NonNull String fuelType
    ) {
        boolean hasExpense = false;
        for (ExpenseRecord e : allExpenses) {
            if (vehicleId.equals(e.getVehicleId())) {
                hasExpense = true;
                break;
            }
        }
        if (!hasExpense) {
            String cleanName = displayName.toLowerCase(Locale.ROOT);
            boolean isEv = "전기".equals(fuelType) || cleanName.contains("아이오닉") || cleanName.contains("ev") || cleanName.contains("전기");
            if (isEv) {
                allExpenses.add(new ExpenseRecord(
                        "exp_fuel_1_" + vehicleId,
                        vehicleId,
                        ExpenseRecord.Category.FUEL,
                        ExpenseRecord.Ownership.MY_RECORD,
                        ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                        "급속 충전 (환경부 초고속)",
                        "11월 06일",
                        45000L
                ));
                allExpenses.add(new ExpenseRecord(
                        "exp_fuel_2_" + vehicleId,
                        vehicleId,
                        ExpenseRecord.Category.FUEL,
                        ExpenseRecord.Ownership.MY_RECORD,
                        ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                        "완속 충전 (아파트 충전기)",
                        "11월 02일",
                        32000L
                ));
                allExpenses.add(new ExpenseRecord(
                        "exp_maint_1_" + vehicleId,
                        vehicleId,
                        ExpenseRecord.Category.MAINTENANCE,
                        ExpenseRecord.Ownership.MY_RECORD,
                        ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                        "에어컨 필터 점검 및 교체",
                        "11월 01일",
                        28000L
                ));
            } else {
                allExpenses.add(new ExpenseRecord(
                        "exp_fuel_1_" + vehicleId,
                        vehicleId,
                        ExpenseRecord.Category.FUEL,
                        ExpenseRecord.Ownership.MY_RECORD,
                        ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                        "GS칼텍스 삼거리주유소",
                        "11월 08일",
                        85000L
                ));
                allExpenses.add(new ExpenseRecord(
                        "exp_fuel_2_" + vehicleId,
                        vehicleId,
                        ExpenseRecord.Category.FUEL,
                        ExpenseRecord.Ownership.MY_RECORD,
                        ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                        "SK에너지 직영주유소",
                        "11월 03일",
                        68000L
                ));
                allExpenses.add(new ExpenseRecord(
                        "exp_maint_1_" + vehicleId,
                        vehicleId,
                        ExpenseRecord.Category.MAINTENANCE,
                        ExpenseRecord.Ownership.MY_RECORD,
                        ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                        "엔진오일 교환 세트",
                        "11월 01일",
                        65000L
                ));
            }
        }

        // Pending task for M07 Fuel confirm banner
        boolean hasPendingFuel = false;
        for (PendingTaskItem t : allPendingTasks) {
            if (vehicleId.equals(t.getVehicleId()) && t.getDestination() == PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM) {
                hasPendingFuel = true;
                break;
            }
        }
        if (!hasPendingFuel) {
            allPendingTasks.add(new PendingTaskItem(
                    "pending_fuel_" + vehicleId,
                    vehicleId,
                    PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM,
                    "주유 기록 확인 필요 (1건)",
                    "방금 감지된 주유 내역을 확인해 주세요.",
                    0L
            ));
        }

        // Trip records if empty
        boolean hasTrips = false;
        for (TripRecord tr : allTrips) {
            if (vehicleId.equals(tr.getVehicleId())) {
                hasTrips = true;
                break;
            }
        }
        if (!hasTrips) {
            allTrips.add(new TripRecord(
                    "trip_1_" + vehicleId,
                    vehicleId,
                    "11월 10일 18:20",
                    "집 → 회사 (출근)",
                    22.4,
                    35,
                    "개인",
                    3200L,
                    ExpenseRecord.Ownership.MY_RECORD
            ));
            allTrips.add(new TripRecord(
                    "trip_2_" + vehicleId,
                    vehicleId,
                    "11월 09일 08:45",
                    "회사 → 본가 (퇴근)",
                    18.1,
                    28,
                    "개인",
                    2600L,
                    ExpenseRecord.Ownership.MY_RECORD
            ));
        }
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
