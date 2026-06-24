package com.example.viper_wallet;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.example.viper_wallet.databinding.ActivityBalanceDetailsBinding;
import com.example.viper_wallet.databinding.ItemMiningRewardBinding;
import com.example.viper_wallet.network.rpc.BitcoinRpcClient;
import com.example.viper_wallet.network.rpc.BitcoinRpcResponse;
import com.example.viper_wallet.network.rpc.BitcoinScanTxOutSetResult;
import com.example.viper_wallet.walletcore.Constants;
import com.example.viper_wallet.walletcore.WalletManager;

import org.bitcoinj.wallet.Wallet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class BalanceDetailsActivity extends AppCompatActivity {
    private static final String TAG = "BalanceDetailsActivity";
    private static final int COINBASE_MATURITY_CONFIRMATIONS = 100;

    static BitcoinScanTxOutSetResult lastScanResult;
    static List<String> lastAddresses;

    private ActivityBalanceDetailsBinding binding;
    private WalletManager walletManager;
    private MiningRewardAdapter rewardAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityBalanceDetailsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainDetails, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        walletManager = WalletManager.getInstance(this);
        setSupportActionBar(binding.toolbar);
        binding.toolbar.setNavigationOnClickListener(v -> onBackPressed());

        rewardAdapter = new MiningRewardAdapter();
        binding.rvMiningRewards.setAdapter(rewardAdapter);

        if (lastScanResult != null && lastAddresses != null) {
            renderBalance(lastScanResult, lastAddresses);
        } else {
            loadBalanceDetails();
        }
    }

    private void loadBalanceDetails() {
        Wallet wallet = walletManager.getWallet();
        if (wallet == null) {
            try {
                wallet = walletManager.loadWallet();
            } catch (IOException e) {
                Log.w(TAG, "No se pudo cargar wallet", e);
            }
        }

        if (wallet == null) {
            showEmptyState("No hay wallet cargada para consultar el balance.");
            return;
        }

        List<String> addresses = walletManager.getIssuedReceiveAddresses();
        if (addresses.isEmpty()) {
            showEmptyState("La wallet aún no tiene direcciones para consultar.");
            return;
        }

        setLoading(true);
        BitcoinRpcClient.getInstance().scanTxOutSetForAddresses(addresses, new Callback<BitcoinRpcResponse<BitcoinScanTxOutSetResult>>() {
            @Override
            public void onResponse(
                    Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call,
                    Response<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> response
            ) {
                setLoading(false);

                BitcoinRpcResponse<BitcoinScanTxOutSetResult> body = response.body();
                if (!response.isSuccessful() || body == null || body.getError() != null || body.getResult() == null) {
                    String message = body != null && body.getError() != null
                            ? body.getError().getMessage()
                            : "respuesta RPC inválida";
                    Toast.makeText(BalanceDetailsActivity.this, "No se pudo leer balance: " + message, Toast.LENGTH_LONG).show();
                    showEmptyState("No se pudo cargar el detalle de balance.");
                    return;
                }

                BitcoinScanTxOutSetResult result = body.getResult();
                if (!result.isSuccess()) {
                    showEmptyState("El nodo no pudo escanear los UTXOs.");
                    return;
                }

                lastScanResult = result;
                lastAddresses = addresses;
                renderBalance(result, addresses);
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Throwable t) {
                setLoading(false);
                Toast.makeText(BalanceDetailsActivity.this, "Error RPC: " + t.getMessage(), Toast.LENGTH_LONG).show();
                showEmptyState("No se pudo cargar el detalle de balance.");
            }
        });
    }

    private void renderBalance(BitcoinScanTxOutSetResult result, List<String> walletAddresses) {
        int chainHeight = result.getHeight();
        long totalSats = 0L;
        long immatureSats = 0L;
        List<MiningReward> rewards = new ArrayList<>();

        if (chainHeight > 0) {
            walletManager.setRpcUtxos(toSpendableWalletUtxos(result, walletAddresses, chainHeight), chainHeight);
        }

        List<BitcoinScanTxOutSetResult.Unspent> unspents = result.getUnspents();
        if (unspents != null) {
            for (BitcoinScanTxOutSetResult.Unspent unspent : unspents) {
                if (unspent == null) continue;

                long amountSats = unspent.getAmountSats();
                totalSats += amountSats;

                if (!unspent.isCoinbase()) continue;

                int confirmations = confirmationsForUnspent(unspent, chainHeight);
                int blocksRemaining = Math.max(0, COINBASE_MATURITY_CONFIRMATIONS - confirmations);
                boolean immature = blocksRemaining > 0;
                if (immature) {
                    immatureSats += amountSats;
                }

                rewards.add(new MiningReward(
                        unspent.getTxid(),
                        amountSats,
                        unspent.getHeight(),
                        confirmations,
                        blocksRemaining,
                        immature
                ));
            }
        } else {
            totalSats = result.getTotalSats();
        }

        Collections.sort(rewards, (left, right) -> {
            if (left.immature != right.immature) {
                return left.immature ? -1 : 1;
            }
            return Integer.compare(right.height, left.height);
        });

        long spendableSats = Math.max(0L, totalSats - immatureSats);
        long estimatedSpendableSats = walletManager.getEstimatedSpendableBalanceSats();
        long availableSats = estimatedSpendableSats > 0 || spendableSats == 0
                ? estimatedSpendableSats
                : spendableSats;
        long pendingOutgoingSats = walletManager.getPendingOutgoingSats();

        binding.tvAvailableBalance.setText(formatCoinAmount(availableSats));
        binding.tvImmatureBalance.setText("Inmaduro: " + formatCoinAmount(immatureSats));
        binding.tvImmatureBalance.setVisibility(immatureSats > 0 ? View.VISIBLE : View.GONE);
        binding.tvTotalBalance.setText("Total detectado: " + formatCoinAmount(totalSats));

        String info = "Las recompensas de minería necesitan 100 confirmaciones antes de poder gastarse.";
        if (pendingOutgoingSats > 0) {
            info += " En transferencia: " + formatCoinAmount(pendingOutgoingSats) + ".";
        }
        binding.tvBalanceInfo.setText(info);

        rewardAdapter.submit(rewards);
        binding.tvEmptyRewards.setVisibility(rewards.isEmpty() ? View.VISIBLE : View.GONE);
        binding.rvMiningRewards.setVisibility(rewards.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private List<WalletManager.WalletUtxo> toSpendableWalletUtxos(
            BitcoinScanTxOutSetResult result,
            List<String> walletAddresses,
            int chainHeight
    ) {
        List<WalletManager.WalletUtxo> walletUtxos = new ArrayList<>();
        if (result.getUnspents() == null) return walletUtxos;

        for (BitcoinScanTxOutSetResult.Unspent unspent : result.getUnspents()) {
            if (isImmatureCoinbase(unspent, chainHeight)) {
                continue;
            }

            String scriptPubKey = unspent.getScriptPubKey();
            if (scriptPubKey == null || scriptPubKey.isEmpty()) {
                continue;
            }

            String ownerAddress = findOwnerAddress(unspent, walletAddresses);
            if (ownerAddress == null) {
                continue;
            }

            walletUtxos.add(new WalletManager.WalletUtxo(
                    unspent.getTxid(),
                    unspent.getVout(),
                    unspent.getAmountSats(),
                    unspent.getHeight(),
                    unspent.isCoinbase(),
                    scriptPubKey,
                    ownerAddress
            ));
        }
        return walletUtxos;
    }

    private String findOwnerAddress(BitcoinScanTxOutSetResult.Unspent unspent, List<String> walletAddresses) {
        String descriptor = unspent.getDescriptor();
        if (descriptor == null) return null;

        for (String walletAddress : walletAddresses) {
            if (descriptor.contains(walletAddress)) {
                return walletAddress;
            }
        }
        return null;
    }

    private boolean isImmatureCoinbase(BitcoinScanTxOutSetResult.Unspent unspent, int chainHeight) {
        return unspent != null
                && unspent.isCoinbase()
                && chainHeight > 0
                && confirmationsForUnspent(unspent, chainHeight) < COINBASE_MATURITY_CONFIRMATIONS;
    }

    private int confirmationsForUnspent(BitcoinScanTxOutSetResult.Unspent unspent, int chainHeight) {
        if (unspent == null || unspent.getHeight() <= 0 || chainHeight <= 0) return 0;
        return Math.max(0, chainHeight - unspent.getHeight() + 1);
    }

    private void setLoading(boolean loading) {
        binding.progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.rvMiningRewards.setVisibility(loading ? View.GONE : View.VISIBLE);
        binding.tvEmptyRewards.setVisibility(View.GONE);
    }

    private void showEmptyState(String message) {
        binding.tvEmptyRewards.setText(message);
        binding.tvEmptyRewards.setVisibility(View.VISIBLE);
        binding.rvMiningRewards.setVisibility(View.GONE);
        binding.tvAvailableBalance.setText(formatCoinAmount(0L));
        binding.tvImmatureBalance.setVisibility(View.GONE);
        binding.tvTotalBalance.setText("Total detectado: " + formatCoinAmount(0L));
    }

    private String formatCoinAmount(long sats) {
        return String.format(Locale.US, "%.8f %s", sats / 100_000_000.0, Constants.COIN_TICKER);
    }

    private static class MiningReward {
        private final String txId;
        private final long amountSats;
        private final int height;
        private final int confirmations;
        private final int blocksRemaining;
        private final boolean immature;

        private MiningReward(
                String txId,
                long amountSats,
                int height,
                int confirmations,
                int blocksRemaining,
                boolean immature
        ) {
            this.txId = txId;
            this.amountSats = amountSats;
            this.height = height;
            this.confirmations = confirmations;
            this.blocksRemaining = blocksRemaining;
            this.immature = immature;
        }
    }

    private class MiningRewardAdapter extends RecyclerView.Adapter<MiningRewardAdapter.ViewHolder> {
        private final List<MiningReward> rewards = new ArrayList<>();

        private void submit(List<MiningReward> items) {
            rewards.clear();
            rewards.addAll(items);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemMiningRewardBinding itemBinding = ItemMiningRewardBinding.inflate(
                    LayoutInflater.from(parent.getContext()),
                    parent,
                    false
            );
            return new ViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(rewards.get(position));
        }

        @Override
        public int getItemCount() {
            return rewards.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final ItemMiningRewardBinding itemBinding;

            private ViewHolder(ItemMiningRewardBinding itemBinding) {
                super(itemBinding.getRoot());
                this.itemBinding = itemBinding;
            }

            private void bind(MiningReward reward) {
                itemBinding.tvRewardAmount.setText(formatCoinAmount(reward.amountSats));

                int color;
                if (reward.immature) {
                    itemBinding.tvRewardStatus.setText("Inmadura");
                    color = ContextCompat.getColor(BalanceDetailsActivity.this, R.color.immature_balance);
                    itemBinding.tvRewardStatus.setTextColor(color);
                    itemBinding.tvRewardMaturity.setText("Faltan " + reward.blocksRemaining + " bloques para madurar");
                } else {
                    itemBinding.tvRewardStatus.setText("Disponible");
                    color = MaterialColors.getColor(BalanceDetailsActivity.this, androidx.appcompat.R.attr.colorPrimary, 0xFF1B8A5A);
                    itemBinding.tvRewardStatus.setTextColor(color);
                    itemBinding.tvRewardMaturity.setText("Lista para usar");
                }

                itemBinding.ivMiningIcon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
                android.graphics.drawable.Drawable bg = itemBinding.ivMiningIcon.getBackground();
                if (bg != null) {
                    bg.setTint(color);
                    bg.setAlpha(40);
                }

                String shortTx = reward.txId != null && reward.txId.length() > 12
                        ? reward.txId.substring(0, 12)
                        : String.valueOf(reward.txId);
                itemBinding.tvRewardInfo.setText(
                        "Bloque " + reward.height
                                + " · " + reward.confirmations + " confirmaciones"
                                + " · tx " + shortTx
                );
            }
        }
    }
}
