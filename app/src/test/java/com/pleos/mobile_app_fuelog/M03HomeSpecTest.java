package com.pleos.mobile_app_fuelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pleos.mobile_app_fuelog.data.home.ExpenseRecord;
import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.home.MaintenanceScheduleItem;
import com.pleos.mobile_app_fuelog.data.home.MonthlyExpenseSummary;
import com.pleos.mobile_app_fuelog.data.home.PendingTaskItem;
import com.pleos.mobile_app_fuelog.data.home.TripRecord;
import com.pleos.mobile_app_fuelog.data.home.VehicleProfile;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * M03. 홈 요구사항(FR-M03-1 ~ FR-M03-3 및 예외/완료) 단위 테스트 (하드코딩 시드 없이 동적 DB/API 레코드 주입 검증).
 */
public class M03HomeSpecTest {

    private M03HomeRepository repository;

    @Before
    public void setUp() {
        repository = new M03HomeRepository();
        // 프로덕션 초기 상태에는 하드코딩된 데이터가 없어야 함
        assertTrue(repository.getVehicles().isEmpty());

        // 테스트용 차량 2대 및 DB 레코드 주입
        repository.registerOrUpdateVehicle(new VehicleProfile(
                M03HomeRepository.VEHICLE_ID_PRIMARY,
                "12가 3456",
                "휘발유",
                "12가 3456 (휘발유)",
                true
        ));
        repository.registerOrUpdateVehicle(new VehicleProfile(
                M03HomeRepository.VEHICLE_ID_SECONDARY,
                "88하 9012",
                "경유",
                "88하 9012 (경유)",
                false
        ));

        repository.addPendingTask(new PendingTaskItem("t1", M03HomeRepository.VEHICLE_ID_PRIMARY, PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM, "주유 확인", "상세", 68000L));
        repository.addPendingTask(new PendingTaskItem("t2", M03HomeRepository.VEHICLE_ID_PRIMARY, PendingTaskItem.TaskDestination.M04_TRIP_ATTRIBUTION, "운행 귀속", "상세", 0L));
        repository.addPendingTask(new PendingTaskItem("t3", M03HomeRepository.VEHICLE_ID_PRIMARY, PendingTaskItem.TaskDestination.M09_SETTLEMENT_CHANGE, "정산 변경", "상세", 45000L));
        repository.addPendingTask(new PendingTaskItem("t4", M03HomeRepository.VEHICLE_ID_PRIMARY, PendingTaskItem.TaskDestination.M13_SHARE_MANAGEMENT, "공동 관리", "상세", 0L));

        repository.addExpenseRecord(new ExpenseRecord("e1", M03HomeRepository.VEHICLE_ID_PRIMARY, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.MY_RECORD, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "주유", "11.01", 72000L));
        repository.addExpenseRecord(new ExpenseRecord("e2", M03HomeRepository.VEHICLE_ID_PRIMARY, ExpenseRecord.Category.MAINTENANCE, ExpenseRecord.Ownership.EXPLICITLY_SHARED, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "정비", "11.02", 58000L));
        repository.addExpenseRecord(new ExpenseRecord("e3", M03HomeRepository.VEHICLE_ID_PRIMARY, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.MY_RECORD, ExpenseRecord.ConfirmationState.UNCONFIRMED_ESTIMATE, "미확인 주유", "11.03", 68000L));
        repository.addExpenseRecord(new ExpenseRecord("e4", M03HomeRepository.VEHICLE_ID_PRIMARY, ExpenseRecord.Category.TRIP_CONSUMPTION_ESTIMATE, ExpenseRecord.Ownership.MY_RECORD, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "운행 소비", "11.03", 4400L));
        repository.addExpenseRecord(new ExpenseRecord("e5", M03HomeRepository.VEHICLE_ID_PRIMARY, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.UNSHARED_OTHER, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "타인 비공유", "11.02", 99000L));

        // 차량 2 데이터
        repository.addPendingTask(new PendingTaskItem("sec_t1", M03HomeRepository.VEHICLE_ID_SECONDARY, PendingTaskItem.TaskDestination.M07_FUEL_CONFIRM, "세컨드카 주유", "상세", 54000L));
        repository.addTripRecord(new TripRecord("sec_tr1", M03HomeRepository.VEHICLE_ID_SECONDARY, "11.01", "광교 -> 송도", 42.5, 52, "본인", 4800L, ExpenseRecord.Ownership.MY_RECORD));
        repository.addExpenseRecord(new ExpenseRecord("sec_e1", M03HomeRepository.VEHICLE_ID_SECONDARY, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.MY_RECORD, ExpenseRecord.ConfirmationState.USER_CONFIRMED, "경유", "11.01", 51000L));
        repository.addExpenseRecord(new ExpenseRecord("sec_e2", M03HomeRepository.VEHICLE_ID_SECONDARY, ExpenseRecord.Category.FUEL, ExpenseRecord.Ownership.MY_RECORD, ExpenseRecord.ConfirmationState.UNCONFIRMED_ESTIMATE, "미확인 경유", "11.02", 54000L));
        repository.addMaintenanceScheduleItem(new MaintenanceScheduleItem("sec_m1", M03HomeRepository.VEHICLE_ID_SECONDARY, "요소수", "점검", "450km", true));
    }

    @Test
    public void frM03_1_pendingTasksRouteToM07_M04_M09_M13() {
        List<PendingTaskItem> tasks = repository.getPendingTasksForSelectedVehicle();
        assertEquals(4, tasks.size());
        assertEquals("M07", tasks.get(0).getDestination().getScreenCode());
        assertEquals("M04", tasks.get(1).getDestination().getScreenCode());
        assertEquals("M09", tasks.get(2).getDestination().getScreenCode());
        assertEquals("M13", tasks.get(3).getDestination().getScreenCode());
    }

    @Test
    public void frM03_2_and_frM03_3_aggregatesOnlyAllowedScope_andSeparatesUnconfirmed_withoutDoubleCountingTripCost() {
        repository.setCurrentScopeMode(MonthlyExpenseSummary.ScopeMode.PERSONAL_AND_SHARED);
        MonthlyExpenseSummary combined = repository.getMonthlyExpenseSummaryForSelectedVehicle();

        assertEquals(130000L, combined.getConfirmedTotalWon());
        assertEquals(72000L, combined.getConfirmedFuelWon());
        assertEquals(58000L, combined.getConfirmedMaintenanceWon());
        assertEquals(2, combined.getConfirmedCount());
        assertEquals(1, combined.getUnconfirmedCount());
        assertEquals(68000L, combined.getUnconfirmedEstimatedWon());
        assertEquals(4400L, combined.getExcludedTripConsumptionWon());

        repository.setCurrentScopeMode(MonthlyExpenseSummary.ScopeMode.PERSONAL_ONLY);
        MonthlyExpenseSummary personalOnly = repository.getMonthlyExpenseSummaryForSelectedVehicle();

        assertEquals(72000L, personalOnly.getConfirmedTotalWon());
        assertEquals(72000L, personalOnly.getConfirmedFuelWon());
        assertEquals(0L, personalOnly.getConfirmedMaintenanceWon());
        assertEquals(1, personalOnly.getConfirmedCount());
    }

    @Test
    public void exceptionAndCompletion_switchingVehicleIsolatesData_andSupportsEmptyAndOfflineStates() {
        repository.selectVehicle(M03HomeRepository.VEHICLE_ID_SECONDARY);

        List<PendingTaskItem> secTasks = repository.getPendingTasksForSelectedVehicle();
        assertEquals(1, secTasks.size());
        assertEquals(M03HomeRepository.VEHICLE_ID_SECONDARY, secTasks.get(0).getVehicleId());

        List<TripRecord> secTrips = repository.getRecentTripsForSelectedVehicle();
        assertEquals(1, secTrips.size());
        assertEquals(M03HomeRepository.VEHICLE_ID_SECONDARY, secTrips.get(0).getVehicleId());

        MonthlyExpenseSummary secSummary = repository.getMonthlyExpenseSummaryForSelectedVehicle();
        assertEquals(51000L, secSummary.getConfirmedTotalWon());
        assertEquals(1, secSummary.getUnconfirmedCount());
        assertEquals(54000L, secSummary.getUnconfirmedEstimatedWon());

        List<MaintenanceScheduleItem> secMaint = repository.getMaintenanceSchedulesForSelectedVehicle();
        assertEquals(1, secMaint.size());
        assertEquals(M03HomeRepository.VEHICLE_ID_SECONDARY, secMaint.get(0).getVehicleId());

        repository.setNewUserEmptyMode(true);
        assertNull(repository.getSelectedVehicle());
        assertTrue(repository.getPendingTasksForSelectedVehicle().isEmpty());

        repository.setOfflineMode(true);
        assertTrue(repository.isOfflineMode());
        assertNotNull(repository.getLastSyncedTimestamp());
        assertFalse(repository.getLastSyncedTimestamp().isEmpty());
    }
}
