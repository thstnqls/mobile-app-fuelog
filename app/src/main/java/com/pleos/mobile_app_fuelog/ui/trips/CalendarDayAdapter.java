package com.pleos.mobile_app_fuelog.ui.trips;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.pleos.mobile_app_fuelog.R;
import com.pleos.mobile_app_fuelog.databinding.ItemCalendarDayBinding;
import com.pleos.mobile_app_fuelog.ui.trips.model.CalendarDayItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CalendarDayAdapter extends RecyclerView.Adapter<CalendarDayAdapter.DayViewHolder> {

    public interface OnDayClickListener {
        void onDayClick(CalendarDayItem dayItem);
    }

    private final List<CalendarDayItem> days = new ArrayList<>();
    private final OnDayClickListener listener;
    private int selectedPosition = -1;

    public CalendarDayAdapter(@NonNull OnDayClickListener listener) {
        this.listener = listener;
    }

    public void setDays(@NonNull List<CalendarDayItem> newDays) {
        this.days.clear();
        this.days.addAll(newDays);
        this.selectedPosition = -1;
        for (int i = 0; i < days.size(); i++) {
            if (days.get(i).isSelected()) {
                selectedPosition = i;
                break;
            }
        }
        notifyDataSetChanged();
    }

    public void selectPosition(int position) {
        if (position < 0 || position >= days.size()) return;
        if (selectedPosition == position) return;

        int oldPos = selectedPosition;
        selectedPosition = position;

        if (oldPos >= 0 && oldPos < days.size()) {
            days.get(oldPos).setSelected(false);
            notifyItemChanged(oldPos);
        }

        days.get(selectedPosition).setSelected(true);
        notifyItemChanged(selectedPosition);
    }

    @NonNull
    @Override
    public DayViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCalendarDayBinding binding = ItemCalendarDayBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new DayViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull DayViewHolder holder, int position) {
        holder.bind(days.get(position));
    }

    @Override
    public int getItemCount() {
        return days.size();
    }

    class DayViewHolder extends RecyclerView.ViewHolder {
        private final ItemCalendarDayBinding binding;

        DayViewHolder(@NonNull ItemCalendarDayBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(CalendarDayItem item) {
            binding.textDayNumber.setText(String.valueOf(item.getDayOfMonth()));

            int dayOfWeek = getBindingAdapterPosition() % 7;

            if (!item.isCurrentMonth()) {
                binding.textDayNumber.setTextColor(Color.parseColor("#FFCBD5E1"));
                binding.textDayNumber.setTypeface(null, Typeface.NORMAL);
                binding.textDayNumber.setBackground(null);
            } else if (item.isSelected()) {
                binding.textDayNumber.setTextColor(Color.WHITE);
                binding.textDayNumber.setTypeface(null, Typeface.BOLD);
                binding.textDayNumber.setBackgroundResource(R.drawable.bg_calendar_day_selected);
            } else if (item.isToday()) {
                binding.textDayNumber.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.fuelog_primary));
                binding.textDayNumber.setTypeface(null, Typeface.BOLD);
                binding.textDayNumber.setBackgroundResource(R.drawable.bg_calendar_day_today);
            } else {
                binding.textDayNumber.setBackground(null);
                binding.textDayNumber.setTypeface(null, Typeface.NORMAL);
                if (dayOfWeek == 0) {
                    binding.textDayNumber.setTextColor(Color.parseColor("#FFEF4444")); // 일요일
                } else if (dayOfWeek == 6) {
                    binding.textDayNumber.setTextColor(Color.parseColor("#FF3B82F6")); // 토요일
                } else {
                    binding.textDayNumber.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.fuelog_text_primary));
                }
            }

            // 주행거리 표시
            if (item.getTotalDistanceKm() > 0.05) {
                binding.textDayDistance.setVisibility(View.VISIBLE);
                if (item.getTotalDistanceKm() >= 10.0) {
                    binding.textDayDistance.setText(String.format(Locale.KOREA, "%.0fkm", item.getTotalDistanceKm()));
                } else {
                    binding.textDayDistance.setText(String.format(Locale.KOREA, "%.1fkm", item.getTotalDistanceKm()));
                }
            } else {
                binding.textDayDistance.setVisibility(View.GONE);
            }

            binding.layoutCalendarDayRoot.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    selectPosition(pos);
                    listener.onDayClick(item);
                }
            });
        }
    }
}
