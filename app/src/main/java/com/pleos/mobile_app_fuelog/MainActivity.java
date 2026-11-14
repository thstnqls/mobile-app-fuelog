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
 * - 저장된 계정(유효 세션)이 있을 때는 M01 로그인 화면을 띄우지 않고 곧바로 진입한다.
 * - 차량 연동은 Bluetooth SPP 없이 차량 화면(C01)의 QR 코드 인식만을 통해 수행한다.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private M01LoginViewModel loginViewModel;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private NavController navController;

    private final android.os.Handler connectionHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService connectionExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean connectionVisible, connectionChecking;
    private String disconnectedVehicle = "";
    private final Runnable connectionCheck = new Runnable() {
        @Override public void run() {
            if (!connectionVisible) return;
            AuthSession session = authRepository.getCurrentValidSession();
            String vehicle = session == null ? null : userCacheManager.getRegisteredVehicleUuid(session.getUserId());
            if (!connectionChecking && vehicle != null) {
                connectionChecking = true;
                String user = session.getUserId();
                connectionExecutor.execute(() -> {
                    Boolean paired = com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient.getInstance(authRepository).vehicleConnectionState(vehicle, userCacheManager);
                    connectionHandler.post(() -> {
                        connectionChecking = false;
                        AuthSession current = authRepository.getCurrentValidSession();
                        if (!connectionVisible || current == null || !user.equals(current.getUserId())
                                || !vehicle.equals(userCacheManager.getRegisteredVehicleUuid(user))) return;
                        if (Boolean.TRUE.equals(paired)) disconnectedVehicle = "";
                        if (Boolean.FALSE.equals(paired) && !vehicle.equals(disconnectedVehicle)) {
                            disconnectedVehicle = vehicle;
                            userCacheManager.setActiveScopedToken(null);
                            userCacheManager.setActiveConsentId(null);
                            userCacheManager.setActiveCandidateId(null);
                            com.pleos.mobile_app_fuelog.data.connection.M04DriverConnectionCoordinator.getInstance().onServerDisconnected();
                            navController.navigate(R.id.nav_m04_driver_connection, null,
                                    new NavOptions.Builder().setPopUpTo(R.id.mobile_navigation, false).build());
                            Snackbar.make(binding.getRoot(), "차량 연결이 해제되었습니다. QR로 다시 연결해 주세요.", Snackbar.LENGTH_LONG).show();
                        }
                    });
                });
            }
            connectionHandler.postDelayed(this, 5000);
        }
    };

    @Override protected void onStart() {
        super.onStart();
        connectionVisible = true;
        connectionHandler.post(connectionCheck);
    }
    @Override protected void onStop() {
        connectionVisible = false;
        connectionHandler.removeCallbacks(connectionCheck);
        super.onStop();
    }
    @Override protected void onDestroy() {
        connectionExecutor.shutdownNow();
        super.onDestroy();
    }

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

        if (savedInstanceState == null) {
            NavGraph navGraph = navController.getNavInflater().inflate(R.navigation.mobile_navigation);
            AuthSession savedSession = authRepository.getCurrentValidSession();
            if (savedSession == null || !authRepository.isAutoLoginEnabled()) {
                navGraph.setStartDestination(R.id.nav_m01_login);
            } else {
                boolean hasVehicle = userCacheManager.hasRegisteredVehicle(savedSession.getUserId());
                navGraph.setStartDestination(hasVehicle ? R.id.nav_m03_vehicle_home : R.id.nav_m02_no_vehicle);
            }
            navController.setGraph(navGraph);
        }

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
                if (navController.getCurrentDestination() != null
                        && navController.getCurrentDestination().getId() != R.id.nav_m05_trip_calendar) {
                    navController.navigate(R.id.nav_m05_trip_calendar);
                }
                return true;
            } else if (itemId == R.id.bottom_tab_ledger) {
                if (navController.getCurrentDestination() != null
                        && navController.getCurrentDestination().getId() != R.id.nav_m10_ledger) {
                    navController.navigate(R.id.nav_m10_ledger);
                }
                return true;
            } else if (itemId == R.id.bottom_tab_manage) {
                if (navController.getCurrentDestination() != null
                        && navController.getCurrentDestination().getId() != R.id.nav_manage_settings) {
                    navController.navigate(R.id.nav_manage_settings);
                }
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
