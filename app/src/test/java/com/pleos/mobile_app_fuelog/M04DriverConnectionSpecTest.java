package com.pleos.mobile_app_fuelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.pleos.mobile_app_fuelog.data.connection.M04ConnectionStage;
import com.pleos.mobile_app_fuelog.data.connection.M04DriverConnectionCoordinator;
import com.pleos.mobile_app_fuelog.data.connection.RegisteredPleosDevice;
import com.pleos.mobile_app_fuelog.data.connection.TripAuthProof;

import org.junit.Before;
import org.junit.Test;

/**
 * M04. 휴대폰 연결·이번 운행 사용자 요구사항(FR-M04-1 ~ FR-M04-3 및 예외/완료) 단위 테스트.
 */
public class M04DriverConnectionSpecTest {

    private M04DriverConnectionCoordinator coordinator;

    @Before
    public void setUp() {
        coordinator = new M04DriverConnectionCoordinator();
    }

    @Test
    public void frM04_1_bluetoothAudioOnly_neverShowsUserAuthenticated() {
        coordinator.onBluetoothAudioConnectedOnly();

        assertTrue(coordinator.isBluetoothAudioConnected());
        assertFalse("오디오 연결만으로 앱 인증 완료가 되면 안 됩니다.", coordinator.isFuelogAppAuthenticated());
        assertNotEquals(M04ConnectionStage.IN_USE, coordinator.getCurrentStage());
    }

    @Test
    public void frM04_2_shortLivedProof_showsRequestSentBeforeAck_andTransitionsToInUseOnC03Ack() {
        long now = 1793700000L;
        TripAuthProof proof = coordinator.startUseMyAccountForCurrentTrip(
                "user_1", "car@fuelog.com", "nonce_abc", now
        );

        assertNotNull(proof);
        assertTrue(proof.toJsonPayload().contains("FUELOG_QR_PROOF"));
        // 완료 응답 전에는 반드시 '요청 보냄(WAITING_VEHICLE_CONFIRM)' 상태여야 함
        assertTrue(coordinator.isRequestSentWaitingAck());
        assertFalse(coordinator.isFuelogAppAuthenticated());
        assertEquals(M04ConnectionStage.WAITING_VEHICLE_CONFIRM, coordinator.getCurrentStage());

        // 차량 C03 완료 응답 수신 후 '사용 중(IN_USE)' 전환
        boolean ackOk = coordinator.onVehicleC03ResponseReceived(proof, "nonce_abc", now + 5);
        assertTrue(ackOk);
        assertTrue(coordinator.isFuelogAppAuthenticated());
        assertFalse(coordinator.isRequestSentWaitingAck());
        assertEquals(M04ConnectionStage.IN_USE, coordinator.getCurrentStage());
    }

    @Test
    public void frM04_3_whileDriving_defersUntilStopped_andMatchesVehicleC03() {
        long now = 1793700000L;
        coordinator.setVehicleDriving(true);

        TripAuthProof proof = coordinator.startUseMyAccountForCurrentTrip(
                "user_1", "car@fuelog.com", "nonce_drv", now
        );
        assertTrue(coordinator.isDeferredUntilStopped());

        // 주행 중 차량 응답이 와도 '정차 후 적용'으로 보류 유지
        boolean ackWhileDriving = coordinator.onVehicleC03ResponseReceived(proof, "nonce_drv", now + 2);
        assertTrue(ackWhileDriving);
        assertTrue(coordinator.isDeferredUntilStopped());
        assertFalse("주행 중에는 즉시 사용 중으로 전환하지 않고 보류해야 합니다.", coordinator.isFuelogAppAuthenticated());
        assertEquals(M04ConnectionStage.WAITING_VEHICLE_CONFIRM, coordinator.getCurrentStage());

        // 정차 시 즉시 '사용 중(IN_USE)'으로 최종 적용
        coordinator.setVehicleDriving(false);
        assertFalse(coordinator.isDeferredUntilStopped());
        assertTrue(coordinator.isFuelogAppAuthenticated());
        assertEquals(M04ConnectionStage.IN_USE, coordinator.getCurrentStage());
    }

    @Test
    public void exceptionAndCompletion_blocksReplay_dualPhone_withdrawnMember_revokedPerm_andEndedTrip() {
        long now = 1793700000L;

        // 1) 증명 재사용(Replay) 차단
        TripAuthProof proof = coordinator.startUseMyAccountForCurrentTrip(
                "user_1", "car@fuelog.com", "nonce_once", now
        );
        assertTrue(coordinator.onVehicleC03ResponseReceived(proof, "nonce_once", now + 1));
        assertFalse("동일 증명 재사용(Replay)은 차단되어야 합니다.",
                coordinator.onVehicleC03ResponseReceived(proof, "nonce_once", now + 2));
        assertEquals(M04ConnectionStage.FAILED, coordinator.getCurrentStage());

        // 2) 두 폰 동시 연결 오인증 차단
        coordinator.startFreshTripSession();
        coordinator.setOtherPhoneOccupyingSession("phone_2_other");
        boolean dualBlocked = false;
        try {
            coordinator.startUseMyAccountForCurrentTrip("user_1", "car@fuelog.com", "nonce_2", now);
        } catch (IllegalStateException e) {
            dualBlocked = true;
        }
        assertTrue("다른 폰이 이미 연결된 운행에는 동시 연결이 차단되어야 합니다.", dualBlocked);
        assertEquals(M04ConnectionStage.FAILED, coordinator.getCurrentStage());
        coordinator.setOtherPhoneOccupyingSession(null);

        // 3) 권한 철회 시 즉시 인증 해제
        coordinator.startFreshTripSession();
        TripAuthProof p3 = coordinator.startUseMyAccountForCurrentTrip("user_1", "car@fuelog.com", "nonce_3", now);
        coordinator.onVehicleC03ResponseReceived(p3, "nonce_3", now + 1);
        assertTrue(coordinator.isFuelogAppAuthenticated());
        coordinator.onPermissionRevoked();
        assertFalse(coordinator.isFuelogAppAuthenticated());
        assertEquals(M04ConnectionStage.PERMISSION_REQUIRED, coordinator.getCurrentStage());

        // 4) 멤버 탈퇴 시 인증 차단
        coordinator.setMemberWithdrawn(true);
        assertEquals(M04ConnectionStage.FAILED, coordinator.getCurrentStage());
        assertFalse(coordinator.isFuelogAppAuthenticated());
        coordinator.setMemberWithdrawn(false);

        // 5) 재연결 시 이미 끝난 운행을 가져오지 않음
        boolean importedEnded = coordinator.onReconnectAttempt(
                M04DriverConnectionCoordinator.ENDED_TRIP_SESSION_ID
        );
        assertFalse("이미 종료된 운행 세션은 재연결 시 가져오면 안 됩니다.", importedEnded);
    }

    @Test
    public void qrPairingSync_registersVehicleAndDeviceViaQrRecognition() {
        long now = 1793700000L;
        coordinator.removeAllRegisteredDevices();
        assertEquals(M04ConnectionStage.UNREGISTERED, coordinator.getCurrentStage());

        // C01/C03 QR 코드 인식으로 차량·기기 정보를 등록
        coordinator.registerOrUpdateFromQrPairing(
                new RegisteredPleosDevice(
                        "qr_c01_device_1",
                        "C01 QR 연동 차량 단말",
                        "QR:C01:01",
                        "QR 연동 차량",
                        true,
                        true
                ),
                M04DriverConnectionCoordinator.DEFAULT_VEHICLE_ID,
                "QR 연동 차량"
        );

        assertEquals(M04ConnectionStage.CONNECTING, coordinator.getCurrentStage());
        assertEquals("QR 연동 차량", coordinator.getVehicleAlias());
        assertEquals(1, coordinator.getRegisteredDevices().size());

        TripAuthProof proof = coordinator.startUseMyAccountForCurrentTrip(
                "user_1", "car@fuelog.com", "nonce_qr_sync", now
        );
        assertTrue(coordinator.onVehicleC03ResponseReceived(proof, "nonce_qr_sync", now + 1));
        assertEquals(M04ConnectionStage.IN_USE, coordinator.getCurrentStage());
        assertTrue(coordinator.isFuelogAppAuthenticated());
    }
}
