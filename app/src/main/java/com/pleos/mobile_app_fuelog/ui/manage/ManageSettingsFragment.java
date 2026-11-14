package com.pleos.mobile_app_fuelog.ui.manage;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.connection.M04DriverConnectionCoordinator;
import com.pleos.mobile_app_fuelog.data.connection.RegisteredPleosDevice;
import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.databinding.FragmentManageSettingsBinding;
import com.pleos.mobile_app_fuelog.ui.auth.M01LoginViewModel;
import com.pleos.mobile_app_fuelog.ui.vehicle.VehicleRegisterActivity;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * [관리·설정 탭] - iOS Inset Grouped Settings 스타일 UI
 * - 깔끔한 섹션 그룹 카드(차량 관리 / 주행 및 추천 / 계정) 리스트 UI 제공
 * - "차량 등록" 항목 클릭 시 전용 {@link VehicleRegisterActivity}로 화면을 완전히 이동하여
 *   카메라 자원과 라이프사이클을 격리하고 쾌적한 QR 연동 경험을 제공한다.
 */
public class ManageSettingsFragment extends Fragment {

    private FragmentManageSettingsBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private M01LoginViewModel loginViewModel;
    private M04DriverConnectionCoordinator coordinator;

    private final ActivityResultLauncher<Intent> registerVehicleLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    renderAll();
                    syncLatestVehicleFromServerDb();
                }
            });

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        binding = FragmentManageSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authRepository = AuthRepository.getInstance(requireContext());
        userCacheManager = UserCacheManager.getInstance(requireContext());
        loginViewModel = new ViewModelProvider(requireActivity()).get(M01LoginViewModel.class);
        loginViewModel.init(authRepository);
        coordinator = M04DriverConnectionCoordinator.getInstance();

        com.google.android.material.button.MaterialButton disconnect = new com.google.android.material.button.MaterialButton(requireContext());
        disconnect.setText("차량 연결 끊기");
        ((ViewGroup) binding.rowManageLogout.getParent()).addView(disconnect);
        String disconnectOperation = java.util.UUID.randomUUID().toString();
        disconnect.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle("차량 연결을 끊을까요?")
                .setMessage("차량에서도 사용자 연결이 해제됩니다. 저장된 기록은 유지됩니다.")
                .setNegativeButton("취소", null).setPositiveButton("연결 끊기", (dialog, which) -> {
                    AuthSession session = authRepository.getCurrentValidSession();
                    if (session == null) return;
                    String vehicle = userCacheManager.getRegisteredVehicleUuid(session.getUserId());
                    if (vehicle == null) return;
                    disconnect.setEnabled(false);
                    Thread request = new Thread(() -> {
                        boolean success = FuelogBackendApiClient.getInstance(authRepository).disconnectVehicle(vehicle, disconnectOperation);
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            if (binding == null) return;
                            disconnect.setEnabled(true);
                            Snackbar.make(binding.getRoot(), success ? "서버 연결 해제 완료" : "연결 해제 실패 · 다시 시도해 주세요", Snackbar.LENGTH_LONG).show();
                        });
                    });
                    request.start();
                }).show());
        setupListeners();
        renderAll();
        syncLatestVehicleFromServerDb();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderAll();
        syncLatestVehicleFromServerDb();
    }

    private void syncLatestVehicleFromServerDb() {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) return;
        String userId = session.getUserId();
        String instSecret = userCacheManager.getInstallationSecret(userId);
        if (instSecret == null || instSecret.isEmpty()) return;

        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient api = FuelogBackendApiClient.getInstance(authRepository);
            List<FuelogBackendApiClient.BackendVehicleItem> vehicles = api.fetchMobileVehicles();
            String currentVid = userCacheManager.getRegisteredVehicleUuid(userId);
            if (vehicles != null && !vehicles.isEmpty()) {
                for (FuelogBackendApiClient.BackendVehicleItem v : vehicles) {
                    String vid = v.vehicleId != null ? v.vehicleId : v.id;
                    userCacheManager.setServerVehicleName(vid, v.getDisplayName());
                }
                String effective = null;
                if (currentVid != null) {
                    effective = userCacheManager.getEffectiveVehicleName(currentVid);
                }
                if (effective == null) {
                    String firstVid = vehicles.get(0).vehicleId != null ? vehicles.get(0).vehicleId : vehicles.get(0).id;
                    effective = userCacheManager.getEffectiveVehicleName(firstVid);
                    if (effective == null) {
                        effective = vehicles.get(0).getDisplayName();
                    }
                }
                userCacheManager.setRegisteredVehicleForUser(userId, effective.trim());
                coordinator.setVehicleAlias(effective.trim());
                if (isAdded() && getActivity() != null) {
                    requireActivity().runOnUiThread(this::renderAll);
                }
            }
        });
    }

    private void setupListeners() {
        // 1. 내 차량 (로컬 별칭 수정 다이얼로그)
        binding.rowManageCurrentVehicle.setOnClickListener(v -> showEditAliasDialog());

        // 2. 차량 등록 (전용 VehicleRegisterActivity로 화면 전환)
        binding.rowManageRegisterVehicle.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), VehicleRegisterActivity.class);
            registerVehicleLauncher.launch(intent);
        });

        // 3. 연동 단말기 (단말 정보 및 연동 해제 팝업)
        binding.rowManageDevices.setOnClickListener(v -> showDevicesDialog());

        // 4. 자동 추천 허용 스위치
        binding.switchManageAutoRecommend.setChecked(coordinator.isAutoRecommendAllowed());
        binding.switchManageAutoRecommend.setOnCheckedChangeListener((buttonView, isChecked) ->
                coordinator.setAutoRecommendAllowed(isChecked)
        );

        // 5. 세션 만료 테스트
        binding.rowManageExpireSession.setOnClickListener(v -> {
            authRepository.expireCurrentSession();
            navigateToM01();
        });

        // 6. 로그아웃 (다른 계정 전환)
        binding.rowManageLogout.setOnClickListener(v -> showLogoutConfirmDialog());
    }

    private void renderAll() {
        if (binding == null) {
            return;
        }
        AuthSession session = authRepository.getCurrentValidSession();
        String cachedVehicle = userCacheManager.getVisibleCachedVehicleName();

        if (cachedVehicle != null && !cachedVehicle.trim().isEmpty()) {
            coordinator.setVehicleAlias(cachedVehicle);
            binding.tvManageCurrentVehicleName.setText(cachedVehicle);
        } else {
            binding.tvManageCurrentVehicleName.setText("미등록");
        }

        List<RegisteredPleosDevice> devices = coordinator.getRegisteredDevices();
        if (devices.isEmpty()) {
            binding.tvManageDevicesStatus.setText("미연동");
        } else {
            binding.tvManageDevicesStatus.setText(devices.size() + "대 연결됨");
        }

        if (session != null) {
            binding.tvManageAccountEmail.setText(session.getEmail());
        } else {
            binding.tvManageAccountEmail.setText("미로그인");
        }
    }

    private void showDevicesDialog() {
        List<RegisteredPleosDevice> devices = coordinator.getRegisteredDevices();
        if (devices.isEmpty()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("연동 단말기")
                    .setMessage("현재 QR 인식으로 연동된 차량 단말기가 없습니다.\n'차량 등록' 메뉴에서 차량 화면의 QR 코드를 스캔해 주세요.")
                    .setPositiveButton("차량 등록하기", (d, w) -> {
                        Intent intent = new Intent(requireContext(), VehicleRegisterActivity.class);
                        registerVehicleLauncher.launch(intent);
                    })
                    .setNegativeButton("닫기", null)
                    .show();
            return;
        }

        RegisteredPleosDevice dev = devices.get(0);
        String msg = "• 단말기 이름: " + dev.getDeviceName()
                + "\n• 식별 코드: " + dev.getMacAddress()
                + "\n• 대상 차량: " + dev.getVehicleAlias();

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("연동 단말기 관리")
                .setMessage(msg)
                .setPositiveButton("확인", null)
                .setNegativeButton("단말 연동 해제", (d, w) -> {
                    AuthSession s = authRepository.getCurrentValidSession();
                    if (s != null) {
                        String instSecret = userCacheManager.getInstallationSecret(s.getUserId());
                        String vehUuid = userCacheManager.getRegisteredVehicleUuid(s.getUserId());
                        if (instSecret != null && vehUuid != null) {
                            Executors.newSingleThreadExecutor().execute(() ->
                                    FuelogBackendApiClient.getInstance(authRepository)
                                            .resetVehicleConnection(instSecret, vehUuid)
                            );
                        }
                        userCacheManager.setRegisteredVehicleForUser(s.getUserId(), null);
                        userCacheManager.setRegisteredVehicleUuid(s.getUserId(), null);
                    }
                    coordinator.removeAllRegisteredDevices();
                    renderAll();
                    Snackbar.make(binding.getRoot(), "차량 단말 연동이 해제되었습니다.", Snackbar.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showEditAliasDialog() {
        AuthSession session = authRepository.getCurrentValidSession();
        if (session == null) {
            navigateToM01();
            return;
        }
        String userId = session.getUserId();
        String vehicleId = userCacheManager.getRegisteredVehicleUuid(userId);
        if (vehicleId == null || vehicleId.isEmpty()) {
            Snackbar.make(binding.getRoot(), "선택된 차량이 없습니다.", Snackbar.LENGTH_SHORT).show();
            return;
        }

        String serverName = userCacheManager.getServerVehicleName(vehicleId);
        if (serverName == null || serverName.isEmpty()) {
            com.pleos.mobile_app_fuelog.data.home.VehicleProfile vp =
                    M03HomeRepository.getInstance().getVehicleProfile(vehicleId);
            if (vp != null && !vp.getDisplayName().isEmpty()) {
                serverName = vp.getDisplayName();
                userCacheManager.setServerVehicleName(vehicleId, serverName);
            }
        }
        String customAlias = userCacheManager.getVehicleCustomAlias(vehicleId);
        String currentDisplay = (customAlias != null && !customAlias.isEmpty())
                ? customAlias
                : (serverName != null && !serverName.isEmpty() ? serverName : "내 차량");
        String hintName = (serverName != null && !serverName.isEmpty()) ? serverName : "내 차량";

        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(hintName);
        input.setHintTextColor(android.graphics.Color.parseColor("#9E9E9E"));
        input.setText(currentDisplay);
        input.setSelection(currentDisplay.length());

        android.widget.FrameLayout container = new android.widget.FrameLayout(requireContext());
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        int marginHorizontal = (int) (24 * getResources().getDisplayMetrics().density);
        int marginVertical = (int) (8 * getResources().getDisplayMetrics().density);
        params.leftMargin = marginHorizontal;
        params.rightMargin = marginHorizontal;
        params.topMargin = marginVertical;
        params.bottomMargin = marginVertical;
        input.setLayoutParams(params);
        container.addView(input);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("차량 별명 수정")
                .setMessage("앱 내에서 사용할 차량 별명을 설정합니다.\n(내용을 모두 지우고 확인을 누르면 서버 기본 이름으로 초기화됩니다)")
                .setView(container)
                .setPositiveButton("확인", (dialog, which) -> {
                    String entered = input.getText() != null ? input.getText().toString().trim() : "";
                    String finalName;
                    if (entered.isEmpty()) {
                        // 모두 지우고 확인 누르면 서버값 사용
                        userCacheManager.setVehicleCustomAlias(vehicleId, null);
                        String sName = userCacheManager.getServerVehicleName(vehicleId);
                        finalName = (sName != null && !sName.isEmpty()) ? sName : hintName;
                        Snackbar.make(binding.getRoot(), "서버 기본 차량명으로 복원되었습니다: " + finalName, Snackbar.LENGTH_SHORT).show();
                    } else {
                        // 로컬에서만 별명 수정 (서버에 전송하지 않음)
                        userCacheManager.setVehicleCustomAlias(vehicleId, entered);
                        finalName = entered;
                        Snackbar.make(binding.getRoot(), "차량 별명이 로컬에 저장되었습니다: " + finalName, Snackbar.LENGTH_SHORT).show();
                    }

                    userCacheManager.setRegisteredVehicleForUser(userId, finalName);
                    coordinator.setVehicleAlias(finalName);
                    M03HomeRepository.getInstance().updateVehicleDisplayName(vehicleId, finalName);
                    renderAll();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showLogoutConfirmDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("로그아웃")
                .setMessage("현재 계정에서 로그아웃하시겠습니까?")
                .setPositiveButton("로그아웃", (d, w) -> {
                    Executors.newSingleThreadExecutor().execute(() ->
                            FuelogBackendApiClient.getInstance(authRepository).revokeActiveSessionAndConsent(userCacheManager)
                    );
                    authRepository.signOut();
                    navigateToM01();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void navigateToM01() {
        NavOptions navOptions = new NavOptions.Builder()
                .setPopUpTo(R.id.mobile_navigation, true)
                .build();
        NavHostFragment.findNavController(this).navigate(R.id.nav_m01_login, null, navOptions);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
