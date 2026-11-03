package com.pleos.mobile_app_fuelog.data.cache;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pleos.mobile_app_fuelog.core.security.SafeLogger;

import java.util.HashMap;
import java.util.Map;

/**
 * 예외/완료 요구사항:
 * "로그인 만료 후 다른 계정으로 진입하면 이전 계정 캐시 숨김."
 *
 * - 세션 만료 시 즉시 캐시 노출을 차단(cacheVisible = false)한다.
 * - 재로그인 시 이전 계정의 userId와 새로운 계정의 userId를 비교하여,
 *   다를 경우 이전 계정의 로컬 캐시(차량 요약, 최근 주유 기록 캐시 등)를 완전히 숨기고 초기화한다.
 */
public final class UserCacheManager {

    private static final String PREF_NAME = "fuelog_user_cache_prefs";
    private static final String KEY_LAST_USER_ID = "last_user_id";
    private static final String KEY_LAST_USER_EMAIL = "last_user_email";
    private static final String KEY_CACHE_VISIBLE = "cache_visible";
    private static final String KEY_CACHED_VEHICLE_NAME = "cached_vehicle_name";
    private static final String KEY_CACHED_FUEL_SUMMARY = "cached_fuel_summary";
    private static final String KEY_PREV_CACHE_HIDDEN_NOTICE = "prev_cache_hidden_notice";

    private static final String VEHICLE_REGISTRY_PREFIX = "account_vehicle_";

    private static volatile UserCacheManager instance;

    private final SharedPreferences prefs;
    private final Map<String, String> memoryStore = new HashMap<>();

    public UserCacheManager(@Nullable Context context) {
        if (context != null) {
            this.prefs = context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } else {
            this.prefs = null;
        }
        seedDefaultDemoDataIfNeeded();
    }

    public static UserCacheManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (UserCacheManager.class) {
                if (instance == null) {
                    instance = new UserCacheManager(context);
                }
            }
        }
        return instance;
    }

    private void seedDefaultDemoDataIfNeeded() {
        // 데모/검증용: car@fuelog.com 계정은 기본 등록 차량 보유(M03 이동),
        // new@fuelog.com 또는 신규 가입 계정은 차량 없음(M02 이동)
        if (getString(VEHICLE_REGISTRY_PREFIX + "user_with_car", null) == null) {
            putString(VEHICLE_REGISTRY_PREFIX + "user_with_car", "아이오닉 6 롱레인지 (12가 3456)");
        }
    }

    /**
     * 세션 만료 또는 로그아웃 시 호출.
     * 즉시 화면 캐시 노출을 차단(숨김)하되, 다음 로그인 계정과 비교하기 위해 last_user_id는 유지한다.
     */
    public synchronized void onSessionExpiredOrLoggedOut() {
        putBoolean(KEY_CACHE_VISIBLE, false);
        SafeLogger.i("세션 만료/로그아웃 감지: 현재 계정 캐시를 화면에서 즉시 숨김 처리했습니다.");
    }

    /**
     * 로그인 성공 시 호출.
     * 이전 로그인 계정과 다른 계정으로 진입한 경우 이전 계정의 로컬 캐시를 완전히 숨기고 초기화한다.
     *
     * @return 이전 계정과 다른 계정이어서 이전 캐시가 숨김/초기화되었으면 true
     */
    public synchronized boolean onUserAuthenticated(@NonNull String newUserId, @NonNull String newEmail) {
        String previousUserId = getString(KEY_LAST_USER_ID, null);
        boolean switchedAccount = previousUserId != null && !previousUserId.equals(newUserId);

        if (switchedAccount) {
            // 이전 계정 캐시 완전 숨김 및 삭제
            removeKey(KEY_CACHED_VEHICLE_NAME);
            removeKey(KEY_CACHED_FUEL_SUMMARY);
            putBoolean(KEY_PREV_CACHE_HIDDEN_NOTICE, true);
            SafeLogger.i("다른 계정 진입 감지(prev=" + previousUserId + " -> new=" + newUserId
                    + "): 이전 계정 캐시를 숨김 및 초기화했습니다.");
        } else {
            putBoolean(KEY_PREV_CACHE_HIDDEN_NOTICE, false);
        }

        putString(KEY_LAST_USER_ID, newUserId);
        putString(KEY_LAST_USER_EMAIL, newEmail);
        putBoolean(KEY_CACHE_VISIBLE, true);

        // 현재 로그인한 계정의 차량 캐시 로드
        String accountVehicle = getString(VEHICLE_REGISTRY_PREFIX + newUserId, null);
        if (accountVehicle != null && !accountVehicle.isEmpty()) {
            putString(KEY_CACHED_VEHICLE_NAME, accountVehicle);
            putString(KEY_CACHED_FUEL_SUMMARY, "최근 평균 연비 14.8 km/L · 이번 달 주유 2회");
        } else {
            removeKey(KEY_CACHED_VEHICLE_NAME);
            removeKey(KEY_CACHED_FUEL_SUMMARY);
        }

        return switchedAccount;
    }

    public synchronized boolean isCacheVisible() {
        return getBoolean(KEY_CACHE_VISIBLE, false);
    }

    public synchronized boolean wasPreviousAccountCacheHidden() {
        return getBoolean(KEY_PREV_CACHE_HIDDEN_NOTICE, false);
    }

    @Nullable
    public synchronized String getVisibleCachedVehicleName() {
        if (!isCacheVisible()) {
            return null;
        }
        return getString(KEY_CACHED_VEHICLE_NAME, null);
    }

    @Nullable
    public synchronized String getVisibleCachedFuelSummary() {
        if (!isCacheVisible()) {
            return null;
        }
        return getString(KEY_CACHED_FUEL_SUMMARY, null);
    }

    public synchronized boolean hasRegisteredVehicle(@NonNull String userId) {
        String vehicle = getString(VEHICLE_REGISTRY_PREFIX + userId, null);
        return vehicle != null && !vehicle.trim().isEmpty();
    }

    public synchronized void setRegisteredVehicleForUser(@NonNull String userId, @Nullable String vehicleName) {
        if (vehicleName == null || vehicleName.trim().isEmpty()) {
            removeKey(VEHICLE_REGISTRY_PREFIX + userId);
            if (userId.equals(getString(KEY_LAST_USER_ID, null))) {
                removeKey(KEY_CACHED_VEHICLE_NAME);
                removeKey(KEY_CACHED_FUEL_SUMMARY);
            }
        } else {
            putString(VEHICLE_REGISTRY_PREFIX + userId, vehicleName.trim());
            if (userId.equals(getString(KEY_LAST_USER_ID, null)) && isCacheVisible()) {
                putString(KEY_CACHED_VEHICLE_NAME, vehicleName.trim());
                putString(KEY_CACHED_FUEL_SUMMARY, "최근 평균 연비 14.8 km/L · 첫 차량 등록 완료");
            }
        }
    }

    @Nullable
    public synchronized String getLastAuthenticatedUserId() {
        return getString(KEY_LAST_USER_ID, null);
    }

    private void putString(String key, String value) {
        if (prefs != null) {
            prefs.edit().putString(key, value).apply();
        } else {
            memoryStore.put(key, value);
        }
    }

    @Nullable
    private String getString(String key, @Nullable String defaultValue) {
        if (prefs != null) {
            return prefs.getString(key, defaultValue);
        }
        return memoryStore.containsKey(key) ? memoryStore.get(key) : defaultValue;
    }

    private void putBoolean(String key, boolean value) {
        if (prefs != null) {
            prefs.edit().putBoolean(key, value).apply();
        } else {
            memoryStore.put(key, Boolean.toString(value));
        }
    }

    private boolean getBoolean(String key, boolean defaultValue) {
        if (prefs != null) {
            return prefs.getBoolean(key, defaultValue);
        }
        String val = memoryStore.get(key);
        return val != null ? Boolean.parseBoolean(val) : defaultValue;
    }

    private void removeKey(String key) {
        if (prefs != null) {
            prefs.edit().remove(key).apply();
        } else {
            memoryStore.remove(key);
        }
    }
}
