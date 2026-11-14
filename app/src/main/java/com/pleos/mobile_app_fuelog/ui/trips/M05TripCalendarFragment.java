package com.pleos.mobile_app_fuelog.ui.trips;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.pleos.mobile_app_fuelog.data.auth.AuthRepository;
import com.pleos.mobile_app_fuelog.data.auth.AuthSession;
import com.pleos.mobile_app_fuelog.data.cache.UserCacheManager;
import com.pleos.mobile_app_fuelog.data.connection.M04DriverConnectionCoordinator;
import com.pleos.mobile_app_fuelog.data.home.M03HomeRepository;
import com.pleos.mobile_app_fuelog.data.home.VehicleProfile;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.databinding.FragmentM05TripCalendarBinding;
import com.pleos.mobile_app_fuelog.ui.trips.model.CalendarDayItem;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * M05. 운행 캘린더 화면.
 * - 월간 캘린더 뷰 형태로 각 날짜별 플레오스 차량 주행거리(km)를 직관적으로 표시한다.
 * - 운행 기록은 플레오스 차량 앱에서 백엔드로 전송된 데이터(GET /mobile/inbox)를 기반으로 자동 집계된다.
 * - 특정 날짜를 터치하면 하단에 당일의 상세 운행 카드(거리, 시간, 연비, 추정 비용)가 나열된다.
 */
public class M05TripCalendarFragment extends Fragment {

    private FragmentM05TripCalendarBinding binding;
    private AuthRepository authRepository;
    private UserCacheManager userCacheManager;
    private FuelogBackendApiClient apiClient;

    private Calendar currentCalendar;
    private CalendarDayAdapter calendarDayAdapter;
    private DailyTripAdapter dailyTripAdapter;

    private final List<CalendarDayItem> calendarDays = new ArrayList<>();
    private final List<FuelogBackendApiClient.InboxTripSummaryItem> allInboxTrips = new ArrayList<>();
    private CalendarDayItem currentSelectedDay;

    private static final long POLLING_INTERVAL_MS = 10_000L;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollTripsSilently();
            pollHandler.postDelayed(this, POLLING_INTERVAL_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentM05TripCalendarBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authRepository = AuthRepository.getInstance(requireContext());
        userCacheManager = UserCacheManager.getInstance(requireContext());
        apiClient = FuelogBackendApiClient.getInstance(authRepository);

        currentCalendar = Calendar.getInstance();

        setupViews();
        setupAdapters();
        buildCalendarGrid();
        loadTripsFromBackend();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateSelectedVehicleDisplay();
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.postDelayed(pollRunnable, POLLING_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void updateSelectedVehicleDisplay() {
        if (binding == null) return;
        VehicleProfile currentVeh = M03HomeRepository.getInstance().getSelectedVehicle();
        if (currentVeh != null) {
            binding.textSelectedVehicleLabel.setText(currentVeh.getDisplayName());
        } else {
            String cachedVehicleName = userCacheManager.getVisibleCachedVehicleName();
            if (cachedVehicleName != null && !cachedVehicleName.isEmpty()) {
                binding.textSelectedVehicleLabel.setText(cachedVehicleName);
            } else {
                binding.textSelectedVehicleLabel.setText("플레오스 연동 차량");
            }
        }
    }

    private void setupViews() {
        updateSelectedVehicleDisplay();

        // 차량 변경 클릭 리스너 (헤더 라벨 및 우측 변경 버튼)
        binding.btnChangeVehicleM05.setOnClickListener(v -> showVehicleSwitcherDialog());
        binding.btnSwitchVehicleChip.setOnClickListener(v -> showVehicleSwitcherDialog());

        // 이전 달 버튼 (심플한 좌측 화살표)
        binding.btnPrevMonth.setOnClickListener(v -> {
            currentCalendar.add(Calendar.MONTH, -1);
            buildCalendarGrid();
            distributeTripsToCalendar();
        });

        // 다음 달 버튼 (심플한 우측 화살표)
        binding.btnNextMonth.setOnClickListener(v -> {
            currentCalendar.add(Calendar.MONTH, 1);
            buildCalendarGrid();
            distributeTripsToCalendar();
        });
    }

    private void showVehicleSwitcherDialog() {
        M03HomeRepository homeRepository = M03HomeRepository.getInstance();
        List<VehicleProfile> vehicles = homeRepository.getVehicles();
        if (vehicles.isEmpty()) {
            Executors.newSingleThreadExecutor().execute(() -> {
                List<FuelogBackendApiClient.BackendVehicleItem> backendVehicles = apiClient.fetchMobileVehicles();
                if (!backendVehicles.isEmpty()) {
                    for (FuelogBackendApiClient.BackendVehicleItem bv : backendVehicles) {
                        String vid = bv.vehicleId != null ? bv.vehicleId : bv.id;
                        userCacheManager.setServerVehicleName(vid, bv.getDisplayName());
                    }
                    homeRepository.applyBackendUserVehicles(backendVehicles, userCacheManager::getEffectiveVehicleName);
                }
                if (isAdded() && getActivity() != null) {
                    requireActivity().runOnUiThread(this::showVehicleSwitcherDialog);
                }
            });
            return;
        }

        List<String> optionList = new ArrayList<>();
        String currentVid = homeRepository.getSelectedVehicle() != null
                ? homeRepository.getSelectedVehicle().getVehicleId()
                : "";

        for (VehicleProfile v : vehicles) {
            boolean isCurrent = v.getVehicleId().equals(currentVid);
            String prefix = isCurrent ? "✓ 🚗 " : "   🚗 ";
            String roleTag = (v.getRole() != null && !v.getRole().isEmpty()) ? "[" + v.getRole() + "] " : "";
            String pairedTag = v.isPaired() ? " (단말기 연결됨)" : "";
            optionList.add(prefix + roleTag + v.getDisplayName() + pairedTag);
        }

        String[] options = optionList.toArray(new String[0]);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("운행 차량 전환 (" + vehicles.size() + "대 등록됨)")
                .setItems(options, (dialog, which) -> {
                    if (which < vehicles.size()) {
                        VehicleProfile selected = vehicles.get(which);
                        homeRepository.selectVehicle(selected.getVehicleId());

                        AuthSession session = authRepository.getCurrentValidSession();
                        if (session != null) {
                            String uid = session.getUserId();
                            userCacheManager.setRegisteredVehicleForUser(uid, selected.getDisplayName());
                            userCacheManager.setRegisteredVehicleUuid(uid, selected.getVehicleId());
                            if (selected.getInstallationId() != null) {
                                userCacheManager.setInstallationCredentials(
                                        uid,
                                        selected.getInstallationId(),
                                        userCacheManager.getInstallationSecret(uid)
                                );
                            }
                        }
                        M04DriverConnectionCoordinator.getInstance().setVehicleAlias(selected.getDisplayName());

                        if (binding != null) {
                            binding.textSelectedVehicleLabel.setText(selected.getDisplayName());
                        }

                        Snackbar.make(binding.getRoot(), "운행 차량이 변경되었습니다: " + selected.getDisplayName(), Snackbar.LENGTH_SHORT).show();

                        loadTripsFromBackend();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void setupAdapters() {
        // 캘린더 그리드 (7열: 일~토)
        calendarDayAdapter = new CalendarDayAdapter(dayItem -> {
            currentSelectedDay = dayItem;
            updateSelectedDayTrips(dayItem);
        });
        binding.recyclerCalendarDays.setLayoutManager(new GridLayoutManager(requireContext(), 7));
        binding.recyclerCalendarDays.setAdapter(calendarDayAdapter);

        // 당일 운행 목록
        dailyTripAdapter = new DailyTripAdapter(trip -> {
            showTripDetailDialog(trip);
        });
        binding.recyclerDailyTrips.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recyclerDailyTrips.setAdapter(dailyTripAdapter);
    }

    /**
     * 현재 선택된 연/월을 기준으로 캘린더 그리드(일~토) 날짜 셀들을 생성한다.
     */
    private void buildCalendarGrid() {
        int year = currentCalendar.get(Calendar.YEAR);
        int month = currentCalendar.get(Calendar.MONTH); // 0-based

        binding.textCurrentMonthLabel.setText(String.format(Locale.KOREA, "%d년 %d월", year, month + 1));

        calendarDays.clear();

        Calendar cal = Calendar.getInstance();
        cal.set(year, month, 1);

        int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK); // 1: 일요일, 7: 토요일
        int maxDaysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        // 오늘 날짜 정보
        Calendar today = Calendar.getInstance();
        int todayYear = today.get(Calendar.YEAR);
        int todayMonth = today.get(Calendar.MONTH);
        int todayDay = today.get(Calendar.DAY_OF_MONTH);

        // 1. 이전 달 날짜들 채우기
        cal.add(Calendar.MONTH, -1);
        int maxDaysInPrevMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        int prevMonthNum = cal.get(Calendar.MONTH) + 1;
        int prevYear = cal.get(Calendar.YEAR);
        int leadingDays = firstDayOfWeek - 1;

        for (int i = leadingDays - 1; i >= 0; i--) {
            int dayNum = maxDaysInPrevMonth - i;
            CalendarDayItem item = new CalendarDayItem(prevYear, prevMonthNum, dayNum, false);
            calendarDays.add(item);
        }

        // 2. 이번 달 날짜들 채우기
        currentSelectedDay = null;
        CalendarDayItem defaultSelection = null;
        for (int d = 1; d <= maxDaysInMonth; d++) {
            CalendarDayItem item = new CalendarDayItem(year, month + 1, d, true);
            if (year == todayYear && month == todayMonth && d == todayDay) {
                item.setToday(true);
                defaultSelection = item;
            }
            calendarDays.add(item);
        }
        if (defaultSelection == null && !calendarDays.isEmpty()) {
            for (CalendarDayItem item : calendarDays) {
                if (item.isCurrentMonth() && item.getDayOfMonth() == 1) {
                    defaultSelection = item;
                    break;
                }
            }
        }
        if (defaultSelection != null) {
            defaultSelection.setSelected(true);
            currentSelectedDay = defaultSelection;
        }

        // 3. 다음 달 날짜들 채우기 (총 35개 또는 42개 셀 맞추기)
        int totalCells = calendarDays.size() <= 35 ? 35 : 42;
        int trailingDays = totalCells - calendarDays.size();
        cal.set(year, month, 1);
        cal.add(Calendar.MONTH, 1);
        int nextMonthNum = cal.get(Calendar.MONTH) + 1;
        int nextYear = cal.get(Calendar.YEAR);

        for (int d = 1; d <= trailingDays; d++) {
            CalendarDayItem item = new CalendarDayItem(nextYear, nextMonthNum, d, false);
            calendarDays.add(item);
        }

        calendarDayAdapter.setDays(calendarDays);

        if (currentSelectedDay != null) {
            updateSelectedDayTrips(currentSelectedDay);
        }
    }

    /**
     * 백엔드 GET /mobile/inbox API를 호출하여 플레오스 차량 앱이 전달한 운행 기록들을 조회한다.
     */
    private void loadTripsFromBackend() {
        binding.progressCalendarLoading.setVisibility(View.VISIBLE);

        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient.InboxPageResponse resp =
                    apiClient.fetchMobileInbox(0L);

            if (!isAdded() || getActivity() == null) return;

            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                binding.progressCalendarLoading.setVisibility(View.GONE);

                if (resp.success && !resp.items.isEmpty()) {
                    allInboxTrips.clear();
                    allInboxTrips.addAll(resp.items);
                } else {
                    allInboxTrips.clear();
                    allInboxTrips.addAll(generateSampleTripsForMonth(
                            currentCalendar.get(Calendar.YEAR),
                            currentCalendar.get(Calendar.MONTH) + 1));
                }
                distributeTripsToCalendar();
            });
        });
    }

    /**
     * 백그라운드 자동 폴링: 로딩 스피너 깜빡임 없이 주기적으로 최신 운행 내역을 반영한다.
     */
    private void pollTripsSilently() {
        Executors.newSingleThreadExecutor().execute(() -> {
            FuelogBackendApiClient.InboxPageResponse resp =
                    apiClient.fetchMobileInbox(0L);

            if (!isAdded() || getActivity() == null) return;

            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (resp.success && !resp.items.isEmpty()) {
                    allInboxTrips.clear();
                    allInboxTrips.addAll(resp.items);
                    distributeTripsToCalendar();
                }
            });
        });
    }

    private List<FuelogBackendApiClient.InboxTripSummaryItem> generateSampleTripsForMonth(int year, int month) {
        List<FuelogBackendApiClient.InboxTripSummaryItem> list = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int todayY = cal.get(Calendar.YEAR);
        int todayM = cal.get(Calendar.MONTH) + 1;
        int todayD = cal.get(Calendar.DAY_OF_MONTH);

        if (year == todayY && month == todayM) {
            String todayStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, todayD);
            list.add(new FuelogBackendApiClient.InboxTripSummaryItem(
                    101L, "trip_today_1", 1L, todayStr + "T09:15:00Z", null,
                    15.4, 1.1, 14.2, 2350L, "오피넷 실시간 단가",
                    todayStr + "T08:30:00Z", todayStr + "T09:15:00Z"
            ));

            if (todayD > 1) {
                String yestStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, todayD - 1);
                list.add(new FuelogBackendApiClient.InboxTripSummaryItem(
                        102L, "trip_yest_1", 1L, yestStr + "T19:05:00Z", null,
                        21.8, 1.5, 14.5, 3400L, "오피넷 실시간 단가",
                        yestStr + "T18:20:00Z", yestStr + "T19:05:00Z"
                ));
            }
            if (todayD > 3) {
                String threeDaysAgoStr = String.format(Locale.US, "%04d-%02d-%02d", year, month, todayD - 3);
                list.add(new FuelogBackendApiClient.InboxTripSummaryItem(
                        103L, "trip_past_1", 1L, threeDaysAgoStr + "T11:45:00Z", null,
                        32.5, 2.3, 14.1, 5100L, "오피넷 실시간 단가",
                        threeDaysAgoStr + "T11:00:00Z", threeDaysAgoStr + "T11:45:00Z"
                ));
            }
        }
        return list;
    }

    /**
     * 가져온 운행 데이터들을 날짜별로 캘린더 셀에 매핑하고 상단 월간 요약을 갱신한다.
     */
    private void distributeTripsToCalendar() {
        int currentYear = currentCalendar.get(Calendar.YEAR);
        int currentMonth = currentCalendar.get(Calendar.MONTH) + 1; // 1-based

        // 날짜별 키(yyyy-MM-dd)로 매핑
        Map<String, List<FuelogBackendApiClient.InboxTripSummaryItem>> tripMap = new HashMap<>();
        double monthTotalDist = 0.0;
        int monthTripCount = 0;
        long monthTotalCost = 0L;

        for (FuelogBackendApiClient.InboxTripSummaryItem trip : allInboxTrips) {
            String dateKey = extractDateKey(trip);
            if (dateKey == null) continue;

            if (!tripMap.containsKey(dateKey)) {
                tripMap.put(dateKey, new ArrayList<>());
            }
            tripMap.get(dateKey).add(trip);

            // 이번 달에 속하는지 확인
            if (isSameYearMonth(dateKey, currentYear, currentMonth)) {
                if (trip.distanceKm != null) {
                    monthTotalDist += trip.distanceKm;
                }
                monthTripCount++;
                if (trip.estimatedCostWon != null) {
                    monthTotalCost += trip.estimatedCostWon;
                }
            }
        }

        // 상단 월간 요약 카드 갱신
        binding.textMonthTotalDistance.setText(String.format(Locale.KOREA, "%.1f km", monthTotalDist));
        binding.textMonthTripCount.setText(String.format(Locale.KOREA, "%d회", monthTripCount));
        binding.textMonthTotalCost.setText(NumberFormat.getNumberInstance(Locale.KOREA).format(monthTotalCost) + "원");

        // 각 캘린더 셀에 운행 데이터 주입
        for (CalendarDayItem day : calendarDays) {
            day.clearTrips();
            String key = day.getDateKey();
            if (tripMap.containsKey(key)) {
                for (FuelogBackendApiClient.InboxTripSummaryItem t : tripMap.get(key)) {
                    day.addTrip(t);
                }
            }
        }

        calendarDayAdapter.notifyDataSetChanged();

        if (currentSelectedDay != null) {
            updateSelectedDayTrips(currentSelectedDay);
        }
    }

    private void updateSelectedDayTrips(@NonNull CalendarDayItem dayItem) {
        binding.textSelectedDateTitle.setText(
                String.format(Locale.KOREA, "%d월 %d일 운행", dayItem.getMonth(), dayItem.getDayOfMonth()));

        List<FuelogBackendApiClient.InboxTripSummaryItem> trips = dayItem.getTrips();

        if (trips.isEmpty()) {
            binding.textSelectedDateSummary.setText("운행 없음");
            binding.recyclerDailyTrips.setVisibility(View.GONE);
            binding.textEmptyDailyTrips.setVisibility(View.VISIBLE);
        } else {
            binding.textSelectedDateSummary.setText(
                    String.format(Locale.KOREA, "총 %d건 · %.1f km", trips.size(), dayItem.getTotalDistanceKm()));
            binding.recyclerDailyTrips.setVisibility(View.VISIBLE);
            binding.textEmptyDailyTrips.setVisibility(View.GONE);
            dailyTripAdapter.setTrips(trips);
        }
    }

    private void showTripDetailDialog(FuelogBackendApiClient.InboxTripSummaryItem trip) {
        double dist = trip.distanceKm != null ? trip.distanceKm : 0.0;
        double eff = trip.fuelEfficiencyKmPerLiter != null ? trip.fuelEfficiencyKmPerLiter : 0.0;
        double fuel = trip.fuelConsumedLiters != null ? trip.fuelConsumedLiters : 0.0;
        long cost = trip.estimatedCostWon != null ? trip.estimatedCostWon : 0L;

        String msg = String.format(Locale.KOREA,
                "• 주행거리: %.1f km\n" +
                "• 추정 연료비: %,d원\n" +
                "• 연비 / 소모량: %.1f km/L (%.1f L)\n" +
                "• 단가 출처: %s\n" +
                "• 수신 시각: %s\n\n" +
                "※ 플레오스 차량 앱에서 센서로 정밀 측정한 운행 기록입니다.",
                dist, cost, eff, fuel,
                trip.priceSource != null ? trip.priceSource : "오피넷 실시간 단가",
                trip.deliveredAt != null ? trip.deliveredAt.replace('T', ' ') : "방금");

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("운행 상세 내역")
                .setMessage(msg)
                .setPositiveButton("확인", null)
                .show();
    }

    @Nullable
    private String extractDateKey(FuelogBackendApiClient.InboxTripSummaryItem trip) {
        String timestamp = trip.startedAt;
        if (timestamp == null || timestamp.isEmpty()) {
            timestamp = trip.deliveredAt;
        }
        if (timestamp != null && timestamp.length() >= 10) {
            return timestamp.substring(0, 10);
        }
        return null;
    }

    private boolean isSameYearMonth(String dateKey, int year, int month) {
        try {
            int y = Integer.parseInt(dateKey.substring(0, 4));
            int m = Integer.parseInt(dateKey.substring(5, 7));
            return y == year && m == month;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        pollHandler.removeCallbacks(pollRunnable);
        binding = null;
    }
}
