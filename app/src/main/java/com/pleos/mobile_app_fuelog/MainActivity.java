package com.pleos.mobile_app_fuelog;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavGraph;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.pleos.mobile_app_fuelog.core.navigation.DeepLinkRouter;
import com.pleos.mobile_app_fuelog.core.permission.PermissionGuideHelper;
import com.pleos.mobile_app_fuelog.databinding.ActivityMainBinding;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.ui.auth.M01LoginFragment;
import com.pleos.mobile_app_fuelog.ui.auth.M01LoginViewModel;

/**
 * 메인 액티비티.
 * - 좌측 상단 드로어 메뉴 없이 하단바(홈·운행·차계부·관리)로 모든 메뉴를 제공한다.
 * - 저장된 계정(유효 세션)이 있을 때는 M01 로그인 화면을 띄우지 않고 곧바로 M03(차량 있음) 또는 M02(차량 없음)로 진입한다.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private M01LoginViewModel loginViewModel;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        userCacheManager = UserCacheManager.getInstance(this);
        authRepository = AuthRepository.getInstance(this);
        loginViewModel = new ViewModelProvider(this).get(M01LoginViewModel.class);
        loginViewModel.init(authRepository);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_content_main);
        if (navHostFragment == null) {
            return;
        }
        navController = navHostFragment.getNavController();

        // 최초 실행 시 저장된 계정 유무에 따라 시작 화면(startDestination)을 결정 (로그인 화면 깜빡임 원천 차단)
        if (savedInstanceState == null) {
            NavGraph navGraph = navController.getNavInflater().inflate(R.navigation.mobile_navigation);
            AuthSession savedSession = authRepository.getCurrentValidSession();
            if (savedSession == null) {
                navGraph.setStartDestination(R.id.nav_m01_login);
            } else {
                boolean hasVehicle = userCacheManager.hasRegisteredVehicle(savedSession.getUserId());
                navGraph.setStartDestination(hasVehicle ? R.id.nav_m03_vehicle_home : R.id.nav_m02_no_vehicle);
            }
            navController.setGraph(navGraph);
        }

        // 화면 목적지에 따라 하단바 노출 여부 제어 (로그인·차량등록에서는 숨기고, 메인 홈에서는 표시)
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            int destId = destination.getId();
            if (destId == R.id.nav_m01_login || destId == R.id.nav_m02_no_vehicle) {
                binding.bottomNavMain.setVisibility(View.GONE);
            } else {
                binding.bottomNavMain.setVisibility(View.VISIBLE);
            }
        });

        setupBottomNavigation();
        handleIncomingIntent(getIntent());
    }

    private void setupBottomNavigation() {
        binding.bottomNavMain.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.bottom_tab_home) {
                if (navController.getCurrentDestination() != null
                        && navController.getCurrentDestination().getId() != R.id.nav_m03_vehicle_home) {
                    navController.navigate(R.id.nav_m03_vehicle_home);
                }
                return true;
            } else if (itemId == R.id.bottom_tab_driving) {
                Snackbar.make(binding.getRoot(),
                        "운행: 최근 운행 기록 및 운행 귀속(M04) 화면",
                        Snackbar.LENGTH_SHORT).show();
                return true;
            } else if (itemId == R.id.bottom_tab_ledger) {
                Snackbar.make(binding.getRoot(),
                        "차계부: 주유·정비 실제 지출 및 정산(M07/M09) 화면",
                        Snackbar.LENGTH_SHORT).show();
                return true;
            } else if (itemId == R.id.bottom_tab_manage) {
                Snackbar.make(binding.getRoot(),
                        "관리: 정비 예정 및 차량·계정 관리(M13) 화면",
                        Snackbar.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (navController == null) {
            return;
        }
        DeepLinkRouter.PendingDeepLink incomingDeepLink = DeepLinkRouter.parseFromIntent(intent);
        if (incomingDeepLink == null) {
            return;
        }

        loginViewModel.getDeepLinkRouter().setPendingDeepLink(incomingDeepLink);
        AuthSession validSession = authRepository.getCurrentValidSession();

        if (validSession == null) {
            navController.navigate(R.id.nav_m01_login);
        } else {
            boolean hasVehicle = userCacheManager.hasRegisteredVehicle(validSession.getUserId());
            DeepLinkRouter.RouteDecision decision = loginViewModel.getDeepLinkRouter().resolvePostLoginRoute(
                    hasVehicle,
                    perm -> PermissionGuideHelper.isPermissionGranted(this, perm)
            );
            Bundle args = new Bundle();
            if (decision.getPermissionToPromptOnArrival() != null) {
                args.putString(
                        M01LoginFragment.ARG_PROMPT_PERMISSION_KEY,
                        decision.getPermissionToPromptOnArrival().getKey()
                );
            }
            NavOptions navOptions = new NavOptions.Builder()
                    .setPopUpTo(R.id.nav_m01_login, true)
                    .build();
            if (decision.getDestination() == DeepLinkRouter.Destination.M03_HAS_VEHICLE) {
                navController.navigate(R.id.nav_m03_vehicle_home, args, navOptions);
            } else {
                navController.navigate(R.id.nav_m02_no_vehicle, args, navOptions);
            }
        }
    }
}
