package com.pleos.mobile_app_fuelog.ui.trips;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.databinding.ItemDailyTripCardBinding;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DailyTripAdapter extends RecyclerView.Adapter<DailyTripAdapter.TripViewHolder> {

    public interface OnTripClickListener {
        void onTripClick(FuelogBackendApiClient.InboxTripSummaryItem trip);
    }

    private final List<FuelogBackendApiClient.InboxTripSummaryItem> trips = new ArrayList<>();
    private final OnTripClickListener listener;

    public DailyTripAdapter(OnTripClickListener listener) {
        this.listener = listener;
    }

    public void setTrips(@NonNull List<FuelogBackendApiClient.InboxTripSummaryItem> newTrips) {
        this.trips.clear();
        this.trips.addAll(newTrips);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TripViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemDailyTripCardBinding binding = ItemDailyTripCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new TripViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull TripViewHolder holder, int position) {
        holder.bind(trips.get(position));
    }

    @Override
    public int getItemCount() {
        return trips.size();
    }

    class TripViewHolder extends RecyclerView.ViewHolder {
        private final ItemDailyTripCardBinding binding;

        TripViewHolder(@NonNull ItemDailyTripCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(FuelogBackendApiClient.InboxTripSummaryItem item) {
            // 시간 표시
            String timeText;
            if (item.startedAt != null && item.endedAt != null) {
                String s = formatIsoTimeToHhMm(item.startedAt);
                String e = formatIsoTimeToHhMm(item.endedAt);
                timeText = s + " ~ " + e;
            } else if (item.deliveredAt != null) {
                timeText = formatIsoTimeToHhMm(item.deliveredAt) + " 수신";
            } else {
                timeText = "운행 완료";
            }
            binding.textTripTime.setText(timeText);

            // 거리
            double dist = item.distanceKm != null ? item.distanceKm : 0.0;
            binding.textTripDistance.setText(String.format(Locale.KOREA, "%.1f km", dist));

            // 비용
            if (item.estimatedCostWon != null && item.estimatedCostWon > 0L) {
                binding.textTripCost.setText(NumberFormat.getNumberInstance(Locale.KOREA).format(item.estimatedCostWon) + "원");
            } else {
                binding.textTripCost.setText("비용 산정 중");
            }

            // 연비 & 소모량
            double eff = item.fuelEfficiencyKmPerLiter != null ? item.fuelEfficiencyKmPerLiter : 0.0;
            double fuel = item.fuelConsumedLiters != null ? item.fuelConsumedLiters : 0.0;
            if (eff > 0.0 || fuel > 0.0) {
                binding.textTripMetrics.setText(String.format(Locale.KOREA, "연비 %.1f km/L · %.1f L 소모", eff, fuel));
            } else {
                binding.textTripMetrics.setText("기록 품질 양호");
            }

            // 뱃지
            binding.textTripSourceBadge.setText("플레오스 차량 기록");
            binding.textTripQuality.setText(item.priceSource != null ? item.priceSource : "오피넷 단가 적용");

            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTripClick(item);
                }
            });
        }

        private String formatIsoTimeToHhMm(String iso) {
            try {
                if (iso.contains("T") && iso.length() >= 16) {
                    return iso.substring(11, 16);
                }
                if (iso.length() >= 5) {
                    return iso.substring(0, 5);
                }
            } catch (Exception ignored) {}
            return iso;
        }
    }
}
