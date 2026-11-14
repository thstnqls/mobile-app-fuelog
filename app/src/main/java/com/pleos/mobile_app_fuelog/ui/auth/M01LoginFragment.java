package com.pleos.mobile_app_fuelog.ui.auth;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.core.navigation.DeepLinkRouter;
import com.pleos.mobile_app_fuelog.core.permission.OptionalPermissionType;
import com.pleos.mobile_app_fuelog.core.permission.PermissionGuideHelper;
import com.pleos.mobile_app_fuelog.databinding.FragmentM01LoginBinding;
import com.pleos.mobile_app_fuelog.data.auth.AuthError;
import com.pleos.mobile_app_fuelog.data.auth.AuthProviderType;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;

/**
 * M01. 로그인·최초 동의 화면 Fragment.
 * - fuelog 한 줄 소개 / 이메일 로그인·가입 / 계정 복구 / 필수 약관 / 선택 기능 설명 제공
 * - 비로그인 또는 세션 만료 시 진입 -> 인증 후 M02(차량 없음) 또는 M03(차량 있음) 분기 이동
 * - 푸시 딥링크 대기 시 로그인 후 권한을 재검사해 목적 화면으로 이동
 */
public class M01LoginFragment extends Fragment {

    public static final String ARG_ARRIVAL_NOTICE = "arg_arrival_notice";
    public static final String ARG_PROMPT_PERMISSION_KEY = "arg_prompt_permission_key";

    private FragmentM01LoginBinding binding;
    private M01LoginViewModel viewModel;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private boolean updatingCheckboxesProgrammatically = false;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentM01LoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        userCacheManager = UserCacheManager.getInstance(requireContext());
        authRepository = AuthRepository.getInstance(requireContext());

        if (authRepository.isRemoteSupabaseConfigured()) {
            binding.tvSupabaseBadge.setText("Supabase Auth 실서버 연동 완료 (이메일 세션 인증 · 소셜 후속 확장)");
        }

        viewModel = new ViewModelProvider(requireActivity()).get(M01LoginViewModel.class);
        viewModel.init(authRepository);

        setupTabs();
        setupTermsCheckboxes();
        setupClickListeners();
        observeViewModel();
        refreshContextAndCacheBanner();

        // 이미 유효한 세션이 존재하고 자동 로그인이 켜져 있는 경우 뷰 트리가 완전히 마운트된 직후 M02/M03(또는 보류 딥링크)로 이동
        AuthSession existingSession = authRepository.getCurrentValidSession();
        if (existingSession != null && authRepository.isAutoLoginEnabled()) {
            view.post(() -> {
                if (!isAdded() || binding == null) {
                    return;
                }
                boolean hasVehicle = userCacheManager.hasRegisteredVehicle(existingSession.getUserId());
                DeepLinkRouter.RouteDecision decision = viewModel.getDeepLinkRouter().resolvePostLoginRoute(
                        hasVehicle,
                        perm -> PermissionGuideHelper.isPermissionGranted(requireContext(), perm)
                );
                navigateByDecision(decision);
            });
            return;
        }

        // 저장된 계정 이메일이 있으면 자동 완성 및 자동 로그인 체크박스 상태 초기화
        String savedEmail = authRepository.getSavedEmail();
        if (savedEmail != null && !savedEmail.trim().isEmpty() && (binding.etEmail.getText() == null || binding.etEmail.getText().toString().isEmpty())) {
            binding.etEmail.setText(savedEmail.trim());
        }
        binding.cbAutoLogin.setChecked(authRepository.isAutoLoginEnabled());
    }

    private void setupTabs() {
        binding.tabAuthMode.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (position == 0) {
                    viewModel.switchMode(M01LoginViewModel.AuthMode.LOGIN);
                } else if (position == 1) {
                    viewModel.switchMode(M01LoginViewModel.AuthMode.SIGN_UP);
                } else if (position == 2) {
                    viewModel.switchMode(M01LoginViewModel.AuthMode.RECOVERY);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
    }

    private void setupTermsCheckboxes() {
        binding.cbTermsAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingCheckboxesProgrammatically) {
                return;
            }
            updatingCheckboxesProgrammatically = true;
            binding.cbTermsService.setChecked(isChecked);
            binding.cbTermsPrivacy.setChecked(isChecked);
            binding.cbTermsAge14.setChecked(isChecked);
            updatingCheckboxesProgrammatically = false;
        });

        View.OnClickListener individualTermListener = v -> {
            if (updatingCheckboxesProgrammatically) {
                return;
            }
            updatingCheckboxesProgrammatically = true;
            boolean allChecked = binding.cbTermsService.isChecked()
                    && binding.cbTermsPrivacy.isChecked()
                    && binding.cbTermsAge14.isChecked();
            binding.cbTermsAll.setChecked(allChecked);
            updatingCheckboxesProgrammatically = false;
        };

        binding.cbTermsService.setOnClickListener(individualTermListener);
        binding.cbTermsPrivacy.setOnClickListener(individualTermListener);
        binding.cbTermsAge14.setOnClickListener(individualTermListener);
    }

    private void setupClickListeners() {
        binding.btnSubmitPrimary.setOnClickListener(v -> onPrimarySubmitClicked());

        binding.btnQuickRecovery.setOnClickListener(v -> {
            TabLayout.Tab recoveryTab = binding.tabAuthMode.getTabAt(2);
            if (recoveryTab != null) {
                recoveryTab.select();
            }
        });

        binding.btnQuickTermsPreview.setOnClickListener(v -> showTermsDetailDialog(
                "fuelog 필수 이용약관 및 개인정보 처리방침 안내",
                "1. [필수] 서비스 이용약관\n"
                        + "fuelog는 차량 주유 기록, 정비 이력, 연비 및 유지비 리포트 기능을 제공합니다.\n\n"
                        + "2. [필수] 개인정보 수집 및 이용 동의\n"
                        + "• 수집 항목: 이메일 주소, 암호화된 인증 토큰, 차량 및 주유 기록 데이터\n"
                        + "• 보유 기간: 회원 탈퇴 시까지 (법령에 따른 보존 기간 제외)\n\n"
                        + "3. [필수] 만 14세 이상 이용 확인\n"
                        + "만 14세 이상 이용자만 회원으로 가입할 수 있습니다."
        ));

        binding.btnViewServiceTerms.setOnClickListener(v -> showTermsDetailDialog(
                "[필수] fuelog 서비스 이용약관",
                "제1조(목적) 본 약관은 fuelog 스마트 차계부 서비스의 이용 조건 및 절차를 규정합니다.\n\n"
                        + "제2조(계정 및 보안) 회원은 본인의 이메일 계정을 통해 로그인하며, 비밀번호와 세션 토큰은 안전하게 암호화·관리됩니다.\n\n"
                        + "제3조(서비스 내용) 차량 등록, 주유·유지비 대시보드 및 데이터 동기화 기능을 제공합니다."
        ));

        binding.btnViewPrivacyTerms.setOnClickListener(v -> showTermsDetailDialog(
                "[필수] 개인정보 수집 및 이용 동의",
                "1. 수집하는 개인정보 항목\n"
                        + "• 필수항목: 이메일 주소, 인증 세션 식별자(UUID)\n"
                        + "• 선택기능 이용 시: 위치 정보(주유소 매칭), 근처 기기 연결 정보(블루투스/OBD), 알림 수신 토큰\n\n"
                        + "2. 수집 및 이용 목적\n"
                        + "• Supabase Auth 기반 회원 식별, 기기 간 차계부 데이터 동기화, 계정 복구 안내 발송\n\n"
                        + "3. 선택 권한 비강제 원칙\n"
                        + "• 선택 권한에 동의하지 않아도 회원가입과 로그인은 제한 없이 가능합니다."
        ));

        binding.btnSocialLaterInfo.setOnClickListener(v ->
                viewModel.onUnsupportedSocialProviderClicked(AuthProviderType.GOOGLE)
        );

        // 검증/시뮬레이션 도우미 버튼들
        binding.btnFillCarAccount.setOnClickListener(v -> {
            TabLayout.Tab loginTab = binding.tabAuthMode.getTabAt(0);
            if (loginTab != null) {
                loginTab.select();
            }
            binding.etEmail.setText("car@fuelog.com");
            binding.etPassword.setText("fuelog1234");
            viewModel.clearMessages();
            Snackbar.make(binding.getRoot(),
                    "차량 보유 계정(car@fuelog.com) 입력 완료 -> 로그인 시 차량 홈으로 이동합니다.",
                    Snackbar.LENGTH_SHORT).show();
        });

        binding.btnFillNoCarAccount.setOnClickListener(v -> {
            TabLayout.Tab loginTab = binding.tabAuthMode.getTabAt(0);
            if (loginTab != null) {
                loginTab.select();
            }
            binding.etEmail.setText("new@fuelog.com");
            binding.etPassword.setText("fuelog1234");
            viewModel.clearMessages();
            Snackbar.make(binding.getRoot(),
                    "차량 미보유 계정(new@fuelog.com) 입력 완료 -> 로그인 시 차량 등록으로 이동합니다.",
                    Snackbar.LENGTH_SHORT).show();
        });

        binding.switchSimulateServerError.setChecked(authRepository.isSimulateServerError());
        binding.switchSimulateServerError.setOnCheckedChangeListener((buttonView, isChecked) -> {
            authRepository.setSimulateServerError(isChecked);
            String msg = isChecked
                    ? "서버 통신 오류(503) 시뮬레이션이 켜졌습니다."
                    : "서버 통신 오류 시뮬레이션이 해제되었습니다.";
            Snackbar.make(binding.getRoot(), msg, Snackbar.LENGTH_SHORT).show();
        });

        binding.btnSimulatePushDeeplink.setOnClickListener(v -> {
            DeepLinkRouter.PendingDeepLink simulatedPush = new DeepLinkRouter.PendingDeepLink(
                    DeepLinkRouter.Destination.M03_HAS_VEHICLE,
                    OptionalPermissionType.LOCATION,
                    "주유소 도착 알림 — 내 주변 주유소 자동 매칭"
            );
            viewModel.getDeepLinkRouter().setPendingDeepLink(simulatedPush);
            refreshContextAndCacheBanner();
            Snackbar.make(binding.getRoot(),
                    "푸시 딥링크가 예약되었습니다. 로그인 후 차량 보유 여부 및 위치 권한을 재검사해 이동합니다.",
                    Snackbar.LENGTH_LONG).show();
        });
    }

    private void onPrimarySubmitClicked() {
        clearFieldErrors();
        String email = binding.etEmail.getText() != null ? binding.etEmail.getText().toString() : "";
        String password = binding.etPassword.getText() != null ? binding.etPassword.getText().toString() : "";
        String passwordConfirm = binding.etPasswordConfirm.getText() != null
                ? binding.etPasswordConfirm.getText().toString()
                : "";

        M01LoginViewModel.AuthMode mode = viewModel.getAuthMode().getValue();
        if (mode == null) {
            mode = M01LoginViewModel.AuthMode.LOGIN;
        }

        DeepLinkRouter.PermissionGrantChecker permChecker =
                perm -> PermissionGuideHelper.isPermissionGranted(requireContext(), perm);

        if (mode == M01LoginViewModel.AuthMode.LOGIN) {
            authRepository.setAutoLoginEnabled(binding.cbAutoLogin.isChecked());
            if (!email.trim().isEmpty()) {
                authRepository.setSavedEmail(email.trim());
            }
            viewModel.submitLogin(email, password, permChecker);
        } else if (mode == M01LoginViewModel.AuthMode.SIGN_UP) {
            viewModel.submitSignUp(
                    email,
                    password,
                    passwordConfirm,
                    binding.cbTermsService.isChecked(),
                    binding.cbTermsPrivacy.isChecked(),
                    binding.cbTermsAge14.isChecked(),
                    permChecker
            );
        } else if (mode == M01LoginViewModel.AuthMode.RECOVERY) {
            viewModel.submitRecovery(email);
        }
    }

    private void observeViewModel() {
        viewModel.getAuthMode().observe(getViewLifecycleOwner(), mode -> {
            clearFieldErrors();
            if (mode == M01LoginViewModel.AuthMode.LOGIN) {
                binding.tilPassword.setVisibility(View.VISIBLE);
                binding.tilPasswordConfirm.setVisibility(View.GONE);
                binding.layoutTermsConsent.setVisibility(View.GONE);
                binding.layoutAutoLogin.setVisibility(View.VISIBLE);
                binding.tvRecoveryModeGuide.setVisibility(View.GONE);
                binding.btnSubmitPrimary.setText(R.string.m01_btn_submit_login);
                binding.btnQuickRecovery.setVisibility(View.VISIBLE);
            } else if (mode == M01LoginViewModel.AuthMode.SIGN_UP) {
                binding.tilPassword.setVisibility(View.VISIBLE);
                binding.tilPasswordConfirm.setVisibility(View.VISIBLE);
                binding.layoutTermsConsent.setVisibility(View.VISIBLE);
                binding.layoutAutoLogin.setVisibility(View.GONE);
                binding.tvRecoveryModeGuide.setVisibility(View.GONE);
                binding.btnSubmitPrimary.setText(R.string.m01_btn_submit_signup);
                binding.btnQuickRecovery.setVisibility(View.GONE);
            } else if (mode == M01LoginViewModel.AuthMode.RECOVERY) {
                binding.tilPassword.setVisibility(View.GONE);
                binding.tilPasswordConfirm.setVisibility(View.GONE);
                binding.layoutTermsConsent.setVisibility(View.GONE);
                binding.layoutAutoLogin.setVisibility(View.GONE);
                binding.tvRecoveryModeGuide.setVisibility(View.VISIBLE);
                binding.btnSubmitPrimary.setText(R.string.m01_btn_submit_recovery);
                binding.btnQuickRecovery.setVisibility(View.GONE);
            }
        });

        // FR-M01-3: 중복 제출 방지를 위한 UI 비활성화 및 진행 표시
        viewModel.getIsSubmitting().observe(getViewLifecycleOwner(), submitting -> {
            boolean busy = Boolean.TRUE.equals(submitting);
            binding.btnSubmitPrimary.setEnabled(!busy);
            binding.etEmail.setEnabled(!busy);
            binding.etPassword.setEnabled(!busy);
            binding.etPasswordConfirm.setEnabled(!busy);
            binding.cbTermsAll.setEnabled(!busy);
            binding.cbTermsService.setEnabled(!busy);
            binding.cbTermsPrivacy.setEnabled(!busy);
            binding.cbTermsAge14.setEnabled(!busy);
            binding.progressSubmitting.setVisibility(busy ? View.VISIBLE : View.GONE);
        });

        // FR-M01-3: 입력 오류(INPUT_ERROR)와 서버 오류(SERVER_ERROR) 시각적 구분
        viewModel.getCurrentError().observe(getViewLifecycleOwner(), error -> {
            clearFieldErrors();
            if (error == null) {
                binding.cardInputError.setVisibility(View.GONE);
                binding.cardServerError.setVisibility(View.GONE);
                return;
            }

            if (error.isServerError()) {
                binding.cardInputError.setVisibility(View.GONE);
                binding.cardServerError.setVisibility(View.VISIBLE);
                binding.tvServerErrorMessage.setText(error.getUserMessage());
            } else {
                binding.cardServerError.setVisibility(View.GONE);
                binding.cardInputError.setVisibility(View.VISIBLE);
                binding.tvInputErrorMessage.setText(error.getUserMessage());

                if (error.getField() == AuthError.Field.EMAIL) {
                    binding.tilEmail.setError(error.getUserMessage());
                } else if (error.getField() == AuthError.Field.PASSWORD) {
                    binding.tilPassword.setError(error.getUserMessage());
                } else if (error.getField() == AuthError.Field.PASSWORD_CONFIRM) {
                    binding.tilPasswordConfirm.setError(error.getUserMessage());
                }
            }
        });

        // 예외/완료: 계정 존재 여부를 노출하지 않는 일관된 복구 안내
        viewModel.getRecoveryGuidanceMessage().observe(getViewLifecycleOwner(), message -> {
            if (message == null || message.isEmpty()) {
                binding.cardRecoveryNotice.setVisibility(View.GONE);
            } else {
                binding.cardRecoveryNotice.setVisibility(View.VISIBLE);
                binding.tvRecoveryNoticeMessage.setText(message);
            }
        });

        // 로그인/가입 완료 후 M02(차량 없음) 또는 M03(차량 있음) / 푸시 딥링크 목적 화면 이동
        viewModel.getRouteEvent().observe(getViewLifecycleOwner(), decision -> {
            if (decision == null) {
                return;
            }
            viewModel.consumeRouteEvent();
            navigateByDecision(decision);
        });
    }

    private void refreshContextAndCacheBanner() {
        DeepLinkRouter.PendingDeepLink pending = viewModel.getDeepLinkRouter().getPendingDeepLink();
        String lastUserId = userCacheManager.getLastAuthenticatedUserId();
        boolean cacheVisible = userCacheManager.isCacheVisible();

        StringBuilder noticeBuilder = new StringBuilder();
        if (pending != null) {
            noticeBuilder.append("• 대기 중인 푸시 딥링크: [")
                    .append(pending.getFeatureLabel())
                    .append("]\n  로그인 완료 후 권한을 재검사하여 목적 화면으로 이동합니다.");
        }
        if (lastUserId != null && !cacheVisible) {
            if (noticeBuilder.length() > 0) {
                noticeBuilder.append("\n\n");
            }
            noticeBuilder.append("• 이전 세션 만료/로그아웃 보호: 이전 계정(")
                    .append(lastUserId)
                    .append(")의 로컬 캐시가 화면에서 숨김 처리되었습니다. 다른 계정으로 로그인 시 이전 캐시는 자동 초기화됩니다.");
        }

        if (noticeBuilder.length() > 0) {
            binding.cardContextNotice.setVisibility(View.VISIBLE);
            binding.tvContextNotice.setText(noticeBuilder.toString());
        } else {
            binding.cardContextNotice.setVisibility(View.GONE);
        }

        String cacheStatus = "캐시 보호 상태: "
                + (cacheVisible ? "활성 세션 캐시 표시 중" : "비로그인/세션만료 — 이전 계정 캐시 숨김됨")
                + (lastUserId != null ? " (직전 계정 ID: " + lastUserId + ")" : "");
        binding.tvCacheIsolationStatus.setText(cacheStatus);
    }

    private void navigateByDecision(@NonNull DeepLinkRouter.RouteDecision decision) {
        if (!isAdded()) {
            return;
        }
        Bundle args = new Bundle();
        if (decision.getPermissionToPromptOnArrival() != null) {
            args.putString(
                    ARG_PROMPT_PERMISSION_KEY,
                    decision.getPermissionToPromptOnArrival().getKey()
            );
        }

        NavOptions navOptions = new NavOptions.Builder()
                .setPopUpTo(R.id.nav_m01_login, true)
                .build();

        NavController navController = NavHostFragment.findNavController(this);
        if (decision.getDestination() == DeepLinkRouter.Destination.M03_HAS_VEHICLE) {
            navController.navigate(R.id.nav_m03_vehicle_home, args, navOptions);
        } else {
            navController.navigate(R.id.nav_m02_no_vehicle, args, navOptions);
        }
    }

    private void clearFieldErrors() {
        binding.tilEmail.setError(null);
        binding.tilPassword.setError(null);
        binding.tilPasswordConfirm.setError(null);
    }

    private void showTermsDetailDialog(@NonNull String title, @NonNull String content) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(content)
                .setPositiveButton("확인", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
