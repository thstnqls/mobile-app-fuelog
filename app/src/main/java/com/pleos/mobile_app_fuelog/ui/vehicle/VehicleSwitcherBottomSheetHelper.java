package com.pleos.mobile_app_fuelog.ui.vehicle;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.data.home.VehicleProfile;

import java.util.List;

public final class VehicleSwitcherBottomSheetHelper {

    public interface OnVehicleSelectedListener {
        void onVehicleSelected(@NonNull VehicleProfile vehicle);
    }

    public static void show(
            @NonNull Context context,
            @NonNull List<VehicleProfile> vehicles,
            @Nullable String currentVehicleId,
            @NonNull OnVehicleSelectedListener listener
    ) {
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_vehicle_switcher, null);
        dialog.setContentView(view);

        TextView tvCount = view.findViewById(R.id.tv_sheet_vehicle_count);
        tvCount.setText("이용할 차량을 선택해 주세요 (총 " + vehicles.size() + "대 등록됨)");

        view.findViewById(R.id.btn_sheet_close).setOnClickListener(v -> dialog.dismiss());

        view.findViewById(R.id.btn_sheet_add_vehicle).setOnClickListener(v -> {
            dialog.dismiss();
            context.startActivity(new Intent(context, VehicleRegisterActivity.class));
        });

        RecyclerView recyclerView = view.findViewById(R.id.recycler_sheet_vehicles);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(new VehicleSwitcherAdapter(context, vehicles, currentVehicleId, v -> {
            dialog.dismiss();
            listener.onVehicleSelected(v);
        }));

        dialog.show();
    }

    private static class VehicleSwitcherAdapter extends RecyclerView.Adapter<VehicleSwitcherAdapter.ViewHolder> {
        private final Context context;
        private final List<VehicleProfile> vehicles;
        private final String currentVehicleId;
        private final OnVehicleSelectedListener listener;

        VehicleSwitcherAdapter(
                Context context,
                List<VehicleProfile> vehicles,
                String currentVehicleId,
                OnVehicleSelectedListener listener
        ) {
            this.context = context;
            this.vehicles = vehicles;
            this.currentVehicleId = currentVehicleId;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vehicle_switcher, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VehicleProfile item = vehicles.get(position);
            boolean isSelected = item.getVehicleId().equals(currentVehicleId);

            holder.tvName.setText(item.getDisplayName());

            // Role badge
            String role = item.getRole() != null ? item.getRole() : "OWNER";
            if ("OWNER".equalsIgnoreCase(role)) {
                holder.tvRoleBadge.setText("소유");
                holder.tvRoleBadge.setTextColor(ContextCompat.getColor(context, R.color.fuelog_primary));
            } else if ("MANAGER".equalsIgnoreCase(role)) {
                holder.tvRoleBadge.setText("관리자");
                holder.tvRoleBadge.setTextColor(Color.parseColor("#E65100"));
            } else {
                holder.tvRoleBadge.setText("멤버");
                holder.tvRoleBadge.setTextColor(Color.parseColor("#2E7D32"));
            }

            // Paired status
            if (item.isPaired()) {
                holder.tvPairedStatus.setText("• 단말기 연결됨");
                holder.tvPairedStatus.setTextColor(Color.parseColor("#2E7D32"));
            } else {
                holder.tvPairedStatus.setText("• 단말 미연동");
                holder.tvPairedStatus.setTextColor(ContextCompat.getColor(context, R.color.fuelog_text_secondary));
            }

            // Selected styling
            if (isSelected) {
                holder.card.setStrokeColor(ContextCompat.getColor(context, R.color.fuelog_primary));
                holder.card.setStrokeWidth((int) (1.8f * context.getResources().getDisplayMetrics().density));
                holder.card.setCardBackgroundColor(Color.parseColor("#F4FBF7"));
                holder.ivCheck.setVisibility(View.VISIBLE);
                holder.cardIconContainer.setCardBackgroundColor(Color.parseColor("#E8F5E9"));
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.fuelog_primary));
            } else {
                holder.card.setStrokeColor(ContextCompat.getColor(context, R.color.fuelog_border));
                holder.card.setStrokeWidth((int) (1.0f * context.getResources().getDisplayMetrics().density));
                holder.card.setCardBackgroundColor(Color.WHITE);
                holder.ivCheck.setVisibility(View.GONE);
                holder.cardIconContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.fuelog_surface));
                holder.ivIcon.setColorFilter(ContextCompat.getColor(context, R.color.fuelog_text_primary));
            }

            View.OnClickListener clickListener = v -> listener.onVehicleSelected(item);
            holder.itemView.setOnClickListener(clickListener);
            holder.card.setOnClickListener(clickListener);
        }

        @Override
        public int getItemCount() {
            return vehicles.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final MaterialCardView cardIconContainer;
            final ImageView ivIcon;
            final TextView tvName;
            final TextView tvRoleBadge;
            final TextView tvPairedStatus;
            final ImageView ivCheck;

            ViewHolder(@NonNull View v) {
                super(v);
                card = v.findViewById(R.id.card_vehicle_item);
                cardIconContainer = v.findViewById(R.id.card_vehicle_icon_container);
                ivIcon = v.findViewById(R.id.iv_vehicle_icon);
                tvName = v.findViewById(R.id.tv_vehicle_name);
                tvRoleBadge = v.findViewById(R.id.tv_vehicle_role_badge);
                tvPairedStatus = v.findViewById(R.id.tv_vehicle_paired_status);
                ivCheck = v.findViewById(R.id.iv_vehicle_check);
            }
        }
    }
}
