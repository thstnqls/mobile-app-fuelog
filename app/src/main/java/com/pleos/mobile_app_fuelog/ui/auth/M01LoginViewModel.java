package com.pleos.mobile_app_fuelog.ui.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.pleos.mobile_app_fuelog.core.navigation.DeepLinkRouter;
import com.pleos.mobile_app_fuelog.core.security.SafeLogger;
import com.pleos.mobile_app_fuelog.data.auth.AuthError;
import com.pleos.mobile_app_fuelog.data.auth.AuthProviderType;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthResult;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M01. 로그인·최초 동의 ViewModel.
 * - 단일 화면 내 모드 전환 (로그인 / 이메일 가입·필수약관 동의 / 계정 복구)
 * - FR-M01-1: 이메일 방식만 허용, 소셜 로그인 후속 확장 안내
 * - FR-M01-3: 서버 오류(SERVER_ERROR)와 입력 오류(INPUT_ERROR) 구분, 중복 제출 방지(inFlight AtomicBoolean)
 * - 예외/완료: 계정 존재 여부를 노출하지 않는 복구 안내, 다른 계정 진입 시 이전 캐시 숨김 상태 전파
 */
public class M01LoginViewModel extends ViewModel {

    public enum AuthMode {
        LOGIN,
        SIGN_UP,
        RECOVERY
    }

    private final MutableLiveData<AuthMode> authMode = new MutableLiveData<>(AuthMode.LOGIN);
    private final MutableLiveData<Boolean> isSubmitting = new MutableLiveData<>(false);
    private final MutableLiveData<AuthError> currentError = new MutableLiveData<>(null);
    private final MutableLiveData<String> recoveryGuidanceMessage = new MutableLiveData<>(null);
    private final MutableLiveData<DeepLinkRouter.RouteDecision> routeEvent = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> previousAccountCacheHidden = new MutableLiveData<>(false);

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private Executor backgroundExecutor = Executors.newSingleThreadExecutor();

    @Nullable
    private AuthRepository authRepository;
    private final DeepLinkRouter deepLinkRouter = new DeepLinkRouter();

    public void init(@NonNull AuthRepository repository) {
        this.authRepository = repository;
    }

    /**
     * 단위 테스트에서 동기 실행기를 주입하기 위한 메서드.
     */
    public void setBackgroundExecutor(@NonNull Executor executor) {
        this.backgroundExecutor = executor;
    }

    @NonNull
    public DeepLinkRouter getDeepLinkRouter() {
        return deepLinkRouter;
    }

    @NonNull
    public LiveData<AuthMode> getAuthMode() {
        return authMode;
    }

    @NonNull
    public LiveData<Boolean> getIsSubmitting() {
        return isSubmitting;
    }

    @NonNull
    public LiveData<AuthError> getCurrentError() {
        return currentError;
    }

    @NonNull
    public LiveData<String> getRecoveryGuidanceMessage() {
        return recoveryGuidanceMessage;
    }

    @NonNull
    public LiveData<DeepLinkRouter.RouteDecision> getRouteEvent() {
        return routeEvent;
    }

    @NonNull
    public LiveData<Boolean> getPreviousAccountCacheHidden() {
        return previousAccountCacheHidden;
    }

    public boolean isRequestInFlight() {
        return inFlight.get();
    }

    public void switchMode(@NonNull AuthMode mode) {
        if (inFlight.get()) {
            return;
        }
        currentError.setValue(null);
        recoveryGuidanceMessage.setValue(null);
        authMode.setValue(mode);
    }

    public void clearMessages() {
        currentError.setValue(null);
        recoveryGuidanceMessage.setValue(null);
    }

    public void consumeRouteEvent() {
        routeEvent.setValue(null);
    }

    /**
     * FR-M01-1: 소셜 로그인 버튼/확장 영역 클릭 시 안내 처리.
     */
    public void onUnsupportedSocialProviderClicked(@NonNull AuthProviderType providerType) {
        if (authRepository == null) {
            return;
        }
        AuthResult result = authRepository.verifyProviderSupported(providerType);
        if (!result.isSuccess() && result.getError() != null) {
            currentError.setValue(result.getError());
        }
    }

    /**
     * 이메일 로그인 제출 (중복 제출 방지 적용).
     */
    public boolean submitLogin(
            @Nullable String email,
            @Nullable String password,
            @NonNull DeepLinkRouter.PermissionGrantChecker permissionChecker
    ) {
        if (authRepository == null) {
            return false;
        }
        if (!inFlight.compareAndSet(false, true)) {
            SafeLogger.w("중복 로그인 제출 차단됨 (이미 요청 진행 중)");
            return false;
        }

        isSubmitting.setValue(true);
        currentError.setValue(null);
        recoveryGuidanceMessage.setValue(null);

        backgroundExecutor.execute(() -> {
            try {
                AuthResult result = authRepository.signInWithEmail(email, password);
                handleAuthResult(result, permissionChecker);
            } finally {
                inFlight.set(false);
                isSubmitting.postValue(false);
            }
        });
        return true;
    }

    /**
     * 이메일 회원가입 및 필수 약관 동의 제출 (중복 제출 방지 적용).
     */
    public boolean submitSignUp(
            @Nullable String email,
            @Nullable String password,
            @Nullable String passwordConfirm,
            boolean serviceTermsChecked,
            boolean privacyPolicyChecked,
            boolean age14Checked,
            @NonNull DeepLinkRouter.PermissionGrantChecker permissionChecker
    ) {
        if (authRepository == null) {
            return false;
        }
        if (!inFlight.compareAndSet(false, true)) {
            SafeLogger.w("중복 회원가입 제출 차단됨 (이미 요청 진행 중)");
            return false;
        }

        isSubmitting.setValue(true);
        currentError.setValue(null);
        recoveryGuidanceMessage.setValue(null);

        backgroundExecutor.execute(() -> {
            try {
                AuthResult result = authRepository.signUpWithEmail(
                        email,
                        password,
                        passwordConfirm,
                        serviceTermsChecked,
                        privacyPolicyChecked,
                        age14Checked
                );
                handleAuthResult(result, permissionChecker);
            } finally {
                inFlight.set(false);
                isSubmitting.postValue(false);
            }
        });
        return true;
    }

    /**
     * 계정 복구(비밀번호 재설정 안내 메일) 요청 (중복 제출 방지 + 계정 존재 여부 비노출 안내).
     */
    public boolean submitRecovery(@Nullable String email) {
        if (authRepository == null) {
            return false;
        }
        if (!inFlight.compareAndSet(false, true)) {
            SafeLogger.w("중복 계정 복구 제출 차단됨 (이미 요청 진행 중)");
            return false;
        }

        isSubmitting.setValue(true);
        currentError.setValue(null);
        recoveryGuidanceMessage.setValue(null);

        backgroundExecutor.execute(() -> {
            try {
                AuthResult result = authRepository.requestPasswordRecovery(email);
                if (result.isSuccess()) {
                    recoveryGuidanceMessage.postValue(result.getNoticeMessage());
                } else {
                    currentError.postValue(result.getError());
                }
            } finally {
                inFlight.set(false);
                isSubmitting.postValue(false);
            }
        });
        return true;
    }

    private void handleAuthResult(
            @NonNull AuthResult result,
            @NonNull DeepLinkRouter.PermissionGrantChecker permissionChecker
    ) {
        if (!result.isSuccess()) {
            currentError.postValue(result.getError());
            return;
        }

        previousAccountCacheHidden.postValue(result.isPreviousAccountCacheHidden());
        DeepLinkRouter.RouteDecision decision = deepLinkRouter.resolvePostLoginRoute(
                result.hasVehicle(),
                permissionChecker
        );
        routeEvent.postValue(decision);
    }
}
