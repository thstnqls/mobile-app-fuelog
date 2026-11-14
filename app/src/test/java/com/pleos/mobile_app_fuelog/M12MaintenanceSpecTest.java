package com.pleos.mobile_app_fuelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.home.MaintenanceScheduleItem;
import com.pleos.mobile_app_fuelog.data.home.VehicleProfile;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;

import org.junit.Before;
import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * M12. 소모품 정비 예정 상세 및 교체 주기 설정 단위 테스트
 */
public class M12MaintenanceSpecTest {

    private M03HomeRepository repository;
    private static final String VEHICLE_ID = "veh-test-uuid";

    @Before
    public void setUp() {
        repository = new M03HomeRepository();
        repository.registerOrUpdateVehicle(new VehicleProfile(
                VEHICLE_ID,
                "12가 3456",
                "휘발유",
                "GV80 (12가 3456)",
                true
        ));
        repository.selectVehicle(VEHICLE_ID);
    }

    @Test
    public void testMaintenancePlanItemDDayCalculation() {
        LocalDate today = LocalDate.now();

        // 1. D-DAY
        FuelogBackendApiClient.MaintenancePlanItem itemToday = new FuelogBackendApiClient.MaintenancePlanItem(
                "p1", "엔진오일", true, true, 10000.0, 12, 1L,
                "2025-01-01", 30000.0, today.toString(), 40000.0, "ACTIVE", "NORMAL"
        );
        assertEquals("D-DAY", itemToday.getDDayText());

        // 2. Future (D-30)
        FuelogBackendApiClient.MaintenancePlanItem itemFuture = new FuelogBackendApiClient.MaintenancePlanItem(
                "p2", "에어컨 필터", true, true, 15000.0, 12, 1L,
                "2025-01-01", 30000.0, today.plusDays(30).toString(), 45000.0, "ACTIVE", "NORMAL"
        );
        assertEquals("D-30", itemFuture.getDDayText());

        // 3. Past (D+10 기한초과)
        FuelogBackendApiClient.MaintenancePlanItem itemPast = new FuelogBackendApiClient.MaintenancePlanItem(
                "p3", "브레이크 패드", true, true, 40000.0, 24, 1L,
                "2024-01-01", 20000.0, today.minusDays(10).toString(), 60000.0, "ACTIVE", "OVERDUE"
        );
        assertEquals("D+10 (기한초과)", itemPast.getDDayText());
    }

    @Test
    public void testMaintenancePlanItemDistanceAndProgress() {
        FuelogBackendApiClient.MaintenancePlanItem plan = new FuelogBackendApiClient.MaintenancePlanItem(
                "p-engine", "엔진오일", true, true, 10000.0, 12, 1L,
                "2026-01-01", 40000.0, "2027-01-01", 50000.0, "ACTIVE", "NORMAL"
        );

        // 현재 주행거리 47,000 km -> 3,000 km 남음, 진행률 약 70%
        assertEquals("3,000 km 남음", plan.getRemainingDistanceText(47000.0));
        assertEquals(70, plan.getProgressPercent(47000.0));

        // 현재 주행거리 51,500 km -> 1,500 km 초과
        assertEquals("1,500 km 초과", plan.getRemainingDistanceText(51500.0));

        // 주기 텍스트
        assertEquals("10,000 km / 12개월 주기", plan.getIntervalText());

        // 위험도 라벨
        assertEquals("정상", plan.getRiskLabel());
    }

    @Test
    public void testRiskLevelsAndBadges() {
        FuelogBackendApiClient.MaintenancePlanItem dangerPlan = new FuelogBackendApiClient.MaintenancePlanItem(
                "p-danger", "타이어", true, true, 40000.0, 36, 1L,
                null, null, null, null, "ACTIVE", "DANGER"
        );
        assertEquals("교체 시급", dangerPlan.getRiskLabel());
        assertEquals(100, dangerPlan.getProgressPercent(50000.0));

        FuelogBackendApiClient.MaintenancePlanItem overduePlan = new FuelogBackendApiClient.MaintenancePlanItem(
                "p-overdue", "와이퍼", true, true, 10000.0, 6, 1L,
                null, null, null, null, "ACTIVE", "OVERDUE"
        );
        assertEquals("교체 권장", overduePlan.getRiskLabel());
        assertEquals(95, overduePlan.getProgressPercent(50000.0));
    }

    @Test
    public void testApplyBackendMaintenancePlansToRepository() {
        List<FuelogBackendApiClient.MaintenancePlanItem> plans = new ArrayList<>();
        plans.add(new FuelogBackendApiClient.MaintenancePlanItem(
                "item-1", "엔진오일", true, true, 10000.0, 12, 1L,
                "2026-01-10", 40000.0, "2027-01-10", 50000.0, "ACTIVE", "NORMAL"
        ));
        plans.add(new FuelogBackendApiClient.MaintenancePlanItem(
                "item-2", "브레이크액", true, true, 40000.0, 24, 1L,
                "2025-01-10", 20000.0, "2026-01-10", 60000.0, "ACTIVE", "OVERDUE"
        ));
        // 비활성 항목은 홈 정비 예정에 노출되지 않아야 함
        plans.add(new FuelogBackendApiClient.MaintenancePlanItem(
                "item-3", "구형 소모품", true, false, 5000.0, 6, 2L,
                null, null, null, null, "INACTIVE", "NORMAL"
        ));

        repository.applyBackendMaintenancePlans(VEHICLE_ID, plans);

        List<MaintenanceScheduleItem> homeSchedules = repository.getMaintenanceSchedulesForSelectedVehicle();
        assertEquals(2, homeSchedules.size());

        MaintenanceScheduleItem first = homeSchedules.get(0);
        assertEquals("item-1", first.getScheduleId());
        assertEquals("엔진오일", first.getItemTitle());
        assertFalse(first.isUrgent());

        MaintenanceScheduleItem second = homeSchedules.get(1);
        assertEquals("item-2", second.getScheduleId());
        assertEquals("브레이크액", second.getItemTitle());
        assertTrue(second.isUrgent());
    }
}
