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

    private final List<TransactionRecord> transactions;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM, yyyy - HH:mm", Locale.getDefault());

    public TransactionAdapter(List<TransactionRecord> transactions) {
        this.transactions = transactions;
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
        holder.tvType.setText(isSend ? "Envío" : "Recepción");
        holder.ivIcon.setImageResource(isSend ? android.R.drawable.stat_sys_upload : android.R.drawable.stat_sys_download);
        
        String amountStr = String.format(Locale.US, "%.8f %s", tx.getAmountSats() / 100_000_000.0, Constants.COIN_TICKER);
        holder.tvAmount.setText((isSend ? "-" : "+") + amountStr);
        holder.tvAmount.setTextColor(holder.itemView.getContext().getColor(isSend ? android.R.color.holo_red_dark : android.R.color.holo_green_dark));
        
        if (tx.getTimestamp() != 0) {
            holder.tvDate.setText(dateFormat.format(new java.util.Date(tx.getTimestamp())));
        }
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
