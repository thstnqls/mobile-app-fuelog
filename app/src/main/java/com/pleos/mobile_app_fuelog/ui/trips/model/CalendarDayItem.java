package com.pleos.mobile_app_fuelog.ui.trips.model;

import androidx.annotation.NonNull;

import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 캘린더 날짜 셀 모델.
 * - 특정 연/월/일 및 해당 날짜의 플레오스 운행 기록 묶음.
 */
public final class CalendarDayItem {

    private final int year;
    private final int month; // 1 ~ 12
    private final int dayOfMonth; // 1 ~ 31
    private final boolean isCurrentMonth;
    private boolean isToday;
    private boolean isSelected;
    private double totalDistanceKm;
    private long totalCostWon;
    private final List<FuelogBackendApiClient.InboxTripSummaryItem> trips = new ArrayList<>();

    public CalendarDayItem(int year, int month, int dayOfMonth, boolean isCurrentMonth) {
        this.year = year;
        this.month = month;
        this.dayOfMonth = dayOfMonth;
        this.isCurrentMonth = isCurrentMonth;
    }

    public int getYear() { return year; }
    public int getMonth() { return month; }
    public int getDayOfMonth() { return dayOfMonth; }
    public boolean isCurrentMonth() { return isCurrentMonth; }
    public boolean isToday() { return isToday; }
    public void setToday(boolean today) { isToday = today; }
    public boolean isSelected() { return isSelected; }
    public void setSelected(boolean selected) { isSelected = selected; }

    public double getTotalDistanceKm() { return totalDistanceKm; }
    public long getTotalCostWon() { return totalCostWon; }

    @NonNull
    public List<FuelogBackendApiClient.InboxTripSummaryItem> getTrips() {
        return trips;
    }

    public void addTrip(@NonNull FuelogBackendApiClient.InboxTripSummaryItem trip) {
        trips.add(trip);
        if (trip.distanceKm != null) {
            totalDistanceKm += trip.distanceKm;
        }
        if (trip.estimatedCostWon != null) {
            totalCostWon += trip.estimatedCostWon;
        }
    }

    public void clearTrips() {
        trips.clear();
        totalDistanceKm = 0.0;
        totalCostWon = 0L;
    }

    public String getDateKey() {
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month, dayOfMonth);
    }
}
