package com.pleos.mobile_app_fuelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pleos.mobile_app_fuelog.data.home.ExpenseRecord;
import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.home.VehicleProfile;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * M11. 정비 등록 · 상세 내역 관리 단위 테스트 (FR-M11-1 ~ FR-M11-4)
 */
public class M11MaintenanceEventSpecTest {

    private M03HomeRepository repository;
    private static final String VEHICLE_ID = "veh-test-uuid-m11";

    @Before
    public void setUp() {
        repository = new M03HomeRepository();
        repository.registerOrUpdateVehicle(new VehicleProfile(
                VEHICLE_ID,
                "34나 7890",
                "휘발유",
                "아반떼 (34나 7890)",
                true
        ));
        repository.selectVehicle(VEHICLE_ID);
    }

    @Test
    public void testMaintenanceEventItemModelAndRevision() {
        FuelogBackendApiClient.MaintenanceEventItem item = new FuelogBackendApiClient.MaintenanceEventItem(
                "ev-001",
                "plan-item-001",
                "2026-11-14",
                45000.0,
                "CONFIRMED",
                true,
                true,
                85000L,
                "KRW",
                "0W-20 합성유 교체",
                "블루핸즈 역삼점",
                null,
                2L
        );

        assertEquals("ev-001", item.id);
        assertEquals("plan-item-001", item.itemId);
        assertEquals("2026-11-14", item.serviceDate);
        assertEquals(Double.valueOf(45000.0), item.odometerKm);
        assertEquals("CONFIRMED", item.status);
        assertTrue(item.shared);
        assertTrue(item.detailsShared);
        assertEquals(85000L, item.cost);
        assertEquals("KRW", item.currency);
        assertEquals("0W-20 합성유 교체", item.memo);
        assertEquals("블루핸즈 역삼점", item.shop);
        assertEquals(2L, item.revision);
    }

    @Test
    public void testRepositoryMaintenanceEventCachingAndLookup() {
        List<FuelogBackendApiClient.MaintenanceEventItem> events = new ArrayList<>();
        events.add(new FuelogBackendApiClient.MaintenanceEventItem(
                "ev-101",
                "item-oil",
                "2026-11-10",
                42000.0,
                "CONFIRMED",
                true,
                true,
                90000L,
                "KRW",
                "엔진오일 교체",
                "스피드메이트",
                null,
                1L
        ));
        events.add(new FuelogBackendApiClient.MaintenanceEventItem(
                "ev-102",
                "item-filter",
                "2026-11-12",
                42500.0,
                "CONFIRMED",
                true,
                false, // detailsShared = false (비공개)
                25000L,
                "KRW",
                "에어컨 필터 교체",
                "공임나라",
                null,
                1L
        ));

        // 1. 저장 및 캐싱 반영
        repository.applyBackendMaintenanceEvents(VEHICLE_ID, events);

        // 2. 단건 조회 확인
        FuelogBackendApiClient.MaintenanceEventItem ev101 = repository.getMaintenanceEventById(VEHICLE_ID, "ev-101");
        assertNotNull(ev101);
        assertEquals("ev-101", ev101.id);
        assertEquals("스피드메이트", ev101.shop);
        assertEquals(90000L, ev101.cost);

        FuelogBackendApiClient.MaintenanceEventItem ev102 = repository.getMaintenanceEventById(VEHICLE_ID, "ev-102");
        assertNotNull(ev102);
        assertFalse(ev102.detailsShared);

        // 3. 전체 목록 확인
        List<FuelogBackendApiClient.MaintenanceEventItem> allEvents = repository.getMaintenanceEventsForVehicle(VEHICLE_ID);
        assertEquals(2, allEvents.size());

        // 4. 지출 차계부(ExpenseRecord) 연동 확인
        List<ExpenseRecord> expenses = repository.getExpensesForVehicle(VEHICLE_ID);
        boolean foundMaint101 = false;
        for (ExpenseRecord e : expenses) {
            if (e.getRecordId().equals("maint_db_ev-101")) {
                foundMaint101 = true;
                assertEquals(ExpenseRecord.Category.MAINTENANCE, e.getCategory());
                assertEquals(90000L, e.getAmountWon());
            }
        }
        assertTrue(foundMaint101);

        // 5. 삭제 확인 (removeMaintenanceEvent)
        repository.removeMaintenanceEvent(VEHICLE_ID, "ev-101");
        assertNull(repository.getMaintenanceEventById(VEHICLE_ID, "ev-101"));
        assertEquals(1, repository.getMaintenanceEventsForVehicle(VEHICLE_ID).size());

        boolean stillInExpenses = false;
        for (ExpenseRecord e : repository.getExpensesForVehicle(VEHICLE_ID)) {
            if (e.getRecordId().equals("maint_db_ev-101")) {
                stillInExpenses = true;
                break;
            }
        }
        assertFalse(stillInExpenses);
    }

    @Test
    public void testFreeServiceAndPrivacySettingsRules() {
        // FR-M11-2 & FR-M11-4: 0원 무상 정비 명시 가능 및 공개 범위 분리
        FuelogBackendApiClient.MaintenanceEventItem freeEvent = new FuelogBackendApiClient.MaintenanceEventItem(
                "ev-free",
                "item-recall",
                "2026-11-14",
                43000.0,
                "CONFIRMED",
                true,   // 공동 정비 이력 공유
                false,  // 상세 메모/비용 비공개
                0L,     // 무상 보증 수리
                "KRW",
                "제조사 리콜 무상 점검",
                "직영 서비스센터",
                null,
                1L
        );

        assertEquals(0L, freeEvent.cost);
        assertTrue(freeEvent.shared);
        assertFalse(freeEvent.detailsShared);
    }

    @Test
    public void testMaintenanceEventEditReflectsInLedger() {
        // 1. 최초 정비 기록 추가 (기존 금액: 85,000원)
        String recordId = "maint_db_test_edit_01";
        ExpenseRecord initial = new ExpenseRecord(
                recordId,
                VEHICLE_ID,
                ExpenseRecord.Category.MAINTENANCE,
                ExpenseRecord.Ownership.MY_RECORD,
                ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                "블루핸즈 - 엔진오일",
                "11월 10일",
                85000L
        );
        repository.addExpenseRecord(initial);

        ExpenseRecord fetched = repository.getExpenseRecordById(recordId);
        assertNotNull(fetched);
        assertEquals(85000L, fetched.getAmountWon());
        assertEquals("블루핸즈 - 엔진오일", fetched.getTitle());

        // 2. M11에서 정비 내용 수정 (금액: 120,000원, 타이틀: 공임나라 - 합성유 및 에어컨필터)
        ExpenseRecord updated = new ExpenseRecord(
                recordId,
                VEHICLE_ID,
                ExpenseRecord.Category.MAINTENANCE,
                ExpenseRecord.Ownership.MY_RECORD,
                ExpenseRecord.ConfirmationState.USER_CONFIRMED,
                "공임나라 - 합성유 및 에어컨필터",
                "11월 12일",
                120000L
        );
        repository.addExpenseRecord(updated);

        // 3. 차계부(ExpenseRecord) 즉시 반영 검증
        ExpenseRecord afterEdit = repository.getExpenseRecordById(recordId);
        assertNotNull(afterEdit);
        assertEquals(120000L, afterEdit.getAmountWon());
        assertEquals("공임나라 - 합성유 및 에어컨필터", afterEdit.getTitle());
        assertEquals("11월 12일", afterEdit.getDateLabel());

        // 4. 차계부 전체 목록에서 단일 건으로 갱신되었는지 확인 (중복 등록 방지)
        int count = 0;
        for (ExpenseRecord e : repository.getExpensesForVehicle(VEHICLE_ID)) {
            if (recordId.equals(e.getRecordId())) {
                count++;
            }
        }
        assertEquals(1, count);

        // 5. 삭제 시 차계부에서 즉시 제거되는지 확인
        assertTrue(repository.removeExpenseRecordById(recordId));
        assertNull(repository.getExpenseRecordById(recordId));
    }
}
