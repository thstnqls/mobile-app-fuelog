package com.pleos.mobile_app_fuelog.ui.ledger;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.pleos.mobile_app_fuelog.data.home.ExpenseRecord;
import com.pleos.mobile_app_fuelog.databinding.ItemExpenseRecordBinding;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ExpenseListAdapter extends RecyclerView.Adapter<ExpenseListAdapter.ExpenseViewHolder> {

    public interface OnExpenseClickListener {
        void onExpenseClick(ExpenseRecord item);
    }

    private final List<ExpenseRecord> items = new ArrayList<>();
    private final OnExpenseClickListener listener;

    public ExpenseListAdapter(OnExpenseClickListener listener) {
        this.listener = listener;
    }

    public void setItems(@NonNull List<ExpenseRecord> newItems) {
        this.items.clear();
        this.items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ExpenseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemExpenseRecordBinding binding = ItemExpenseRecordBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ExpenseViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ExpenseViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ExpenseViewHolder extends RecyclerView.ViewHolder {
        private final ItemExpenseRecordBinding binding;

        ExpenseViewHolder(@NonNull ItemExpenseRecordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(ExpenseRecord record) {
            binding.textExpenseTitle.setText(record.getTitle());
            binding.textExpenseAmount.setText(
                    NumberFormat.getNumberInstance(Locale.KOREA).format(record.getAmountWon()) + "원"
            );

            if (record.getCategory() == ExpenseRecord.Category.FUEL) {
                binding.textExpenseIcon.setText("⛽");
                binding.textExpenseIcon.setBackgroundColor(Color.parseColor("#155CE1A6"));
                binding.textExpenseCategoryBadge.setText("주유");
            } else if (record.getCategory() == ExpenseRecord.Category.MAINTENANCE) {
                binding.textExpenseIcon.setText("🔧");
                binding.textExpenseIcon.setBackgroundColor(Color.parseColor("#153B82F6"));
                binding.textExpenseCategoryBadge.setText("정비");
            } else {
                binding.textExpenseIcon.setText("🚗");
                binding.textExpenseIcon.setBackgroundColor(Color.parseColor("#15FFA500"));
                binding.textExpenseCategoryBadge.setText("운행 추정");
            }

            binding.textExpenseDateAndDetails.setText(record.getDateLabel());

            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) {
                    listener.onExpenseClick(record);
                }
            });
        }
    }
}
