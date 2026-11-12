package com.pleos.mobile_app_fuelog.ui.maintenance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.data.network.FuelogBackendApiClient;
import com.pleos.mobile_app_fuelog.databinding.ItemMaintenanceScheduleCardBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MaintenanceScheduleAdapter extends RecyclerView.Adapter<MaintenanceScheduleAdapter.ViewHolder> {

    public interface OnPlanActionListener {
        void onEditCycle(@NonNull FuelogBackendApiClient.MaintenancePlanItem item);
        void onCompleteMaintenance(@NonNull FuelogBackendApiClient.MaintenancePlanItem item);
        void onDeletePlan(@NonNull FuelogBackendApiClient.MaintenancePlanItem item);
    }

    private final List<FuelogBackendApiClient.MaintenancePlanItem> items = new ArrayList<>();
    private final OnPlanActionListener listener;
    @Nullable
    private Double currentOdometerKm;

    public MaintenanceScheduleAdapter(
            @Nullable Double currentOdometerKm,
            @NonNull OnPlanActionListener listener
    ) {
        this.currentOdometerKm = currentOdometerKm;
        this.listener = listener;
    }

    public void updateData(
            @NonNull List<FuelogBackendApiClient.MaintenancePlanItem> newItems,
            @Nullable Double currentOdometerKm
    ) {
        this.items.clear();
        this.items.addAll(newItems);
        this.currentOdometerKm = currentOdometerKm;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemMaintenanceScheduleCardBinding binding = ItemMaintenanceScheduleCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemMaintenanceScheduleCardBinding binding;

        ViewHolder(@NonNull ItemMaintenanceScheduleCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(FuelogBackendApiClient.MaintenancePlanItem item) {
            Context context = itemView.getContext();

            // 소모품명
            binding.tvPlanName.setText(item.name);

            // 위험도 뱃지 및 프로그레스 바 스타일
            String riskLevel = item.riskLevel != null ? item.riskLevel : "UNKNOWN";
            int badgeBgColor;
            int badgeTextColor;
            int progressColor;
            String badgeText;

            if ("DANGER".equalsIgnoreCase(riskLevel)) {
                badgeText = "교체 시급";
                badgeBgColor = ContextCompat.getColor(context, R.color.fuelog_server_error_bg);
                badgeTextColor = ContextCompat.getColor(context, R.color.fuelog_server_error_text);
                progressColor = ContextCompat.getColor(context, R.color.fuelog_server_error_border);
            } else if ("OVERDUE".equalsIgnoreCase(riskLevel)) {
                badgeText = "교체 권장";
                badgeBgColor = ContextCompat.getColor(context, R.color.fuelog_input_error_bg);
                badgeTextColor = ContextCompat.getColor(context, R.color.fuelog_input_error_text);
                progressColor = ContextCompat.getColor(context, R.color.fuelog_input_error_border);
            } else if ("NORMAL".equalsIgnoreCase(riskLevel)) {
                badgeText = "정상";
                badgeBgColor = ContextCompat.getColor(context, R.color.fuelog_success_bg);
                badgeTextColor = ContextCompat.getColor(context, R.color.fuelog_success_text);
                progressColor = ContextCompat.getColor(context, R.color.fuelog_primary);
            } else {
                badgeText = "점검 필요";
                badgeBgColor = ContextCompat.getColor(context, R.color.fuelog_info_bg);
                badgeTextColor = ContextCompat.getColor(context, R.color.fuelog_info_text);
                progressColor = ContextCompat.getColor(context, R.color.fuelog_primary);
            }

            binding.badgePlanRisk.setText(badgeText);
            binding.badgePlanRisk.setBackgroundTintList(ColorStateList.valueOf(badgeBgColor));
            binding.badgePlanRisk.setTextColor(badgeTextColor);

            // D-day & 남은 거리
            binding.tvPlanDday.setText(item.getDDayText());
            binding.tvPlanRemainingKm.setText(item.getRemainingDistanceText(currentOdometerKm));

            // 진행 바
            int progress = item.getProgressPercent(currentOdometerKm);
            binding.progressPlanUsage.setProgress(progress);
            binding.progressPlanUsage.setProgressTintList(ColorStateList.valueOf(progressColor));

            // 세부 주기
            binding.tvPlanInterval.setText(item.getIntervalText());

            // 최근 정비
            if (item.lastServiceDate != null) {
                String lastStr = item.lastServiceDate;
                if (item.lastServiceKm != null && item.lastServiceKm > 0) {
                    lastStr += String.format(Locale.KOREA, " (%,d km)", Math.round(item.lastServiceKm));
                }
                binding.tvPlanLastService.setText(lastStr);
            } else {
                binding.tvPlanLastService.setText("기록 없음");
            }

            // 다음 예정
            if (item.nextDueDate != null) {
                String nextStr = item.nextDueDate;
                if (item.nextDueKm != null && item.nextDueKm > 0) {
                    nextStr += String.format(Locale.KOREA, " (%,d km)", Math.round(item.nextDueKm));
                }
                binding.tvPlanNextDue.setText(nextStr);
            } else if (item.nextDueKm != null && item.nextDueKm > 0) {
                binding.tvPlanNextDue.setText(String.format(Locale.KOREA, "%,d km 도래 시", Math.round(item.nextDueKm)));
            } else {
                binding.tvPlanNextDue.setText("예정 없음");
            }

            // 버튼 액션
            binding.btnEditCycle.setOnClickListener(v -> listener.onEditCycle(item));
            binding.btnCompleteMaintenance.setOnClickListener(v -> listener.onCompleteMaintenance(item));

            // 더보기 팝업 (비활성화/삭제)
            binding.btnPlanMore.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(context, v);
                popup.getMenu().add(0, 1, 0, "소모품 주기 삭제 (비활성화)");
                popup.setOnMenuItemClickListener(menuItem -> {
                    if (menuItem.getItemId() == 1) {
                        listener.onDeletePlan(item);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }
    }
}
