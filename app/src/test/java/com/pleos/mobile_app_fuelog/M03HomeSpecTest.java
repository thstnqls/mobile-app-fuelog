package com.pleos.mobile_app_fuelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.home.MaintenanceScheduleItem;
import com.pleos.mobile_app_fuelog.data.home.MonthlyExpenseSummary;
import com.pleos.mobile_app_fuelog.data.home.PendingTaskItem;
import com.pleos.mobile_app_fuelog.data.home.TripRecord;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * M03. 홈 요구사항(FR-M03-1 ~ FR-M03-3 및 예외/완료) 단위 테스트.
 */
public class M03HomeSpecTest {

    private M03HomeRepository repository;

    @Before
    public void setUp() {
        repository = new M03HomeRepository();
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
        // 1) 기본 범위: 개인 + 차량 공동(명시적 공유 포함)
        // - 본인 확인 주유 72,000 + 명시적 공유 확인 정비 58,000 = 130,000원
        // - 비공유 타인 주유(99,000원)는 제외되어야 함 (FR-M03-2)
        // - 미확인 추정 주유(68,000원, 1건)는 별도 집계되어야 함 (FR-M03-3)
        // - 운행 소비 추정 비용(4,400원)은 월 지출 합계에 중복 합산되면 안 됨 (FR-M03-3)
        repository.setCurrentScopeMode(MonthlyExpenseSummary.ScopeMode.PERSONAL_AND_SHARED);
        MonthlyExpenseSummary combined = repository.getMonthlyExpenseSummaryForSelectedVehicle();

        assertEquals(130000L, combined.getConfirmedTotalWon());
        assertEquals(72000L, combined.getConfirmedFuelWon());
        assertEquals(58000L, combined.getConfirmedMaintenanceWon());
        assertEquals(2, combined.getConfirmedCount());
        assertEquals(1, combined.getUnconfirmedCount());
        assertEquals(68000L, combined.getUnconfirmedEstimatedWon());
        assertEquals(4400L, combined.getExcludedTripConsumptionWon());

        // 2) 개인 지출만 보기 (본인 기록만 집계, 명시적 공유 58,000원 제외)
        repository.setCurrentScopeMode(MonthlyExpenseSummary.ScopeMode.PERSONAL_ONLY);
        MonthlyExpenseSummary personalOnly = repository.getMonthlyExpenseSummaryForSelectedVehicle();

        assertEquals(72000L, personalOnly.getConfirmedTotalWon());
        assertEquals(72000L, personalOnly.getConfirmedFuelWon());
        assertEquals(0L, personalOnly.getConfirmedMaintenanceWon());
        assertEquals(1, personalOnly.getConfirmedCount());
    }

    @Test
    public void exceptionAndCompletion_switchingVehicleIsolatesData_andSupportsEmptyAndOfflineStates() {
        // 차량 2(88하 9012 경유)로 전환 시 차량 1의 데이터가 전혀 섞이지 않아야 함
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

        // 신규 사용자 빈 상태 확인
        repository.setNewUserEmptyMode(true);
        assertNull(repository.getSelectedVehicle());
        assertTrue(repository.getPendingTasksForSelectedVehicle().isEmpty());

        // 오프라인 모드 및 마지막 갱신 시각 확인
        repository.setOfflineMode(true);
        assertTrue(repository.isOfflineMode());
        assertNotNull(repository.getLastSyncedTimestamp());
        assertFalse(repository.getLastSyncedTimestamp().isEmpty());
    }
}
