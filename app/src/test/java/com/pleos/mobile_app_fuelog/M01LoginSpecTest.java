package com.pleos.mobile_app_fuelog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pleos.mobile_app_fuelog.core.navigation.DeepLinkRouter;
import com.pleos.mobile_app_fuelog.core.permission.OptionalPermissionType;
import com.pleos.mobile_app_fuelog.core.security.SafeLogger;
import com.pleos.mobile_app_fuelog.data.auth.AuthError;
import com.pleos.mobile_app_fuelog.data.auth.AuthErrorType;
import com.pleos.mobile_app_fuelog.data.auth.AuthProviderType;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthResult;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * M01. 로그인·최초 동의 요구사항 통합 단위 테스트.
 */
public class M01LoginSpecTest {

    private UserCacheManager cacheManager;
    private AuthRepository authRepository;
    private DeepLinkRouter deepLinkRouter;

    @Before
    public void setUp() {
        SafeLogger.clearRecentLogs();
        cacheManager = new UserCacheManager(null);
        authRepository = new AuthRepository(null, cacheManager);
        authRepository.registerLocalAccountForTest("car@fuelog.com", "fuelog1234", "user_with_car");
        authRepository.registerLocalAccountForTest("new@fuelog.com", "fuelog1234", "user_no_car");
        cacheManager.setRegisteredVehicleForUser("user_with_car", "아이오닉 6 롱레인지 (12가 3456)");
        deepLinkRouter = new DeepLinkRouter();
    }

    @Test
    public void frM01_1_onlyEmailSupportedInInitialRelease_andRoutesM02OrM03() {
        assertTrue(AuthProviderType.EMAIL.isSupportedInInitialRelease());
        assertFalse(AuthProviderType.GOOGLE.isSupportedInInitialRelease());
        assertFalse(AuthProviderType.KAKAO.isSupportedInInitialRelease());

        AuthResult socialAttempt = authRepository.verifyProviderSupported(AuthProviderType.GOOGLE);
        assertFalse(socialAttempt.isSuccess());
        assertNotNull(socialAttempt.getError());
        assertEquals(AuthErrorType.INPUT_ERROR, socialAttempt.getError().getType());

        // 차량 보유 계정 -> M03
        AuthResult carLogin = authRepository.signInWithEmail("car@fuelog.com", "fuelog1234");
        assertTrue(carLogin.isSuccess());
        assertTrue(carLogin.hasVehicle());
        DeepLinkRouter.RouteDecision m03Route = deepLinkRouter.resolvePostLoginRoute(
                carLogin.hasVehicle(), perm -> false
        );
        assertEquals(DeepLinkRouter.Destination.M03_HAS_VEHICLE, m03Route.getDestination());

        // 차량 미보유 계정 -> M02
        AuthResult noCarLogin = authRepository.signInWithEmail("new@fuelog.com", "fuelog1234");
        assertTrue(noCarLogin.isSuccess());
        assertFalse(noCarLogin.hasVehicle());
        DeepLinkRouter.RouteDecision m02Route = deepLinkRouter.resolvePostLoginRoute(
                noCarLogin.hasVehicle(), perm -> false
        );
        assertEquals(DeepLinkRouter.Destination.M02_NO_VEHICLE, m02Route.getDestination());
    }

    @Test
    public void frM01_1_signUpRequiresMandatoryTermsConsent() {
        // 필수 약관 미동의 시 가입 차단 (INPUT_ERROR, TERMS 필드)
        AuthResult missingTerms = authRepository.signUpWithEmail(
                "fresh@fuelog.com",
                "pass1234",
                "pass1234",
                true,
                false, // 개인정보 미동의
                true
        );
        assertFalse(missingTerms.isSuccess());
        assertNotNull(missingTerms.getError());
        assertEquals(AuthErrorType.INPUT_ERROR, missingTerms.getError().getType());
        assertEquals(AuthError.Field.TERMS, missingTerms.getError().getField());

        // 모든 필수 약관 동의 시 정상 가입 (신규 계정은 차량 없음 -> M02)
        AuthResult validSignUp = authRepository.signUpWithEmail(
                "fresh@fuelog.com",
                "pass1234",
                "pass1234",
                true,
                true,
                true
        );
        assertTrue(validSignUp.isSuccess());
        assertFalse(validSignUp.hasVehicle());
    }

    @Test
    public void frM01_2_optionalPermissionDenialDoesNotBlockLogin_andDeepLinkRechecksPermission() {
        // 모든 선택 권한이 거부(false)된 상태에서도 로그인 정상 성공
        AuthResult loginResult = authRepository.signInWithEmail("car@fuelog.com", "fuelog1234");
        assertTrue(loginResult.isSuccess());

        // 푸시 딥링크 대기 등록 (위치 권한 필요)
        deepLinkRouter.setPendingDeepLink(new DeepLinkRouter.PendingDeepLink(
                DeepLinkRouter.Destination.M03_HAS_VEHICLE,
                OptionalPermissionType.LOCATION,
                "주유소 자동 매칭 알림"
        ));

        // 로그인 후 권한 재검사: 위치 권한이 미허용이면 M03으로 이동하되 도착 시 위치 권한 목적 설명을 띄우도록 표시
        DeepLinkRouter.RouteDecision decision = deepLinkRouter.resolvePostLoginRoute(
                loginResult.hasVehicle(),
                perm -> false
        );
        assertTrue(decision.isFromDeepLink());
        assertEquals(DeepLinkRouter.Destination.M03_HAS_VEHICLE, decision.getDestination());
        assertEquals(OptionalPermissionType.LOCATION, decision.getPermissionToPromptOnArrival());
    }

    @Test
    public void frM01_3_distinguishesInputErrorAndServerError_andNeverLogsSecrets() {
        // 1) 입력 오류 (잘못된 비밀번호)
        AuthResult badCreds = authRepository.signInWithEmail("car@fuelog.com", "wrongpass99");
        assertFalse(badCreds.isSuccess());
        assertNotNull(badCreds.getError());
        assertTrue(badCreds.getError().isInputError());
        assertFalse(badCreds.getError().isServerError());

        // 2) 서버 오류 (HTTP 503 시뮬레이션)
        authRepository.setSimulateServerError(true);
        AuthResult serverFail = authRepository.signInWithEmail("car@fuelog.com", "fuelog1234");
        assertFalse(serverFail.isSuccess());
        assertNotNull(serverFail.getError());
        assertTrue(serverFail.getError().isServerError());
        assertFalse(serverFail.getError().isInputError());
        authRepository.setSimulateServerError(false);

        // 3) 비밀번호·토큰 로그 비노출 검증
        String rawSecretLog = "login attempt password=mySecretPassword123! "
                + "{\"access_token\":\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjMifQ.sig\"} "
                + "Authorization: Bearer secret_bearer_token_999";
        SafeLogger.i(rawSecretLog);

        List<String> logs = SafeLogger.getRecentSanitizedLogs();
        for (String logLine : logs) {
            assertFalse("비밀번호가 로그에 노출되면 안 됩니다: " + logLine, logLine.contains("mySecretPassword123!"));
            assertFalse("비밀번호가 로그에 노출되면 안 됩니다: " + logLine, logLine.contains("wrongpass99"));
            assertFalse("비밀번호가 로그에 노출되면 안 됩니다: " + logLine, logLine.contains("fuelog1234"));
            assertFalse("JWT 토큰이 로그에 노출되면 안 됩니다: " + logLine, logLine.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
            assertFalse("Bearer 토큰이 로그에 노출되면 안 됩니다: " + logLine, logLine.contains("secret_bearer_token_999"));
        }

        AuthSession session = new AuthSession("u1", "car@fuelog.com", "secret_access_123", "secret_refresh_456", 9999999999L, true);
        assertFalse(session.toString().contains("secret_access_123"));
        assertFalse(session.toString().contains("secret_refresh_456"));
    }

    @Test
    public void exceptionAndCompletion_hidesPreviousAccountCacheOnAccountSwitch_andNeutralRecoveryNotice() {
        // 1) 첫 번째 계정(car@fuelog.com, 차량 있음) 로그인 -> 차량 캐시 표시됨
        AuthResult firstLogin = authRepository.signInWithEmail("car@fuelog.com", "fuelog1234");
        assertTrue(firstLogin.isSuccess());
        assertTrue(cacheManager.isCacheVisible());
        assertNotNull(cacheManager.getVisibleCachedVehicleName());

        // 2) 세션 만료 발생 -> 즉시 이전 계정 캐시 숨김 처리
        authRepository.expireCurrentSession();
        assertNull(authRepository.getCurrentValidSession());
        assertFalse(cacheManager.isCacheVisible());
        assertNull("세션 만료 상태에서 이전 계정 차량 캐시가 노출되면 안 됩니다.", cacheManager.getVisibleCachedVehicleName());

        // 3) 다른 계정(new@fuelog.com)으로 진입 -> 이전 계정 캐시 완전 숨김 및 초기화 확인
        AuthResult secondLogin = authRepository.signInWithEmail("new@fuelog.com", "fuelog1234");
        assertTrue(secondLogin.isSuccess());
        assertTrue("다른 계정 진입 시 이전 캐시 숨김 플래그가 true여야 합니다.", secondLogin.isPreviousAccountCacheHidden());
        assertNull("다른 계정 진입 후 이전 계정의 차량 캐시가 남아있으면 안 됩니다.", cacheManager.getVisibleCachedVehicleName());

        // 4) 계정 복구 요청 시 가입된 이메일과 미가입 이메일 모두 동일한 안내 문구 반환 (계정 존재 여부 비노출)
        AuthResult existingRecovery = authRepository.requestPasswordRecovery("car@fuelog.com");
        AuthResult unknownRecovery = authRepository.requestPasswordRecovery("nonexistent_user_999@fuelog.com");

        assertTrue(existingRecovery.isSuccess());
        assertTrue(unknownRecovery.isSuccess());
        assertEquals(AuthRepository.RECOVERY_NEUTRAL_GUIDANCE, existingRecovery.getNoticeMessage());
        assertEquals(existingRecovery.getNoticeMessage(), unknownRecovery.getNoticeMessage());
    }

    @Test
    public void m02QrVehicleRegistration_linksVehicleToAccountInCache() {
        assertFalse(cacheManager.hasRegisteredVehicle("user_no_car"));
        cacheManager.setRegisteredVehicleUuid("user_no_car", "veh-uuid-qr-01");
        cacheManager.setRegisteredVehicleForUser("user_no_car", "QR 연동 차량");
        assertTrue(cacheManager.hasRegisteredVehicle("user_no_car"));
        assertEquals("veh-uuid-qr-01", cacheManager.getRegisteredVehicleUuid("user_no_car"));
    }

    @Test
    public void testAutoLoginAndSessionPersistence() {
        // 1. 초기 자동 로그인 기본 활성화 확인
        assertTrue(authRepository.isAutoLoginEnabled());

        // 2. 로그인 성공 시 이메일 및 세션 영속화 확인
        AuthResult loginResult = authRepository.signInWithEmail("car@fuelog.com", "fuelog1234");
        assertTrue(loginResult.isSuccess());
        assertEquals("car@fuelog.com", authRepository.getSavedEmail());

        AuthSession session = authRepository.getCurrentValidSession();
        assertNotNull(session);
        assertEquals("car@fuelog.com", session.getEmail());

        // 3. 자동 로그인 토글 확인
        authRepository.setAutoLoginEnabled(false);
        assertFalse(authRepository.isAutoLoginEnabled());
        authRepository.setAutoLoginEnabled(true);
        assertTrue(authRepository.isAutoLoginEnabled());

        // 4. 로그아웃 시 자동 로그인 비활성화 및 세션 해제 확인 (저장된 이메일은 유지되어 재입력 편리)
        authRepository.signOut();
        assertNull(authRepository.getCurrentValidSession());
        assertFalse(authRepository.isAutoLoginEnabled());
        assertEquals("car@fuelog.com", authRepository.getSavedEmail());
    }
}
