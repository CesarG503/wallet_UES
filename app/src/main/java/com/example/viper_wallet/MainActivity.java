package com.example.viper_wallet;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextUtils;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.PopupMenu;
import android.content.Context;

import com.example.viper_wallet.adapters.TransactionAdapter;
import com.example.viper_wallet.auth.AuthManager;
import com.example.viper_wallet.auth.LoginActivity;
import com.example.viper_wallet.auth.ProfileActivity;
import com.example.viper_wallet.databinding.ActivityMainBinding;
import com.example.viper_wallet.models.TransactionRecord;
import com.example.viper_wallet.network.api.ApiEnvelope;
import com.example.viper_wallet.network.api.MiningApiClient;
import com.example.viper_wallet.network.api.MiningSubmitResult;
import com.example.viper_wallet.network.api.MiningWork;
import com.example.viper_wallet.network.rpc.BitcoinRpcClient;
import com.example.viper_wallet.network.rpc.BitcoinRpcResponse;
import com.example.viper_wallet.network.rpc.BitcoinScanTxOutSetResult;
import com.example.viper_wallet.walletcore.Constants;
import com.example.viper_wallet.walletcore.WalletManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import org.bitcoinj.base.Address;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.wallet.Wallet;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final long BALANCE_REFRESH_INTERVAL_MS = 20_000L;
    private static final long INITIAL_BLOCK_SUBSIDY_SATS = 5_000_000_000L;
    private static final long FALLBACK_REGTEST_BLOCK_REWARD_SATS = 5_000_000_000L;
    private static final int REGTEST_SUBSIDY_HALVING_INTERVAL = 150;
    private static final int COINBASE_MATURITY_CONFIRMATIONS = 100;
    private static final int QR_CODE_SIZE_DP = 220;

    private ActivityMainBinding binding;
    private WalletManager walletManager;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private EditText pendingScanAddressEditText;
    private DecoratedBarcodeView activeScannerView;
    private final Handler balanceHandler = new Handler(Looper.getMainLooper());
    private final Handler miningHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService miningExecutor = Executors.newSingleThreadExecutor();
    private boolean isBalancePolling;
    private boolean isBalanceRequestInFlight;
    private boolean isBalanceRefreshQueued;
    private static boolean isServerWalletInitialized;
    private boolean isMining;
    private boolean isMiningRequestInFlight;
    private volatile boolean shouldMineCurrentWork;
    private String activeMiningAddress;
    private String activeMiningJobId;
    private int minedBlocksThisSession;
    private long minedSatsThisSession;
    private volatile long miningAttemptsThisJob;
    private volatile double currentMiningHashRate;
    private AlertDialog miningDialog;
    private View miningCircleView;
    private TextView miningCircleText;
    private TextView miningStatusText;
    private TextView miningStatsText;
    private AnimatorSet miningPulseAnimator;
    private long lastTotalBalanceSats;
    private long lastSpendableBalanceSats;
    private long lastImmatureMiningSats;
    private int lastMiningMaturityBlocks;

    private final Runnable balanceRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshBalanceFromRpc();
            if (isBalancePolling) {
                balanceHandler.postDelayed(this, BALANCE_REFRESH_INTERVAL_MS);
            }
        }
    };

    private final Runnable miningCountdownRunnable = new Runnable() {
        @Override
        public void run() {
            updateMiningStats();
            if (isMining) {
                miningHandler.postDelayed(this, 1_000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Verificar sesión de Firebase
        //    La biometría ya fue verificada en LoginActivity antes de llegar aquí.
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            goToLogin();
            return;
        }

        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        walletManager = WalletManager.getInstance(this);

        setupCameraPermissionLauncher();
        setupNotificationPermissionLauncher();
        setupWindowInsets();
        setupListeners();

        // 2. Verificar estado de la wallet
        checkWalletState();
    }

    private void goToLogin() {
        isServerWalletInitialized = false;
        BalanceDetailsActivity.lastScanResult = null;
        BalanceDetailsActivity.lastAddresses = null;
        walletManager.reset();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null && binding.layoutDashboard.getVisibility() == View.VISIBLE) {
            startBalancePolling();
            loadDashboardTransactions();
        }
        resumeActiveScannerIfPermitted();
    }

    @Override
    protected void onPause() {
        stopBalancePolling();
        stopMiningSession();
        pauseActiveScanner();
        super.onPause();
    }

    private void setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Aplicar padding lateral y inferior al root para evitar solapamiento con gestos/botones
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom);
            
            // Aplicar el padding superior (Notch/Status Bar) al primer contenedor del dashboard
            // para que el fondo del ScrollView sí suba hasta arriba.
            binding.innerDashboard.setPadding(
                binding.innerDashboard.getPaddingLeft(),
                systemBars.top,
                binding.innerDashboard.getPaddingRight(),
                binding.innerDashboard.getPaddingBottom()
            );

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

    private void setupNotificationPermissionLauncher() {
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        registerPushNotifications();
                    }
                }
        );
    }

    private void checkWalletState() {
        try {
            Wallet wallet = walletManager.loadWallet();
            if (wallet != null) {
                showDashboard();
                return;
            }
        } catch (IOException e) {
            handleWalletLoadFailure(e);
            return;
        }

        // No hay wallet local → intentar restaurar desde el backup en Firebase
        tryRestoreWalletFromCloud();
    }

    private void handleWalletLoadFailure(Exception e) {
        Log.e(TAG, "Wallet local ilegible", e);
        Toast.makeText(
                this,
                "La wallet local está dañada. Vamos a restaurarla desde tu backup.",
                Toast.LENGTH_LONG
        ).show();
        tryRestoreWalletFromCloud();
    }

    /**
     * Verifica si el usuario tiene un backup de wallet en Firestore.
     * Si existe → pide contraseña AES y restaura la wallet.
     * Si no existe → cierra sesión y va a Login (debe registrarse).
     * Si hay error de red → muestra pantalla de Setup como fallback.
     */
    private void tryRestoreWalletFromCloud() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { goToLogin(); return; }

        AuthManager.getInstance().checkUserExists(user.getUid(), new AuthManager.CheckUserCallback() {
            @Override
            public void onResult(boolean hasBackup) {
                if (hasBackup) {
                    showRestoreWalletDialog(user.getUid());
                } else {
                    // No hay backup — cuenta sin wallet (caso raro: registro incompleto)
                    FirebaseAuth.getInstance().signOut();
                    Toast.makeText(MainActivity.this,
                            "No se encontró una wallet asociada. Por favor regístrate.",
                            Toast.LENGTH_LONG).show();
                    goToLogin();
                }
            }

            @Override
            public void onError(String message) {
                // Error de red — redirigir a login
                Toast.makeText(MainActivity.this,
                        "Sin conexión. Verifica tu internet.", Toast.LENGTH_LONG).show();
                goToLogin();
            }
        });
    }

    /**
     * Muestra un diálogo para pedir la contraseña AES y restaurar la wallet
     * descifrando la semilla desde Firestore.
     */
    private void showRestoreWalletDialog(String uid) {
        final android.widget.EditText etWalletPass = new android.widget.EditText(this);
        etWalletPass.setHint("Contraseña de tu Billetera");
        etWalletPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Restaurar Wallet")
                .setMessage("Ingresa tu contraseña de cifrado (AES) para descifrar y restaurar tu wallet desde el respaldo en la nube.")
                .setView(etWalletPass)
                .setCancelable(false)
                .setPositiveButton("Restaurar", null)
                .setNegativeButton("Cerrar Sesión", (d, w) -> {
                    FirebaseAuth.getInstance().signOut();
                    goToLogin();
                })
                .create();

        dialog.show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String walletPass = etWalletPass.getText().toString();
            if (walletPass.isEmpty()) {
                etWalletPass.setError("Ingresa tu contraseña");
                return;
            }

            AuthManager.getInstance().getDecryptedSeed(uid, walletPass, new AuthManager.SeedCallback() {
                @Override
                public void onSeedReady(String mnemonic) {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    etWalletPass.setEnabled(false);
                    walletManager.restoreWalletFromMnemonicAsync(mnemonic, walletPass, new WalletManager.SimpleCallback() {
                        @Override
                        public void onSuccess() {
                            dialog.dismiss();
                            walletManager.saveWalletPasswordSecurely(uid, walletPass);
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.wallet_restore_success), Toast.LENGTH_SHORT).show();
                            showDashboard();
                        }

                        @Override
                        public void onError(Exception e) {
                            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            etWalletPass.setEnabled(true);
                            etWalletPass.setError("Error al restaurar: " + e.getMessage());
                        }
                    });
                }

                @Override
                public void onError(String message) {
                    etWalletPass.setError("Contraseña incorrecta o error de red");
                }
            });
        });
    }

    private void showDashboard() {
        binding.layoutDashboard.setVisibility(View.VISIBLE);
        updateUI();
        loadDashboardTransactions();
        walletManager.startSyncAsync();
        initializeServerWalletOnce();
        startBalancePolling();
        checkNotificationPermission();
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                registerPushNotifications();
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            registerPushNotifications();
        }
    }

    private void registerPushNotifications() {
        String address = walletManager.getCurrentReceiveAddress();
        if (address == null) return;

        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                return;
            }

            String token = task.getResult();
            Log.d(TAG, "FCM Token: " + token);

            MiningApiClient.getInstance().registerDevice(token, address, new Callback<Map<String, Object>>() {
                @Override
                public void onResponse(Call<Map<String, Object>> call, Response<Map<String, Object>> response) {
                    if (response.isSuccessful()) {
                        Log.i(TAG, "Push notifications registered for address: " + address);
                    }
                }

                @Override
                public void onFailure(Call<Map<String, Object>> call, Throwable t) {
                    Log.w(TAG, "Failed to register push notifications", t);
                }
            });
        });
    }

    private void setupListeners() {

        // Abrir Perfil
        binding.btnProfile.setOnClickListener(v -> {
            startActivity(new Intent(this, ProfileActivity.class));
        });

        // Recargar UESCoin
        binding.btnReload.setOnClickListener(v -> {
            String address = walletManager.getCurrentReceiveAddress();
            if (address != null) {
                showMiningDialog(address);
            } else {
                Toast.makeText(this, "No hay dirección disponible para recargar", Toast.LENGTH_SHORT).show();
            }
        });

        // Servicios
        binding.btnServices.setOnClickListener(v -> showServicesDialog());

        binding.btnReceive.setOnClickListener(v -> {
            startActivity(new Intent(this, ReceiveActivity.class));
        });
        binding.btnSend.setOnClickListener(v -> {
            startActivity(new Intent(this, TransferActivity.class));
        });
        binding.layoutBalanceSummary.setOnClickListener(v ->
                startActivity(new Intent(this, BalanceDetailsActivity.class))
        );
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
                    BitcoinRpcResponse.BitcoinRpcError error = response.body() != null ? response.body().getError() : null;
                    String message = "respuesta RPC inválida";
                    if (error != null) {
                        message = error.getMessage();
                    } else {
                        BitcoinRpcResponse<?> errorResponse = parseErrorResponse(response);
                        if (errorResponse != null && errorResponse.getError() != null) {
                            message = errorResponse.getError().getMessage();
                            error = errorResponse.getError();
                        }
                    }
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

                String message = "respuesta RPC inválida";
                if (error != null) {
                    message = error.getMessage();
                } else {
                    BitcoinRpcResponse<?> errorResponse = parseErrorResponse(response);
                    if (errorResponse != null && errorResponse.getError() != null) {
                        message = errorResponse.getError().getMessage();
                    }
                }
                Log.w(TAG, "loadwallet RPC: " + message);
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<Object>> call, Throwable t) {
                Log.w(TAG, "No se pudo cargar wallet RPC " + walletName, t);
            }
        });
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
                            String message = "respuesta RPC inválida";
                            if (body != null && body.getError() != null) {
                                message = body.getError().getMessage();
                            } else {
                                BitcoinRpcResponse<?> errorResponse = parseErrorResponse(response);
                                if (errorResponse != null && errorResponse.getError() != null) {
                                    message = errorResponse.getError().getMessage();
                                }
                            }
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

    private void showMiningDialog(String address) {
        if (miningDialog != null && miningDialog.isShowing()) {
            return;
        }

        activeMiningAddress = address;
        registerAddressWithServer(address, false);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dpToPx(24), dpToPx(18), dpToPx(24), dpToPx(8));

        miningCircleView = createMiningCircleView();
        layout.addView(miningCircleView);

        miningStatusText = new TextView(this);
        miningStatusText.setGravity(Gravity.CENTER_HORIZONTAL);
        miningStatusText.setTextColor(ContextCompat.getColor(this, R.color.onSurface));
        miningStatusText.setTextSize(16);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.setMargins(0, dpToPx(18), 0, 0);
        layout.addView(miningStatusText, statusParams);

        miningStatsText = new TextView(this);
        miningStatsText.setGravity(Gravity.CENTER_HORIZONTAL);
        miningStatsText.setTextColor(Color.parseColor("#64748B"));
        miningStatsText.setTextSize(13);
        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statsParams.setMargins(0, dpToPx(8), 0, 0);
        layout.addView(miningStatsText, statsParams);

        TextView addressView = new TextView(this);
        addressView.setGravity(Gravity.CENTER_HORIZONTAL);
        addressView.setTextColor(Color.parseColor("#64748B"));
        addressView.setTextSize(12);
        addressView.setText(address);
        addressView.setSingleLine(false);
        addressView.setTextIsSelectable(true);
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        addressParams.setMargins(0, dpToPx(14), 0, 0);
        layout.addView(addressView, addressParams);

        miningDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Recargar minando")
                .setView(layout)
                .setNegativeButton("Cerrar", null)
                .create();

        miningDialog.setOnShowListener(dialog -> startMiningSession(address));
        miningDialog.setOnDismissListener(dialog -> {
            stopMiningSession();
            clearMiningDialogReferences();
        });
        miningDialog.show();
    }

    private int getThemeColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private View createMiningCircleView() {
        FrameLayout circle = new FrameLayout(this);
        int circleSize = dpToPx(236);
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(circleSize, circleSize);
        circleParams.gravity = Gravity.CENTER_HORIZONTAL;
        circle.setLayoutParams(circleParams);
        circle.setClickable(true);
        circle.setFocusable(true);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(getThemeColor(androidx.appcompat.R.attr.colorPrimary));
        background.setStroke(dpToPx(4), getThemeColor(com.google.android.material.R.attr.colorTertiary));
        circle.setBackground(background);

        miningCircleText = new TextView(this);
        miningCircleText.setGravity(Gravity.CENTER);
        miningCircleText.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnPrimary));
        miningCircleText.setTextSize(30);
        miningCircleText.setTypeface(Typeface.DEFAULT_BOLD);
        miningCircleText.setText("MINANDO");
        circle.addView(miningCircleText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        circle.setOnClickListener(v -> toggleMiningSession());
        return circle;
    }

    private void startMiningSession(String address) {
        activeMiningAddress = address;
        activeMiningJobId = null;
        minedBlocksThisSession = 0;
        minedSatsThisSession = 0L;
        miningAttemptsThisJob = 0L;
        currentMiningHashRate = 0;
        isMining = true;
        updateMiningStatus("Pidiendo trabajo de minería a la red...", true);
        updateMiningStats();
        startMiningPulseAnimation();
        miningHandler.removeCallbacks(miningCountdownRunnable);
        requestMiningWork();
        miningHandler.post(miningCountdownRunnable);
    }

    private void toggleMiningSession() {
        if (isMining) {
            stopMiningSession();
            updateMiningStatus("Minería pausada. Toca el círculo para continuar.", false);
            updateMiningStats();
        } else if (activeMiningAddress != null) {
            isMining = true;
            updateMiningStatus("Reanudando minería...", true);
            startMiningPulseAnimation();
            miningHandler.removeCallbacks(miningCountdownRunnable);
            requestMiningWork();
            miningHandler.post(miningCountdownRunnable);
        }
    }

    private void stopMiningSession() {
        isMining = false;
        shouldMineCurrentWork = false;
        activeMiningJobId = null;
        miningHandler.removeCallbacks(miningCountdownRunnable);
        stopMiningPulseAnimation();
        if (miningCircleText != null) {
            miningCircleText.setText("PAUSADO");
        }
    }

    private void clearMiningDialogReferences() {
        miningDialog = null;
        miningCircleView = null;
        miningCircleText = null;
        miningStatusText = null;
        miningStatsText = null;
        miningPulseAnimator = null;
    }

    private void requestMiningWork() {
        if (!isMining || isMiningRequestInFlight || activeMiningAddress == null) return;

        isMiningRequestInFlight = true;
        shouldMineCurrentWork = false;
        miningAttemptsThisJob = 0L;
        currentMiningHashRate = 0;
        updateMiningStatus("Solicitando bloque candidato...", true);
        updateMiningStats();

        MiningApiClient.getInstance().getWork(activeMiningAddress, new Callback<ApiEnvelope<MiningWork>>() {
            @Override
            public void onResponse(Call<ApiEnvelope<MiningWork>> call, Response<ApiEnvelope<MiningWork>> response) {
                isMiningRequestInFlight = false;
                ApiEnvelope<MiningWork> body = response.body();
                if (response.isSuccessful() && body != null && body.getData() != null) {
                    startMiningWork(body.getData());
                    return;
                }

                handleMiningWorkError("No se pudo obtener trabajo de minería");
            }

            @Override
            public void onFailure(Call<ApiEnvelope<MiningWork>> call, Throwable t) {
                isMiningRequestInFlight = false;
                handleMiningWorkError("Error de red pidiendo trabajo: " + t.getMessage());
            }
        });
    }

    private void startMiningWork(MiningWork work) {
        if (!isMining || work.getJobId() == null || work.getPayloadPrefix() == null) return;

        activeMiningJobId = work.getJobId();
        shouldMineCurrentWork = true;
        miningAttemptsThisJob = 0L;
        currentMiningHashRate = 0;
        updateMiningStatus("Minando bloque #" + work.getHeight() + " · objetivo " + work.getTargetPrefix(), true);
        updateMiningStats();

        miningExecutor.execute(() -> mineWorkInBackground(work));
    }

    private void mineWorkInBackground(MiningWork work) {
        long nonce = Math.max(0L, System.nanoTime());
        long attempts = 0L;
        long startedAt = System.currentTimeMillis();
        long lastUiUpdateAt = startedAt;

        while (isMining && shouldMineCurrentWork && work.getJobId().equals(activeMiningJobId)) {
            String nonceString = Long.toUnsignedString(nonce);
            String hash = doubleSha256Hex(work.getPayloadPrefix() + nonceString);
            attempts++;
            miningAttemptsThisJob = attempts;

            long now = System.currentTimeMillis();
            if (now - lastUiUpdateAt >= 1_000L) {
                currentMiningHashRate = attempts * 1_000.0 / Math.max(1L, now - startedAt);
                lastUiUpdateAt = now;
                runOnUiThread(this::updateMiningStats);
            }

            if (hash != null && hashMeetsTarget(hash, work.getTargetPrefix())) {
                currentMiningHashRate = attempts * 1_000.0 / Math.max(1L, now - startedAt);
                String finalNonce = nonceString;
                String finalHash = hash;
                long finalAttempts = attempts;
                runOnUiThread(() -> submitMiningSolution(work, finalNonce, finalHash, finalAttempts, currentMiningHashRate));
                return;
            }

            nonce++;
        }
    }

    private String doubleSha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] first = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            byte[] second = digest.digest(first);
            return toHex(second);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 no disponible", e);
            return null;
        }
    }

    private boolean hashMeetsTarget(String hash, String targetPrefix) {
        return hash != null
                && targetPrefix != null
                && hash.toLowerCase(Locale.US).startsWith(targetPrefix.toLowerCase(Locale.US));
    }

    private void submitMiningSolution(
            MiningWork work,
            String nonce,
            String hash,
            long attempts,
            double hashRate
    ) {
        if (!isMining || activeMiningAddress == null || !work.getJobId().equals(activeMiningJobId)) return;

        shouldMineCurrentWork = false;
        isMiningRequestInFlight = true;
        updateMiningStatus("Solución encontrada. Enviando prueba a la red...", true);
        updateMiningStats();

        MiningApiClient.getInstance().submitSolution(
                work.getJobId(),
                activeMiningAddress,
                nonce,
                attempts,
                hashRate,
                new Callback<ApiEnvelope<MiningSubmitResult>>() {
                    @Override
                    public void onResponse(
                            Call<ApiEnvelope<MiningSubmitResult>> call,
                            Response<ApiEnvelope<MiningSubmitResult>> response
                    ) {
                        isMiningRequestInFlight = false;
                        ApiEnvelope<MiningSubmitResult> body = response.body();
                        MiningSubmitResult result = body != null ? body.getData() : null;

                        if (response.isSuccessful() && result != null && result.isAccepted()) {
                            finishMinedBlock(work, result);
                            return;
                        }

                        String message = result != null && result.getMessage() != null
                                ? result.getMessage()
                                : "Otro usuario ganó o la solución expiró";
                        handleMiningWorkError(message);
                    }

                    @Override
                    public void onFailure(Call<ApiEnvelope<MiningSubmitResult>> call, Throwable t) {
                        isMiningRequestInFlight = false;
                        handleMiningWorkError("Error enviando solución: " + t.getMessage());
                    }
                }
        );
    }

    private void finishMinedBlock(MiningWork work, MiningSubmitResult result) {
        long rewardSats = resolveMiningRewardSats(work, result);
        String blockHash = result.getBlockHash() != null
                ? result.getBlockHash()
                : "job_" + result.getJobId();

        minedBlocksThisSession++;
        minedSatsThisSession += rewardSats;

        String miningTxId = result.getCoinbaseTxId() != null && !result.getCoinbaseTxId().isEmpty()
                ? result.getCoinbaseTxId()
                : "mining_" + blockHash;
        AuthManager.getInstance().saveTransaction(new TransactionRecord(
                miningTxId,
                "MINING",
                rewardSats,
                activeMiningAddress
        ));

        String shortHash = blockHash.length() > 12 ? blockHash.substring(0, 12) : blockHash;
        updateMiningStatus("Ganaste el bloque " + shortHash + ". Recompensa: " + formatCoinAmount(rewardSats), isMining);
        updateMiningStats();
        refreshBalanceFromRpc();
        loadDashboardTransactions();

        if (isMining) {
            requestMiningWork();
        }
    }

    private long resolveMiningRewardSats(MiningWork work, MiningSubmitResult result) {
        boolean apiReturnedCoinbase = result.getCoinbaseTxId() != null && !result.getCoinbaseTxId().isEmpty();
        if (apiReturnedCoinbase && result.getRewardSats() > 0) {
            return result.getRewardSats();
        }

        if (work != null && work.getHeight() > 0) {
            return estimatedRegtestRewardSatsForHeight(work.getHeight());
        }

        return result.getRewardSats() > 0
                ? result.getRewardSats()
                : FALLBACK_REGTEST_BLOCK_REWARD_SATS;
    }

    private long estimatedRegtestRewardSatsForHeight(int height) {
        int halvings = Math.max(0, height) / REGTEST_SUBSIDY_HALVING_INTERVAL;
        if (halvings >= 63) {
            return 0L;
        }
        return INITIAL_BLOCK_SUBSIDY_SATS >> halvings;
    }

    private void handleMiningWorkError(String message) {
        Log.w(TAG, message);
        updateMiningStatus(message, isMining);
        updateMiningStats();

        if (isMining) {
            miningHandler.postDelayed(this::requestMiningWork, 1_500L);
        }
    }

    private void updateMiningStatus(String message, boolean miningNow) {
        if (miningCircleText != null) {
            miningCircleText.setText(miningNow ? "MINANDO" : "PAUSADO");
        }
        if (miningStatusText != null) {
            miningStatusText.setText(message);
        }
    }

    private void updateMiningStats() {
        if (miningStatsText == null) return;

        StringBuilder stats = new StringBuilder();
        stats.append("Bloques ganados: ")
                .append(minedBlocksThisSession)
                .append(" · Total: ")
                .append(formatCoinAmount(minedSatsThisSession));

        if (activeMiningJobId != null) {
            stats.append("\nIntentos: ")
                    .append(miningAttemptsThisJob)
                    .append(" · ")
                    .append(String.format(Locale.US, "%.0f H/s", currentMiningHashRate));
        }

        if (isMiningRequestInFlight) {
            stats.append("\nEsperando respuesta de la red...");
        }

        miningStatsText.setText(stats.toString());
    }

    private void startMiningPulseAnimation() {
        if (miningCircleView == null) return;
        if (miningPulseAnimator != null && miningPulseAnimator.isStarted()) return;

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(miningCircleView, View.SCALE_X, 1f, 1.08f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(miningCircleView, View.SCALE_Y, 1f, 1.08f, 1f);
        scaleX.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        scaleY.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        scaleX.setDuration(1_150L);
        scaleY.setDuration(1_150L);
        scaleX.setInterpolator(new LinearInterpolator());
        scaleY.setInterpolator(new LinearInterpolator());

        miningPulseAnimator = new AnimatorSet();
        miningPulseAnimator.playTogether(scaleX, scaleY);
        miningPulseAnimator.start();
    }

    private void stopMiningPulseAnimation() {
        if (miningPulseAnimator != null) {
            miningPulseAnimator.cancel();
            miningPulseAnimator = null;
        }
        if (miningCircleView != null) {
            miningCircleView.setScaleX(1f);
            miningCircleView.setScaleY(1f);
        }
    }

    private void showSendDialog() {
        showSendDialog(null);
    }

    private void showSendDialog(String prefilledAddress) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), 0);

        TextView availableBalanceView = new TextView(this);
        availableBalanceView.setText(sendDialogBalanceText());
        availableBalanceView.setTextColor(Color.parseColor("#64748B"));
        availableBalanceView.setTextSize(14);
        layout.addView(availableBalanceView);

        final EditText etAddress = new EditText(this);
        etAddress.setHint("Dirección destino");
        etAddress.setSingleLine(true);
        if (prefilledAddress != null && !prefilledAddress.isEmpty()) {
            etAddress.setText(prefilledAddress);
            etAddress.setSelection(prefilledAddress.length());
        }
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        addressParams.setMargins(0, dpToPx(8), 0, 0);
        layout.addView(etAddress, addressParams);

        FrameLayout scannerFrame = new FrameLayout(this);
        scannerFrame.setVisibility(prefilledAddress == null || prefilledAddress.isEmpty() ? View.VISIBLE : View.GONE);
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

        MaterialButton btnSaveFriend = new MaterialButton(this);
        btnSaveFriend.setText("Guardar como amigo");
        btnSaveFriend.setIconResource(R.drawable.ic_plus);
        layout.addView(btnSaveFriend);

        final EditText etAmount = new EditText(this);
        etAmount.setHint("Monto en UESCoin");
        etAmount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(etAmount);

        MaterialButton btnUseMax = new MaterialButton(this);
        btnUseMax.setText("Usar máximo");
        btnUseMax.setIconResource(R.drawable.ic_arrow_up_right);
        layout.addView(btnUseMax);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Enviar UESCoin")
                .setView(layout)
                .setPositiveButton("Enviar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        btnSaveFriend.setOnClickListener(v -> {
            String address = etAddress.getText().toString().trim();
            if (address.isEmpty()) {
                etAddress.setError("Escanea o escribe una dirección");
                return;
            }
            showSaveFriendDialog(address);
        });
        btnUseMax.setOnClickListener(v -> etAmount.setText(satsToPlainCoin(lastSpendableBalanceSats)));
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
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = etAddress.getText().toString().trim();
            String amountStr = etAmount.getText().toString().trim();
            if (address.isEmpty()) {
                etAddress.setError("Escanea o escribe una dirección");
                return;
            }
            if (!isValidAddress(address)) {
                etAddress.setError("Dirección inválida para " + Constants.NETWORK_NAME);
                return;
            }
            if (amountStr.isEmpty()) {
                etAmount.setError("Escribe el monto");
                return;
            }
            try {
                long satoshis = parseCoinAmountToSats(amountStr);
                if (satoshis <= 0) {
                    etAmount.setError("El monto debe ser mayor que 0");
                    return;
                }
                dialog.dismiss();
                sendTransaction(address, satoshis);
            } catch (Exception e) {
                etAmount.setError("Monto inválido");
            }
        });
        if (prefilledAddress == null || prefilledAddress.isEmpty()) {
            scannerFrame.post(() -> startEmbeddedQrScanner(etAddress, scannerView, scannerFrame));
        }
    }

    private void showServicesDialog() {
        android.widget.GridLayout gridLayout = new android.widget.GridLayout(this);
        gridLayout.setColumnCount(2);
        gridLayout.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Servicios")
                .setView(gridLayout)
                .create();

        gridLayout.addView(createServiceCard("Historial", R.drawable.ic_history_mdi, v -> {
            dialog.dismiss();
            startActivity(new Intent(this, com.example.viper_wallet.auth.TransactionHistoryActivity.class));
        }));

        gridLayout.addView(createServiceCard("Amigos", R.drawable.ic_people_mdi, v -> {
            dialog.dismiss();
            showFriendsDialog();
        }));

        gridLayout.addView(createServiceCard("Mi QR", R.drawable.ic_qrcode_mdi, v -> {
            dialog.dismiss();
            startActivity(new Intent(this, ReceiveActivity.class));
        }));

        gridLayout.addView(createServiceCard("Copiar Dir.", R.drawable.ic_copy_mdi, v -> {
            dialog.dismiss();
            String address = walletManager.getCurrentReceiveAddress();
            if (address != null) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Dirección", address);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "Dirección copiada", Toast.LENGTH_SHORT).show();
            }
        }));

        dialog.show();
    }

    private View createServiceCard(String title, int iconRes, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dpToPx(12), dpToPx(20), dpToPx(12), dpToPx(20));
        card.setClickable(true);
        card.setFocusable(true);

        // Usar colorPrimaryContainer para que coincida exactamente con los botones circulares del dashboard
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, typedValue, true);
        int containerColor = typedValue.data;
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(containerColor);
        bg.setCornerRadius(dpToPx(20));

        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#20000000")),
                bg,
                null
        );
        card.setBackground(ripple);
        card.setElevation(dpToPx(1));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        // Usar colorPrimary para que coincida con los iconos del dashboard
        TypedValue iconColorValue = new TypedValue();
        getTheme().resolveAttribute(androidx.appcompat.R.attr.colorPrimary, iconColorValue, true);
        icon.setColorFilter(iconColorValue.data);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dpToPx(30), dpToPx(30));
        iconParams.setMargins(0, 0, 0, dpToPx(10));
        card.addView(icon, iconParams);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(14);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        
        // Usar colorOnPrimaryContainer para garantizar legibilidad sobre el fondo morado/vibrante
        TypedValue textColorValue = new TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, textColorValue, true);
        label.setTextColor(textColorValue.data);

        label.setGravity(Gravity.CENTER);
        card.addView(label);

        card.setOnClickListener(listener);

        android.widget.GridLayout.LayoutParams params = new android.widget.GridLayout.LayoutParams();
        params.width = 0;
        params.height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
        params.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        card.setLayoutParams(params);

        return card;
    }

    private void showFriendsDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16));

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.setMargins(0, 0, 0, dpToPx(8));

        MaterialButton btnAddFriend = new MaterialButton(this);
        btnAddFriend.setText("Agregar amigo con QR");
        btnAddFriend.setIconResource(R.drawable.ic_plus);
        btnAddFriend.setLayoutParams(buttonParams);
        content.addView(btnAddFriend);

        MaterialButton btnSendByQr = new MaterialButton(this);
        btnSendByQr.setText("Enviar escaneando QR");
        btnSendByQr.setIconResource(R.drawable.ic_qrcode_mdi);
        btnSendByQr.setLayoutParams(buttonParams);
        content.addView(btnSendByQr);

        TextView loadingView = new TextView(this);
        loadingView.setText("Cargando amigos...");
        loadingView.setGravity(Gravity.CENTER_HORIZONTAL);
        loadingView.setPadding(0, dpToPx(24), 0, dpToPx(24));
        content.addView(loadingView);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Mis Amigos")
                .setView(scrollView)
                .setNegativeButton("Cerrar", null)
                .create();

        btnAddFriend.setOnClickListener(v -> {
            dialog.dismiss();
            showAddFriendDialog(null);
        });
        btnSendByQr.setOnClickListener(v -> {
            dialog.dismiss();
            showSendDialog();
        });

        AuthManager.getInstance().getContacts(new AuthManager.ContactsCallback() {
            @Override
            public void onContactsLoaded(List<AuthManager.Contact> contacts) {
                content.removeView(loadingView);
                if (contacts.isEmpty()) {
                    TextView emptyView = new TextView(MainActivity.this);
                    emptyView.setText("Aún no tienes amigos guardados.");
                    emptyView.setGravity(Gravity.CENTER_HORIZONTAL);
                    emptyView.setPadding(0, dpToPx(24), 0, dpToPx(24));
                    emptyView.setTextColor(Color.parseColor("#64748B"));
                    content.addView(emptyView);
                    return;
                }

                // Ordenar alfabéticamente por nombre
                java.util.Collections.sort(contacts, (c1, c2) -> {
                    String n1 = c1.getName() != null ? c1.getName() : "";
                    String n2 = c2.getName() != null ? c2.getName() : "";
                    return n1.compareToIgnoreCase(n2);
                });

                for (int i = 0; i < contacts.size(); i++) {
                    AuthManager.Contact contact = contacts.get(i);
                    content.addView(createFriendRow(contact, dialog));

                    if (i < contacts.size() - 1) {
                        View divider = new View(MainActivity.this);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
                        lp.setMargins(dpToPx(56), 0, 0, 0); // Skip icon space
                        divider.setLayoutParams(lp);
                        divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
                        content.addView(divider);
                    }
                }
            }

            @Override
            public void onError(String message) {
                loadingView.setText("Error cargando amigos: " + message);
            }
        });

        dialog.show();
    }

    private View createFriendRow(AuthManager.Contact contact, AlertDialog parentDialog) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpToPx(12), 0, dpToPx(12));

        String name = contact.getName() != null && !contact.getName().isEmpty() ? contact.getName() : "Sin nombre";
        String initial = name.substring(0, 1).toUpperCase(Locale.US);

        // Icono circular con inicial
        TextView iconView = new TextView(this);
        int iconSize = dpToPx(42);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.setMargins(0, 0, dpToPx(14), 0);
        iconView.setLayoutParams(iconParams);
        iconView.setGravity(Gravity.CENTER);
        iconView.setText(initial);
        iconView.setTextColor(Color.WHITE);
        iconView.setTextSize(18);
        iconView.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(getContactColor(name));
        iconView.setBackground(iconBg);
        row.addView(iconView);

        // Info: Nombre y Dirección
        LinearLayout infoLayout = new LinearLayout(this);
        infoLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLayout.setLayoutParams(infoParams);

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(16);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        nameView.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurface));
        infoLayout.addView(nameView);

        TextView addressView = new TextView(this);
        addressView.setText(contact.getPublicKey());
        addressView.setTextSize(12);
        addressView.setTextColor(getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        addressView.setSingleLine(true);
        addressView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        infoLayout.addView(addressView);

        row.addView(infoLayout);

        // Botón Enviar (más compacto)
        MaterialButton btnTransfer = new MaterialButton(this);
        btnTransfer.setText("Enviar");
        btnTransfer.setPadding(dpToPx(8), 0, dpToPx(8), 0);
        btnTransfer.setOnClickListener(v -> {
            parentDialog.dismiss();
            Intent intent = new Intent(this, TransferActivity.class);
            intent.putExtra("address", contact.getPublicKey());
            intent.putExtra("name", contact.getName());
            startActivity(intent);
        });

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(40)
        );
        btnParams.setMargins(dpToPx(8), 0, 0, 0);
        btnTransfer.setLayoutParams(btnParams);
        btnTransfer.setTextSize(12);

        row.addView(btnTransfer);

        return row;
    }

    private int getContactColor(String name) {
        int[] colors = {
                Color.parseColor("#EF5350"), Color.parseColor("#EC407A"), Color.parseColor("#AB47BC"),
                Color.parseColor("#7E57C2"), Color.parseColor("#5C6BC0"), Color.parseColor("#42A5F5"),
                Color.parseColor("#26A69A"), Color.parseColor("#66BB6A"), Color.parseColor("#FFA726"),
                Color.parseColor("#FF7043"), Color.parseColor("#8D6E63"), Color.parseColor("#78909C")
        };
        return colors[Math.abs(name.hashCode()) % colors.length];
    }

    private void showAddFriendDialog(String prefilledAddress) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), 0);

        EditText etName = new EditText(this);
        etName.setHint("Nombre del amigo");
        etName.setSingleLine(true);
        layout.addView(etName);

        EditText etAddress = new EditText(this);
        etAddress.setHint("Dirección del amigo");
        etAddress.setSingleLine(true);
        if (prefilledAddress != null && !prefilledAddress.isEmpty()) {
            etAddress.setText(prefilledAddress);
            etAddress.setSelection(prefilledAddress.length());
        }
        layout.addView(etAddress);

        FrameLayout scannerFrame = new FrameLayout(this);
        scannerFrame.setVisibility(prefilledAddress == null || prefilledAddress.isEmpty() ? View.VISIBLE : View.GONE);
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

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Agregar amigo")
                .setView(layout)
                .setPositiveButton("Guardar", null)
                .setNegativeButton("Cancelar", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = etName.getText().toString().trim();
                String address = etAddress.getText().toString().trim();
                if (name.isEmpty()) {
                    etName.setError("Escribe un nombre");
                    return;
                }
                if (address.isEmpty()) {
                    etAddress.setError("Escanea o escribe la dirección");
                    return;
                }
                if (!isValidAddress(address)) {
                    etAddress.setError("Dirección inválida");
                    return;
                }
                saveFriend(name, address, () -> {
                    dialog.dismiss();
                    showFriendsDialog();
                });
            });

            if (prefilledAddress == null || prefilledAddress.isEmpty()) {
                scannerFrame.post(() -> startEmbeddedQrScanner(etAddress, scannerView, scannerFrame));
            }
        });

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

    private void showSaveFriendDialog(String address) {
        EditText etName = new EditText(this);
        etName.setHint("Nombre del amigo");
        etName.setSingleLine(true);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Guardar amigo")
                .setMessage(address)
                .setView(etName)
                .setPositiveButton("Guardar", null)
                .setNegativeButton("Cancelar", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                etName.setError("Escribe un nombre");
                return;
            }
            saveFriend(name, address, dialog::dismiss);
        }));

        dialog.show();
    }

    private void saveFriend(String name, String address, Runnable onSaved) {
        AuthManager.getInstance().saveContact(
                contactKeyForAddress(address),
                name,
                address,
                () -> {
                    Toast.makeText(this, "Amigo guardado", Toast.LENGTH_SHORT).show();
                    if (onSaved != null) onSaved.run();
                },
                message -> Toast.makeText(this, "No se pudo guardar amigo: " + message, Toast.LENGTH_LONG).show()
        );
    }

    private String contactKeyForAddress(String address) {
        return address.replaceAll("[.#$\\[\\]/]", "_");
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

    private boolean isValidAddress(String addressStr) {
        if (addressStr == null || addressStr.isEmpty()) return false;
        try {
            Address.fromString(Constants.NETWORK_PARAMETERS, addressStr);
            return true;
        } catch (Exception e) {
            return false;
        }
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
                    String message = "respuesta RPC inválida";
                    if (body != null && body.getError() != null) {
                        message = body.getError().getMessage();
                    } else {
                        BitcoinRpcResponse<?> errorResponse = parseErrorResponse(response);
                        if (errorResponse != null && errorResponse.getError() != null) {
                            message = errorResponse.getError().getMessage();
                        }
                    }
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
                    String message = "respuesta RPC inválida";
                    if (body != null && body.getError() != null) {
                        message = body.getError().getMessage();
                    } else {
                        BitcoinRpcResponse<?> errorResponse = parseErrorResponse(response);
                        if (errorResponse != null && errorResponse.getError() != null) {
                            message = errorResponse.getError().getMessage();
                        }
                    }
                    Toast.makeText(MainActivity.this, "No se pudieron leer UTXOs: " + message, Toast.LENGTH_LONG).show();
                    return;
                }

                BitcoinScanTxOutSetResult result = body.getResult();
                List<WalletManager.WalletUtxo> walletUtxos = toSpendableWalletUtxos(result, walletAddresses, chainHeight);
                walletManager.setRpcUtxos(walletUtxos, chainHeight);
                long estimatedSpendableSats = walletManager.getEstimatedSpendableBalanceSats();
                if (walletUtxos.isEmpty() && estimatedSpendableSats <= 0) {
                    Toast.makeText(
                            MainActivity.this,
                            "El nodo ve 0 UTXOs gastables para esta wallet. Si acabas de minar, la recompensa debe madurar primero.",
                            Toast.LENGTH_LONG
                    ).show();
                    return;
                }

                boolean emptyWallet = shouldEmptyWallet(amountSats, estimatedSpendableSats);
                createSignAndBroadcastTransaction(address, amountSats, emptyWallet);
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Throwable t) {
                Toast.makeText(MainActivity.this, "Error RPC leyendo UTXOs: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean shouldEmptyWallet(long requestedSats, long estimatedSpendableSats) {
        return estimatedSpendableSats > 0 && requestedSats >= estimatedSpendableSats;
    }

    private void createSignAndBroadcastTransaction(String address, long amountSats, boolean emptyWallet) {
        if (walletManager.isEncrypted()) {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            String savedPassword = (user != null) ? walletManager.getSavedWalletPassword(user.getUid()) : null;

            if (savedPassword != null && com.example.viper_wallet.auth.BiometricHelper.isBiometricOrPinAvailable(this)) {
                com.example.viper_wallet.auth.BiometricHelper.showPrompt(this, new com.example.viper_wallet.auth.BiometricHelper.BiometricCallback() {
                    @Override
                    public void onAuthenticated() {
                        performTransaction(address, amountSats, emptyWallet, savedPassword);
                    }

                    @Override
                    public void onError(String error) {
                        Toast.makeText(MainActivity.this, "Autenticación biométrica fallida: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                requestWalletPassword(password -> {
                    performTransaction(address, amountSats, emptyWallet, password);
                });
            }
        } else {
            performTransaction(address, amountSats, emptyWallet, null);
        }
    }

    private void performTransaction(String address, long amountSats, boolean emptyWallet, String password) {
        walletManager.createTransactionAsync(address, amountSats, emptyWallet, password, new WalletManager.TransactionCallback() {
            @Override
            public void onSuccess(Transaction tx) {
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user != null && password != null) {
                    walletManager.saveWalletPasswordSecurely(user.getUid(), password);
                }
                try {
                    String txHex = toHex(tx.serialize());
                    BitcoinRpcClient.getInstance().sendRawTransaction(txHex, new Callback<BitcoinRpcResponse<String>>() {
                        @Override
                        public void onResponse(Call<BitcoinRpcResponse<String>> call, Response<BitcoinRpcResponse<String>> response) {
                            BitcoinRpcResponse<String> body = response.body();
                            if (response.isSuccessful() && body != null && body.getError() == null) {
                                String txId = body.getResult();
                                try {
                                    walletManager.commitBroadcastTransaction(tx);
                                } catch (Exception e) {
                                    Log.w(TAG, "No se pudo guardar tx pendiente localmente", e);
                                }

                                long actualSentSats = walletManager.getOutputAmountToAddress(tx, address);
                                if (actualSentSats <= 0) {
                                    actualSentSats = amountSats;
                                }
                                String message = emptyWallet
                                        ? "Transacción enviada. Comisión descontada del máximo."
                                        : "Transacción enviada: " + txId;
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();

                                AuthManager.getInstance().saveTransaction(new TransactionRecord(
                                    txId, "SEND", actualSentSats, address
                                ));

                                updateUI();
                                refreshBalanceFromRpc();
                                return;
                            }

                            String message = "respuesta RPC inválida";
                            if (body != null && body.getError() != null) {
                                message = body.getError().getMessage();
                            } else {
                                BitcoinRpcResponse<?> errorResponse = parseErrorResponse(response);
                                if (errorResponse != null && errorResponse.getError() != null) {
                                    message = errorResponse.getError().getMessage();
                                }
                            }
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
                    Toast.makeText(MainActivity.this, "Error al serializar: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(MainActivity.this, "Error al firmar transacción: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private interface PasswordCallback {
        void onPasswordEntered(String password);
    }

    private void requestWalletPassword(PasswordCallback callback) {
        final EditText etWalletPass = new EditText(this);
        etWalletPass.setHint(R.string.prompt_wallet_password);
        etWalletPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Contraseña de Billetera")
                .setMessage("Se requiere tu contraseña para autorizar esta acción.")
                .setView(etWalletPass)
                .setPositiveButton("Confirmar", (dialog, which) -> {
                    callback.onPasswordEntered(etWalletPass.getText().toString());
                })
                .setNegativeButton("Cancelar", null)
                .show();
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
                Log.w(TAG, "UTXO sin scriptPubKey, se omite: " + unspent.getTxid() + ":" + unspent.getVout());
                continue;
            }

            String ownerAddress = findOwnerAddress(unspent, walletAddresses);
            if (ownerAddress == null) {
                Log.w(TAG, "UTXO sin dirección dueña reconocida, se omite: " + unspent.getTxid() + ":" + unspent.getVout());
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
            if (wallet != null && binding != null) {
                // Priorizar el último balance conocido (RPC) para evitar que el balance SPV (que tarda en sincronizar)
                // lo resetee a cero visualmente al navegar entre pantallas.
                if (lastTotalBalanceSats > 0 || lastImmatureMiningSats > 0) {
                    displayBalance(new BalanceSummary(lastTotalBalanceSats, lastSpendableBalanceSats, lastImmatureMiningSats, lastMiningMaturityBlocks));
                } else {
                    displayBalance(wallet.getBalance().value);
                }

                binding.tvNetwork.setText(Constants.COIN_DISPLAY_NAME + " " + Constants.NETWORK_NAME);

                String receiveAddress = walletManager.getCurrentReceiveAddress();
                if (receiveAddress != null) {
                    binding.tvReceiveAddress.setText(receiveAddress);
                }
                loadDashboardTransactions();
            }
        } catch (IOException e) {
            handleWalletLoadFailure(e);
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
        if (walletManager.getWallet() == null) return;
        if (isBalanceRequestInFlight) {
            isBalanceRefreshQueued = true;
            return;
        }

        List<String> addresses = walletManager.getIssuedReceiveAddresses();
        if (addresses.isEmpty()) return;

        isBalanceRequestInFlight = true;
        BitcoinRpcClient.getInstance().scanTxOutSetForAddresses(addresses, new Callback<BitcoinRpcResponse<BitcoinScanTxOutSetResult>>() {
            @Override
            public void onResponse(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Response<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> response) {
                BitcoinRpcResponse<BitcoinScanTxOutSetResult> body = response.body();
                if (!response.isSuccessful() || body == null || body.getError() != null || body.getResult() == null) {
                    String message = "respuesta RPC inválida";
                    if (body != null && body.getError() != null) {
                        message = body.getError().getMessage();
                    } else {
                        BitcoinRpcResponse<?> errorResponse = parseErrorResponse(response);
                        if (errorResponse != null && errorResponse.getError() != null) {
                            message = errorResponse.getError().getMessage();
                        }
                    }
                    Log.w(TAG, "No se pudo actualizar balance: " + message);
                    finishBalanceRefresh();
                    return;
                }

                BitcoinScanTxOutSetResult result = body.getResult();
                if (result.isSuccess()) {
                    int chainHeight = result.getHeight();
                    if (chainHeight > 0) {
                        walletManager.setRpcUtxos(toSpendableWalletUtxos(result, addresses, chainHeight), chainHeight);
                    }
                    BalanceSummary summary = summarizeBalance(result);
                    displayBalance(summary);
                    saveIncomingTransactionsFromUtxos(result, addresses);
                }
                finishBalanceRefresh();
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Throwable t) {
                Log.w(TAG, "Error RPC actualizando balance", t);
                finishBalanceRefresh();
            }
        });
    }

    private void displayBalance(long sats) {
        displayBalance(new BalanceSummary(sats, sats, 0L, 0));
    }

    private void displayBalance(BalanceSummary summary) {
        long pendingOutgoingSats = walletManager.getPendingOutgoingSats();
        long estimatedSpendableSats = walletManager.getEstimatedSpendableBalanceSats();
        long displayedSpendableSats = pendingOutgoingSats > 0
                ? Math.max(0L, estimatedSpendableSats)
                : summary.spendableSats;

        lastTotalBalanceSats = summary.totalSats;
        lastSpendableBalanceSats = displayedSpendableSats;
        lastImmatureMiningSats = summary.immatureMiningSats;
        lastMiningMaturityBlocks = summary.nextMaturityBlocks;

        binding.tvBalance.setText(formatCoinAmount(displayedSpendableSats));
        if (summary.immatureMiningSats > 0) {
            binding.tvImmatureBalance.setVisibility(View.VISIBLE);
            String immatureText = "Inmaduro: " + formatCoinAmount(summary.immatureMiningSats);
            if (summary.nextMaturityBlocks > 0) {
                immatureText += " · madura en " + summary.nextMaturityBlocks + " bloques";
            }
            binding.tvImmatureBalance.setText(immatureText);
        } else {
            binding.tvImmatureBalance.setVisibility(View.GONE);
        }

        if (pendingOutgoingSats > 0) {
            StringBuilder balanceDetails = new StringBuilder("En transferencia: ")
                    .append(pendingOutgoingSats)
                    .append(" sats");
            binding.tvBalanceSats.setText(balanceDetails.toString());
            binding.tvBalanceSats.setVisibility(View.VISIBLE);
        } else {
            binding.tvBalanceSats.setText("");
            binding.tvBalanceSats.setVisibility(View.GONE);
        }
    }

    private void finishBalanceRefresh() {
        isBalanceRequestInFlight = false;
        if (isBalanceRefreshQueued) {
            isBalanceRefreshQueued = false;
            balanceHandler.post(this::refreshBalanceFromRpc);
        }
    }

    private BalanceSummary summarizeBalance(BitcoinScanTxOutSetResult result) {
        long totalSats = 0L;
        long immatureMiningSats = 0L;
        int nextMaturityBlocks = 0;

        List<BitcoinScanTxOutSetResult.Unspent> unspents = result.getUnspents();
        if (unspents != null) {
            for (BitcoinScanTxOutSetResult.Unspent unspent : unspents) {
                if (unspent == null) continue;
                long amountSats = unspent.getAmountSats();
                totalSats += amountSats;

                if (isImmatureCoinbase(unspent, result.getHeight())) {
                    immatureMiningSats += amountSats;
                    int remaining = remainingMaturityBlocks(unspent, result.getHeight());
                    if (remaining > 0 && (nextMaturityBlocks == 0 || remaining < nextMaturityBlocks)) {
                        nextMaturityBlocks = remaining;
                    }
                }
            }
        } else {
            totalSats = result.getTotalSats();
        }

        long spendableSats = Math.max(0L, totalSats - immatureMiningSats);
        return new BalanceSummary(totalSats, spendableSats, immatureMiningSats, nextMaturityBlocks);
    }

    private boolean isImmatureCoinbase(BitcoinScanTxOutSetResult.Unspent unspent, int chainHeight) {
        return unspent != null
                && unspent.isCoinbase()
                && chainHeight > 0
                && confirmationsForUnspent(unspent, chainHeight) < COINBASE_MATURITY_CONFIRMATIONS;
    }

    private int remainingMaturityBlocks(BitcoinScanTxOutSetResult.Unspent unspent, int chainHeight) {
        int confirmations = confirmationsForUnspent(unspent, chainHeight);
        return Math.max(0, COINBASE_MATURITY_CONFIRMATIONS - confirmations);
    }

    private int confirmationsForUnspent(BitcoinScanTxOutSetResult.Unspent unspent, int chainHeight) {
        if (unspent.getHeight() <= 0 || chainHeight <= 0) return 0;
        return Math.max(0, chainHeight - unspent.getHeight() + 1);
    }

    private String sendDialogBalanceText() {
        String text = "Disponible: " + formatCoinAmount(lastSpendableBalanceSats);
        long pendingOutgoingSats = walletManager.getPendingOutgoingSats();
        if (pendingOutgoingSats > 0) {
            text += "\nEn transferencia: " + formatCoinAmount(pendingOutgoingSats);
        }
        if (lastImmatureMiningSats > 0) {
            text += "\nMinería pendiente: " + formatCoinAmount(lastImmatureMiningSats);
            if (lastMiningMaturityBlocks > 0) {
                text += " · madura en " + lastMiningMaturityBlocks + " bloques";
            }
        }
        return text;
    }

    private static class BalanceSummary {
        private final long totalSats;
        private final long spendableSats;
        private final long immatureMiningSats;
        private final int nextMaturityBlocks;

        private BalanceSummary(
                long totalSats,
                long spendableSats,
                long immatureMiningSats,
                int nextMaturityBlocks
        ) {
            this.totalSats = totalSats;
            this.spendableSats = spendableSats;
            this.immatureMiningSats = immatureMiningSats;
            this.nextMaturityBlocks = nextMaturityBlocks;
        }
    }

    private void saveIncomingTransactionsFromUtxos(
            BitcoinScanTxOutSetResult result,
            List<String> walletAddresses
    ) {
        if (result.getUnspents() == null || walletAddresses == null || walletAddresses.isEmpty()) return;

        Map<String, IncomingTransactionSummary> incomingByTxId = new LinkedHashMap<>();
        for (BitcoinScanTxOutSetResult.Unspent unspent : result.getUnspents()) {
            if (unspent == null || unspent.isCoinbase() || unspent.getTxid() == null) continue;

            String ownerAddress = findOwnerAddress(unspent, walletAddresses);
            if (ownerAddress == null) continue;

            IncomingTransactionSummary summary = incomingByTxId.get(unspent.getTxid());
            if (summary == null) {
                summary = new IncomingTransactionSummary(ownerAddress);
                incomingByTxId.put(unspent.getTxid(), summary);
            }
            summary.amountSats += unspent.getAmountSats();
        }

        if (incomingByTxId.isEmpty()) return;

        AuthManager authManager = AuthManager.getInstance();
        for (Map.Entry<String, IncomingTransactionSummary> entry : incomingByTxId.entrySet()) {
            IncomingTransactionSummary summary = entry.getValue();
            authManager.saveTransactionIfAbsent(
                    new TransactionRecord(entry.getKey(), "RECEIVE", summary.amountSats, summary.address),
                    this::loadDashboardTransactions
            );
        }
    }

    private static class IncomingTransactionSummary {
        private final String address;
        private long amountSats;

        private IncomingTransactionSummary(String address) {
            this.address = address;
        }
    }

    private String formatCoinAmount(long sats) {
        BigDecimal coin = BigDecimal.valueOf(sats).divide(BigDecimal.valueOf(100_000_000L), 8, RoundingMode.HALF_UP);
        java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0.00######");
        return df.format(coin) + " " + Constants.COIN_TICKER;
    }

    private String satsToPlainCoin(long sats) {
        return BigDecimal.valueOf(sats)
                .movePointLeft(8)
                .setScale(8, RoundingMode.DOWN)
                .toPlainString();
    }

    private long parseCoinAmountToSats(String amount) {
        return new BigDecimal(amount)
                .movePointRight(8)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
    }

    private BitcoinRpcResponse<?> parseErrorResponse(Response<?> response) {
        try {
            if (response.errorBody() != null) {
                retrofit2.Retrofit retrofit = BitcoinRpcClient.getInstance().getRetrofit();
                retrofit2.Converter<okhttp3.ResponseBody, BitcoinRpcResponse> converter =
                        retrofit.responseBodyConverter(BitcoinRpcResponse.class, new java.lang.annotation.Annotation[0]);
                return converter.convert(response.errorBody());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing error body", e);
        }
        return null;
    }

    private void loadDashboardTransactions() {
        if (binding == null || binding.layoutDashboard.getVisibility() != View.VISIBLE) return;
        binding.progressBarDashboard.setVisibility(View.VISIBLE);
        AuthManager.getInstance().getTransactions(new AuthManager.TransactionsCallback() {
            @Override
            public void onTransactionsLoaded(List<TransactionRecord> transactions) {
                binding.progressBarDashboard.setVisibility(View.GONE);
                if (transactions.isEmpty()) {
                    binding.tvEmptyStateDashboard.setVisibility(View.VISIBLE);
                    binding.rvTransactionsDashboard.setVisibility(View.GONE);
                } else {
                    binding.tvEmptyStateDashboard.setVisibility(View.GONE);
                    binding.rvTransactionsDashboard.setVisibility(View.VISIBLE);
                    TransactionAdapter adapter = new TransactionAdapter(
                            transactions,
                            transaction -> TransactionDetailsDialog.show(MainActivity.this, transaction)
                    );
                    binding.rvTransactionsDashboard.setAdapter(adapter);
                }
            }

            @Override
            public void onError(String message) {
                binding.progressBarDashboard.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, "Error cargando actividad: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
