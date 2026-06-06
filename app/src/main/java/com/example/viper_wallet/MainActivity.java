package com.example.viper_wallet;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.viper_wallet.databinding.ActivityMainBinding;
import com.example.viper_wallet.network.rpc.BitcoinRpcClient;
import com.example.viper_wallet.network.rpc.BitcoinRpcResponse;
import com.example.viper_wallet.network.rpc.BitcoinScanTxOutSetResult;
import com.example.viper_wallet.walletcore.Constants;
import com.example.viper_wallet.walletcore.WalletManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final long BALANCE_REFRESH_INTERVAL_MS = 20_000L;

    private ActivityMainBinding binding;
    private WalletManager walletManager;
    private final Handler balanceHandler = new Handler(Looper.getMainLooper());
    private boolean isBalancePolling;
    private boolean isBalanceRequestInFlight;

    private final Runnable balanceRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshBalanceFromRpc();
            if (isBalancePolling) {
                balanceHandler.postDelayed(this, BALANCE_REFRESH_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        walletManager = WalletManager.getInstance(this);

        setupWindowInsets();
        checkWalletState();
        setupListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null && binding.layoutDashboard.getVisibility() == View.VISIBLE) {
            startBalancePolling();
        }
    }

    @Override
    protected void onPause() {
        stopBalancePolling();
        super.onPause();
    }

    private void setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void checkWalletState() {
        if (walletManager.hasWallet()) {
            showDashboard();
        } else {
            showSetup();
        }
    }

    private void showSetup() {
        stopBalancePolling();
        binding.layoutSetup.setVisibility(View.VISIBLE);
        binding.layoutDashboard.setVisibility(View.GONE);
    }

    private void showDashboard() {
        binding.layoutSetup.setVisibility(View.GONE);
        binding.layoutDashboard.setVisibility(View.VISIBLE);
        updateUI();
        createServerWalletForDemo();
        startBalancePolling();
    }

    private void setupListeners() {
        binding.btnGenerateWallet.setOnClickListener(v -> generateNewWallet());
        
        binding.btnRestoreWallet.setOnClickListener(v -> {
            Toast.makeText(this, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show();
        });

        binding.cardBackup.setOnClickListener(v -> showSeedPhrase());

        binding.btnReceive.setOnClickListener(v -> showReceiveAddress());

        binding.btnSend.setOnClickListener(v -> showSendDialog());
    }

    private void generateNewWallet() {
        try {
            walletManager.createWallet();
            showSeedPhrase();
            showDashboard();
            Toast.makeText(this, R.string.wallet_created_success, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, R.string.error_creating_wallet, Toast.LENGTH_LONG).show();
        }
    }

    private void createServerWalletForDemo() {
        String walletName = walletManager.getServerWalletName();

        BitcoinRpcClient.getInstance().createServerWallet(walletName, new Callback<BitcoinRpcResponse<Object>>() {
            @Override
            public void onResponse(Call<BitcoinRpcResponse<Object>> call, Response<BitcoinRpcResponse<Object>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().getError() == null) {
                    Toast.makeText(MainActivity.this, "Wallet RPC creada: " + walletName, Toast.LENGTH_SHORT).show();
                } else {
                    BitcoinRpcResponse.BitcoinRpcError error = response.body() != null
                            ? response.body().getError()
                            : null;
                    String message = error != null ? error.getMessage() : "respuesta RPC inválida";
                    Log.w(TAG, "createwallet RPC: " + message);
                    loadServerWalletAndSync(walletName);
                    return;
                }
                syncIssuedAddressesWithServer();
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<Object>> call, Throwable t) {
                Toast.makeText(MainActivity.this, "Error RPC createwallet: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void loadServerWalletAndSync(String walletName) {
        BitcoinRpcClient.getInstance().loadServerWallet(walletName, new Callback<BitcoinRpcResponse<Object>>() {
            @Override
            public void onResponse(Call<BitcoinRpcResponse<Object>> call, Response<BitcoinRpcResponse<Object>> response) {
                syncIssuedAddressesWithServer();
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<Object>> call, Throwable t) {
                Log.w(TAG, "No se pudo cargar wallet RPC " + walletName, t);
            }
        });
    }

    private void showSeedPhrase() {
        String mnemonic = walletManager.getMnemonic();
        if (mnemonic == null) return;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.seed_phrase_title)
                .setMessage(mnemonic + "\n\n" + getString(R.string.seed_phrase_warning))
                .setPositiveButton(R.string.btn_done, (dialogInterface, which) -> dialogInterface.dismiss())
                .create();

        dialog.setOnDismissListener(dialogInterface ->
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        dialog.show();
    }

    private void showReceiveAddress() {
        String address = walletManager.getCurrentReceiveAddress();

        if (address == null) {
            Toast.makeText(this, R.string.empty_receive_address, Toast.LENGTH_SHORT).show();
            return;
        }

        binding.tvReceiveAddress.setText(address);
        registerAddressWithServer(address, false);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.receive_address_title)
                .setMessage(getString(R.string.receive_address_message) + "\n\n" + address)
                .setNeutralButton("Mine (RegTest)", (dialog, which) -> mineToAddress(address))
                .setNegativeButton("Nueva dirección", (dialog, which) -> createFreshReceiveAddress())
                .setPositiveButton(R.string.btn_done, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void createFreshReceiveAddress() {
        try {
            String address = walletManager.getFreshReceiveAddress();
            if (address == null) {
                Toast.makeText(this, R.string.empty_receive_address, Toast.LENGTH_SHORT).show();
                return;
            }
            binding.tvReceiveAddress.setText(address);
            registerAddressWithServer(address, true);
            showReceiveAddress();
        } catch (IOException e) {
            Toast.makeText(this, R.string.empty_receive_address, Toast.LENGTH_SHORT).show();
        }
    }

    private void syncIssuedAddressesWithServer() {
        if (walletManager.getWallet() == null) return;
        syncIssuedAddressesWithServer(walletManager.getIssuedReceiveAddresses(), 0);
    }

    private void syncIssuedAddressesWithServer(List<String> addresses, int index) {
        if (index >= addresses.size()) return;
        registerAddressWithServer(
                addresses.get(index),
                false,
                () -> syncIssuedAddressesWithServer(addresses, index + 1)
        );
    }

    private void registerAddressWithServer(String address, boolean showResult) {
        registerAddressWithServer(address, showResult, null);
    }

    private void registerAddressWithServer(String address, boolean showResult, Runnable onComplete) {
        BitcoinRpcClient.getInstance().registerWatchOnlyAddress(
                walletManager.getServerWalletName(),
                address,
                new Callback<BitcoinRpcResponse<Object>>() {
                    @Override
                    public void onResponse(
                            Call<BitcoinRpcResponse<Object>> call,
                            Response<BitcoinRpcResponse<Object>> response
                    ) {
                        BitcoinRpcResponse<Object> body = response.body();
                        if (!response.isSuccessful() || body == null || body.getError() != null) {
                            String message = body != null && body.getError() != null
                                    ? body.getError().getMessage()
                                    : "respuesta RPC inválida";
                            Log.w(TAG, "No se pudo registrar dirección " + address + ": " + message);
                            if (onComplete != null) onComplete.run();
                            return;
                        }
                        Log.i(TAG, "Dirección registrada en wallet RPC: " + address);
                        if (showResult) {
                            Toast.makeText(
                                    MainActivity.this,
                                    "Dirección sincronizada con el panel",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                        if (onComplete != null) onComplete.run();
                    }

                    @Override
                    public void onFailure(Call<BitcoinRpcResponse<Object>> call, Throwable t) {
                        Log.w(TAG, "Error registrando dirección " + address, t);
                        if (onComplete != null) onComplete.run();
                    }
                }
        );
    }

    private void mineToAddress(String address) {
        Toast.makeText(this, "Mining blocks to " + address, Toast.LENGTH_SHORT).show();
        BitcoinRpcClient.getInstance().generateToAddress(address, 101, new Callback<BitcoinRpcResponse<List<String>>>() {
            @Override
            public void onResponse(Call<BitcoinRpcResponse<List<String>>> call, Response<BitcoinRpcResponse<List<String>>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().getError() == null) {
                    Toast.makeText(MainActivity.this, "Mined successfully!", Toast.LENGTH_SHORT).show();
                    refreshBalanceFromRpc();
                } else {
                    Toast.makeText(MainActivity.this, "Mining failed", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<List<String>>> call, Throwable t) {
                Toast.makeText(MainActivity.this, "Network error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showSendDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        final EditText etAddress = new EditText(this);
        etAddress.setHint("Recipient Address");
        layout.addView(etAddress);

        final EditText etAmount = new EditText(this);
        etAmount.setHint("Amount in BTC");
        etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(etAmount);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Send VPR (RegTest)")
                .setView(layout)
                .setPositiveButton("Send", (dialog, which) -> {
                    String address = etAddress.getText().toString();
                    String amountStr = etAmount.getText().toString();
                    if (!address.isEmpty() && !amountStr.isEmpty()) {
                        try {
                            long satoshis = (long) (Double.parseDouble(amountStr) * 100_000_000);
                            sendTransaction(address, satoshis);
                        } catch (Exception e) {
                            Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendTransaction(String address, long amountSats) {
        try {
            Transaction tx = walletManager.createTransaction(address, amountSats);
            walletManager.broadcastTransaction(tx);
            Toast.makeText(this, "Transaction broadcasted!", Toast.LENGTH_LONG).show();
            updateUI();
            refreshBalanceFromRpc();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private void updateUI() {
        try {
            Wallet wallet = walletManager.getWallet();
            if (wallet == null) {
                wallet = walletManager.loadWallet();
            }
            if (wallet != null) {
                displayBalance(wallet.getBalance().value);
                binding.tvNetwork.setText(Constants.COIN_DISPLAY_NAME + " " + Constants.NETWORK_NAME);

                String receiveAddress = walletManager.getCurrentReceiveAddress();
                if (receiveAddress != null) {
                    binding.tvReceiveAddress.setText(receiveAddress);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startBalancePolling() {
        if (isBalancePolling) return;
        isBalancePolling = true;
        balanceHandler.removeCallbacks(balanceRefreshRunnable);
        balanceRefreshRunnable.run();
    }

    private void stopBalancePolling() {
        isBalancePolling = false;
        balanceHandler.removeCallbacks(balanceRefreshRunnable);
    }

    private void refreshBalanceFromRpc() {
        if (isBalanceRequestInFlight || walletManager.getWallet() == null) return;

        List<String> addresses = walletManager.getIssuedReceiveAddresses();
        if (addresses.isEmpty()) return;

        isBalanceRequestInFlight = true;
        BitcoinRpcClient.getInstance().scanTxOutSetForAddresses(addresses, new Callback<BitcoinRpcResponse<BitcoinScanTxOutSetResult>>() {
            @Override
            public void onResponse(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Response<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> response) {
                isBalanceRequestInFlight = false;

                BitcoinRpcResponse<BitcoinScanTxOutSetResult> body = response.body();
                if (!response.isSuccessful() || body == null || body.getError() != null || body.getResult() == null) {
                    String message = body != null && body.getError() != null
                            ? body.getError().getMessage()
                            : "respuesta RPC inválida";
                    Log.w(TAG, "No se pudo actualizar balance: " + message);
                    return;
                }

                BitcoinScanTxOutSetResult result = body.getResult();
                if (result.isSuccess()) {
                    displayBalance(result.getTotalSats());
                }
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Throwable t) {
                isBalanceRequestInFlight = false;
                Log.w(TAG, "Error RPC actualizando balance", t);
            }
        });
    }

    private void displayBalance(long sats) {
        String balance = String.format(Locale.US, "%.8f %s", sats / 100_000_000.0, Constants.COIN_TICKER);
        binding.tvBalance.setText(balance);
        binding.tvBalanceSats.setText(sats + " sats");
    }
}
