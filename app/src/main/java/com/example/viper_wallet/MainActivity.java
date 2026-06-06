package com.example.viper_wallet;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.viper_wallet.databinding.ActivityMainBinding;
import com.example.viper_wallet.network.rpc.BitcoinRpcClient;
import com.example.viper_wallet.network.rpc.BitcoinRpcResponse;
import com.example.viper_wallet.network.rpc.BitcoinScanTxOutSetResult;
import com.example.viper_wallet.walletcore.Constants;
import com.example.viper_wallet.walletcore.WalletManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final long BALANCE_REFRESH_INTERVAL_MS = 20_000L;
    private static final int QR_CODE_SIZE_DP = 220;

    private ActivityMainBinding binding;
    private WalletManager walletManager;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private EditText pendingScanAddressEditText;
    private DecoratedBarcodeView activeScannerView;
    private final Handler balanceHandler = new Handler(Looper.getMainLooper());
    private boolean isBalancePolling;
    private boolean isBalanceRequestInFlight;
    private boolean isServerWalletInitialized;

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

        setupCameraPermissionLauncher();
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
        resumeActiveScannerIfPermitted();
    }

    @Override
    protected void onPause() {
        stopBalancePolling();
        pauseActiveScanner();
        super.onPause();
    }

    private void setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void setupCameraPermissionLauncher() {
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        resumeActiveScannerIfPermitted();
                    } else {
                        Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show();
                    }
                }
        );
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
        walletManager.startSyncAsync();
        initializeServerWalletOnce();
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

    private void initializeServerWalletOnce() {
        if (isServerWalletInitialized) return;
        isServerWalletInitialized = true;
        createServerWalletForDemo();
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
                    if (isWalletAlreadyExistsError(error)) {
                        loadServerWalletAndSync(walletName);
                    } else {
                        Toast.makeText(MainActivity.this, "Error RPC createwallet: " + message, Toast.LENGTH_LONG).show();
                    }
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

    private boolean isWalletAlreadyExistsError(BitcoinRpcResponse.BitcoinRpcError error) {
        if (error == null || error.getMessage() == null) return false;
        String message = error.getMessage().toLowerCase(Locale.US);
        return message.contains("already exists")
                || message.contains("database already exists")
                || message.contains("wallet already loaded");
    }

    private void loadServerWalletAndSync(String walletName) {
        BitcoinRpcClient.getInstance().loadServerWallet(walletName, new Callback<BitcoinRpcResponse<Object>>() {
            @Override
            public void onResponse(Call<BitcoinRpcResponse<Object>> call, Response<BitcoinRpcResponse<Object>> response) {
                BitcoinRpcResponse<Object> body = response.body();
                BitcoinRpcResponse.BitcoinRpcError error = body != null ? body.getError() : null;
                if (response.isSuccessful() && body != null && (error == null || isWalletAlreadyExistsError(error))) {
                    syncIssuedAddressesWithServer();
                    return;
                }

                String message = error != null ? error.getMessage() : "respuesta RPC inválida";
                Log.w(TAG, "loadwallet RPC: " + message);
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

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), 0);

        TextView messageView = new TextView(this);
        messageView.setText(R.string.receive_address_message);
        layout.addView(messageView);

        ImageView qrImageView = new ImageView(this);
        try {
            qrImageView.setImageBitmap(generateQrBitmap(address));
        } catch (WriterException e) {
            Log.w(TAG, "No se pudo generar el QR para la dirección " + address, e);
            qrImageView.setVisibility(View.GONE);
        }
        LinearLayout.LayoutParams qrLayoutParams = new LinearLayout.LayoutParams(
                dpToPx(QR_CODE_SIZE_DP),
                dpToPx(QR_CODE_SIZE_DP)
        );
        qrLayoutParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrLayoutParams.setMargins(0, dpToPx(16), 0, dpToPx(16));
        layout.addView(qrImageView, qrLayoutParams);

        TextView addressView = new TextView(this);
        addressView.setText(address);
        addressView.setTextIsSelectable(true);
        addressView.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(addressView);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.receive_address_title)
                .setView(layout)
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

    private Bitmap generateQrBitmap(String content) throws WriterException {
        int size = dpToPx(QR_CODE_SIZE_DP);
        BitMatrix bitMatrix = new MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                size,
                size
        );

        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int offset = y * size;
            for (int x = 0; x < size; x++) {
                pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
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
        layout.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), 0);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);

        final EditText etAddress = new EditText(this);
        etAddress.setHint("Recipient Address");
        etAddress.setSingleLine(true);
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        addressRow.addView(etAddress, addressParams);

        MaterialButton btnScanQr = new MaterialButton(this);
        btnScanQr.setText("Escanear QR");
        btnScanQr.setIconResource(android.R.drawable.ic_menu_camera);
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        scanParams.setMargins(dpToPx(12), 0, 0, 0);
        addressRow.addView(btnScanQr, scanParams);
        layout.addView(addressRow);

        FrameLayout scannerFrame = new FrameLayout(this);
        scannerFrame.setVisibility(View.GONE);
        LinearLayout.LayoutParams scannerParams = new LinearLayout.LayoutParams(
                dpToPx(260),
                dpToPx(260)
        );
        scannerParams.gravity = Gravity.CENTER_HORIZONTAL;
        scannerParams.setMargins(0, dpToPx(12), 0, dpToPx(12));

        DecoratedBarcodeView scannerView = new DecoratedBarcodeView(this);
        scannerView.setStatusText("");
        scannerFrame.addView(scannerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        layout.addView(scannerFrame, scannerParams);

        btnScanQr.setOnClickListener(v -> startEmbeddedQrScanner(etAddress, scannerView, scannerFrame));

        final EditText etAmount = new EditText(this);
        etAmount.setHint("Amount in BTC");
        etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(etAmount);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Send VPR (RegTest)")
                .setView(layout)
                .setPositiveButton("Send", (dialogInterface, which) -> {
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
                .create();
        dialog.setOnDismissListener(dialogInterface -> {
            if (pendingScanAddressEditText == etAddress) {
                pendingScanAddressEditText = null;
            }
            pauseActiveScanner();
            if (activeScannerView == scannerView) {
                activeScannerView = null;
            }
        });
        dialog.show();
    }

    private void startEmbeddedQrScanner(
            EditText destinationEditText,
            DecoratedBarcodeView scannerView,
            FrameLayout scannerFrame
    ) {
        pendingScanAddressEditText = destinationEditText;
        activeScannerView = scannerView;
        scannerFrame.setVisibility(View.VISIBLE);

        scannerView.decodeSingle(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (result == null || result.getText() == null) return;

                String scannedAddress = extractBitcoinAddress(result.getText());
                destinationEditText.setText(scannedAddress);
                destinationEditText.setSelection(scannedAddress.length());
                scannerView.pause();
                scannerFrame.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "Dirección QR cargada", Toast.LENGTH_SHORT).show();
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scannerView.resume();
            return;
        }

        cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
    }

    private void resumeActiveScannerIfPermitted() {
        if (activeScannerView == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            activeScannerView.resume();
        }
    }

    private void pauseActiveScanner() {
        if (activeScannerView != null) {
            activeScannerView.pause();
        }
    }

    private String extractBitcoinAddress(String scannedValue) {
        String value = scannedValue.trim();
        String lowerValue = value.toLowerCase(Locale.US);
        if (lowerValue.startsWith("bitcoin:")) {
            String addressWithQuery = value.substring("bitcoin:".length());
            int queryIndex = addressWithQuery.indexOf('?');
            return queryIndex >= 0
                    ? addressWithQuery.substring(0, queryIndex)
                    : addressWithQuery;
        }
        return value;
    }

    private void sendTransaction(String address, long amountSats) {
        List<String> walletAddresses = walletManager.getIssuedReceiveAddresses();
        if (walletAddresses.isEmpty()) {
            Toast.makeText(this, "La wallet aún no tiene direcciones sincronizadas", Toast.LENGTH_LONG).show();
            return;
        }

        BitcoinRpcClient.getInstance().getBlockchainInfo(new Callback<BitcoinRpcResponse<Object>>() {
            @Override
            public void onResponse(Call<BitcoinRpcResponse<Object>> call, Response<BitcoinRpcResponse<Object>> response) {
                BitcoinRpcResponse<Object> body = response.body();
                if (!response.isSuccessful() || body == null || body.getError() != null) {
                    String message = body != null && body.getError() != null
                            ? body.getError().getMessage()
                            : "respuesta RPC inválida";
                    Toast.makeText(MainActivity.this, "No se pudo leer altura del nodo: " + message, Toast.LENGTH_LONG).show();
                    return;
                }

                Integer chainHeight = extractBlockHeight(body.getResult());
                if (chainHeight == null) {
                    Toast.makeText(MainActivity.this, "El nodo no devolvió altura de bloques válida", Toast.LENGTH_LONG).show();
                    return;
                }

                scanRpcUtxosAndSend(address, amountSats, walletAddresses, chainHeight);
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<Object>> call, Throwable t) {
                Toast.makeText(MainActivity.this, "Error RPC leyendo nodo: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void scanRpcUtxosAndSend(
            String address,
            long amountSats,
            List<String> walletAddresses,
            int chainHeight
    ) {
        BitcoinRpcClient.getInstance().scanTxOutSetForAddresses(walletAddresses, new Callback<BitcoinRpcResponse<BitcoinScanTxOutSetResult>>() {
            @Override
            public void onResponse(
                    Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call,
                    Response<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> response
            ) {
                BitcoinRpcResponse<BitcoinScanTxOutSetResult> body = response.body();
                if (!response.isSuccessful() || body == null || body.getError() != null || body.getResult() == null) {
                    String message = body != null && body.getError() != null
                            ? body.getError().getMessage()
                            : "respuesta RPC inválida";
                    Toast.makeText(MainActivity.this, "No se pudieron leer UTXOs: " + message, Toast.LENGTH_LONG).show();
                    return;
                }

                BitcoinScanTxOutSetResult result = body.getResult();
                List<WalletManager.WalletUtxo> walletUtxos = toWalletUtxos(result, walletAddresses);
                if (walletUtxos.isEmpty()) {
                    Toast.makeText(
                            MainActivity.this,
                            "El nodo ve 0 UTXOs gastables para esta wallet. Revisa que recibiste/minaste a esta dirección.",
                            Toast.LENGTH_LONG
                    ).show();
                    return;
                }

                walletManager.setRpcUtxos(walletUtxos, chainHeight);
                createSignAndBroadcastTransaction(address, amountSats);
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Throwable t) {
                Toast.makeText(MainActivity.this, "Error RPC leyendo UTXOs: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void createSignAndBroadcastTransaction(String address, long amountSats) {
        try {
            Transaction tx = walletManager.createTransaction(address, amountSats);
            BitcoinRpcClient.getInstance().sendRawTransaction(toHex(tx.serialize()), new Callback<BitcoinRpcResponse<String>>() {
                @Override
                public void onResponse(Call<BitcoinRpcResponse<String>> call, Response<BitcoinRpcResponse<String>> response) {
                    BitcoinRpcResponse<String> body = response.body();
                    if (response.isSuccessful() && body != null && body.getError() == null) {
                        Toast.makeText(MainActivity.this, "Transacción enviada: " + body.getResult(), Toast.LENGTH_LONG).show();
                        updateUI();
                        refreshBalanceFromRpc();
                        return;
                    }

                    String message = body != null && body.getError() != null
                            ? body.getError().getMessage()
                            : "respuesta RPC inválida";
                    Toast.makeText(MainActivity.this, "No se pudo transmitir: " + message, Toast.LENGTH_LONG).show();
                    Log.w(TAG, "sendrawtransaction RPC: " + message);
                }

                @Override
                public void onFailure(Call<BitcoinRpcResponse<String>> call, Throwable t) {
                    Toast.makeText(MainActivity.this, "Error RPC enviando tx: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    Log.w(TAG, "Error RPC sendrawtransaction", t);
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private List<WalletManager.WalletUtxo> toWalletUtxos(
            BitcoinScanTxOutSetResult result,
            List<String> walletAddresses
    ) {
        List<WalletManager.WalletUtxo> walletUtxos = new ArrayList<>();
        if (result.getUnspents() == null) return walletUtxos;

        for (BitcoinScanTxOutSetResult.Unspent unspent : result.getUnspents()) {
            String scriptPubKey = unspent.getScriptPubKey();
            if (scriptPubKey == null || scriptPubKey.isEmpty()) {
                Log.w(TAG, "UTXO sin scriptPubKey, se omite: " + unspent.getTxid() + ":" + unspent.getVout());
                continue;
            }

            String ownerAddress = findOwnerAddress(unspent, walletAddresses);
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

    @SuppressWarnings("unchecked")
    private Integer extractBlockHeight(Object blockchainInfoResult) {
        if (!(blockchainInfoResult instanceof Map)) return null;
        Object blocks = ((Map<String, Object>) blockchainInfoResult).get("blocks");
        if (blocks instanceof Number) {
            return ((Number) blocks).intValue();
        }
        if (blocks != null) {
            try {
                return Integer.parseInt(blocks.toString());
            } catch (NumberFormatException e) {
                Log.w(TAG, "Altura de bloques inválida: " + blocks, e);
            }
        }
        return null;
    }

    private String toHex(byte[] bytes) {
        char[] hexArray = "0123456789abcdef".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            hexChars[i * 2] = hexArray[value >>> 4];
            hexChars[i * 2 + 1] = hexArray[value & 0x0F];
        }
        return new String(hexChars);
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
