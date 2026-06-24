package com.example.viper_wallet.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.viper_wallet.R;
import com.example.viper_wallet.models.TransactionRecord;
import com.example.viper_wallet.walletcore.Constants;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.ViewHolder> {

    public interface OnTransactionClickListener {
        void onTransactionClick(TransactionRecord transaction);
    }

    private final List<TransactionRecord> transactions;
    private final OnTransactionClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM, yyyy - HH:mm", Locale.getDefault());

    public TransactionAdapter(List<TransactionRecord> transactions) {
        this(transactions, null);
    }

    public TransactionAdapter(List<TransactionRecord> transactions, OnTransactionClickListener listener) {
        this.transactions = transactions;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transaction, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TransactionRecord tx = transactions.get(position);
        
        boolean isSend = "SEND".equals(tx.getType());
        boolean isMining = "MINING".equals(tx.getType());
        holder.tvType.setText(isSend ? "Envío" : (isMining ? "Ganancia por minería" : "Recepción"));
        
        int iconRes = R.drawable.ic_arrow_down_left; // Default Receive
        int color;
        if (isSend) {
            iconRes = R.drawable.ic_arrow_up_right;
            color = holder.itemView.getContext().getColor(android.R.color.holo_red_dark);
        } else if (isMining) {
            iconRes = R.drawable.ic_plus;
            color = holder.itemView.getContext().getColor(R.color.immature_balance);
        } else {
            color = holder.itemView.getContext().getColor(android.R.color.holo_green_dark);
        }
        
        holder.ivIcon.setImageResource(iconRes);
        holder.ivIcon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        
        // Also tint the background slightly with the same color
        android.graphics.drawable.Drawable bg = holder.ivIcon.getBackground();
        if (bg != null) {
            bg.setTint(color);
            bg.setAlpha(40); // 15% opacity
        }
        
        String amountStr = String.format(Locale.US, "%.8f %s", tx.getAmountSats() / 100_000_000.0, Constants.COIN_TICKER);
        holder.tvAmount.setText((isSend ? "-" : "+") + amountStr);
        holder.tvAmount.setTextColor(color);
        
        if (tx.getTimestamp() != 0) {
            holder.tvDate.setText(dateFormat.format(new java.util.Date(tx.getTimestamp())));
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTransactionClick(tx);
            }
        });
        holder.itemView.setClickable(listener != null);
    }

    @Override
    public int getItemCount() {
        return transactions.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvType, tvDate, tvAmount;

        ViewHolder(View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivTxIcon);
            tvType = itemView.findViewById(R.id.tvTxType);
            tvDate = itemView.findViewById(R.id.tvTxDate);
            tvAmount = itemView.findViewById(R.id.tvTxAmount);
        }
    }
}
