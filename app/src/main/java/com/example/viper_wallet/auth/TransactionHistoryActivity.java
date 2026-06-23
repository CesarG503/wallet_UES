package com.example.viper_wallet.auth;

import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.example.viper_wallet.R;
import com.example.viper_wallet.TransactionDetailsDialog;
import com.example.viper_wallet.adapters.TransactionAdapter;
import com.example.viper_wallet.models.TransactionRecord;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.List;

public class TransactionHistoryActivity extends AppCompatActivity {

    private RecyclerView rvTransactions;
    private ProgressBar progressBar;
    private TextView tvEmptyState;
    private AuthManager authManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_history);

        authManager = AuthManager.getInstance();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());

        rvTransactions = findViewById(R.id.rvTransactions);
        progressBar = findViewById(R.id.progressBar);
        tvEmptyState = findViewById(R.id.tvEmptyState);

        loadTransactions();
    }

    private void loadTransactions() {
        setLoading(true);
        authManager.getTransactions(new AuthManager.TransactionsCallback() {
            @Override
            public void onTransactionsLoaded(List<TransactionRecord> transactions) {
                setLoading(false);
                if (transactions.isEmpty()) {
                    tvEmptyState.setVisibility(View.VISIBLE);
                    rvTransactions.setVisibility(View.GONE);
                } else {
                    tvEmptyState.setVisibility(View.GONE);
                    rvTransactions.setVisibility(View.VISIBLE);
                    TransactionAdapter adapter = new TransactionAdapter(
                            transactions,
                            transaction -> TransactionDetailsDialog.show(TransactionHistoryActivity.this, transaction)
                    );
                    rvTransactions.setAdapter(adapter);
                }
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                Toast.makeText(TransactionHistoryActivity.this, "Error: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setLoading(boolean isLoading) {
        progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
    }
}
