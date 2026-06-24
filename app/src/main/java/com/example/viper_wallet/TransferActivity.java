package com.example.viper_wallet;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.viper_wallet.adapters.ContactAdapter;
import com.example.viper_wallet.auth.AuthManager;
import com.example.viper_wallet.auth.BiometricHelper;
import com.example.viper_wallet.databinding.ActivityTransferBinding;
import com.example.viper_wallet.models.TransactionRecord;
import com.example.viper_wallet.network.rpc.BitcoinRpcClient;
import com.example.viper_wallet.network.rpc.BitcoinRpcResponse;
import com.example.viper_wallet.network.rpc.BitcoinScanTxOutSetResult;
import com.example.viper_wallet.walletcore.Constants;
import com.example.viper_wallet.walletcore.WalletManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.bitcoinj.base.Address;
import org.bitcoinj.core.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TransferActivity extends AppCompatActivity {
    private static final String TAG = "TransferActivity";
    private ActivityTransferBinding binding;
    private WalletManager walletManager;
    private ContactAdapter contactAdapter;
    private List<AuthManager.Contact> currentContacts = new ArrayList<>();
    private String selectedAddress;
    private String selectedName;
    private long spendableBalanceSats = 0;
    private EditText targetScanEditText;

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher = registerForActivityResult(new ScanContract(),
            result -> {
                if(result.getContents() == null) {
                    Toast.makeText(this, "Escaneo cancelado", Toast.LENGTH_LONG).show();
                } else {
                    String address = extractBitcoinAddress(result.getContents());
                    if (targetScanEditText != null) {
                        targetScanEditText.setText(address);
                    } else {
                        binding.etAddress.setText(address);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityTransferBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainTransfer, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        walletManager = WalletManager.getInstance(this);

        setupToolbar();
        setupRecyclerView();
        setupListeners();
        loadContacts();
        refreshBalance();
        checkIntent();
    }

    private void checkIntent() {
        if (getIntent() != null && getIntent().hasExtra("address")) {
            selectedAddress = getIntent().getStringExtra("address");
            selectedName = getIntent().getStringExtra("name");
            showStepAmount();
        }
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbar.setNavigationOnClickListener(v -> {
            if (binding.layoutStepAmount.getVisibility() == View.VISIBLE) {
                showStepRecipient();
            } else {
                finish();
            }
        });
    }

    private void setupRecyclerView() {
        binding.rvContacts.setLayoutManager(new LinearLayoutManager(this));
        contactAdapter = new ContactAdapter(contact -> {
            selectedAddress = contact.getPublicKey();
            selectedName = contact.getName();
            showStepAmount();
        });
        binding.rvContacts.setAdapter(contactAdapter);
    }

    private void setupListeners() {
        binding.tilAddress.setEndIconOnClickListener(v -> {
            targetScanEditText = binding.etAddress;
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Escanea una dirección de UESCoin");
            options.setCameraId(0);
            options.setBeepEnabled(false);
            options.setBarcodeImageEnabled(true);
            options.setOrientationLocked(false);
            barcodeLauncher.launch(options);
        });

        binding.btnPaste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard.hasPrimaryClip()) {
                String text = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                binding.etAddress.setText(text);
            }
        });

        binding.btnContinue.setOnClickListener(v -> {
            if (binding.layoutStepRecipient.getVisibility() == View.VISIBLE) {
                String address = binding.etAddress.getText().toString().trim();
                if (isValidAddress(address)) {
                    selectedAddress = address;
                    selectedName = null;
                    showStepAmount();
                } else {
                    binding.tilAddress.setError("Dirección inválida");
                }
            } else {
                processTransaction();
            }
        });

        binding.btnUseMax.setOnClickListener(v -> {
            binding.etAmount.setText(satsToPlainCoin(spendableBalanceSats));
        });

        binding.btnAddFriend.setOnClickListener(v -> showAddFriendDialog());
    }

    private void showAddFriendDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(64, 32, 64, 0);

        TextInputLayout tilName = new TextInputLayout(this);
        tilName.setHint("Nombre");
        tilName.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText etName = new TextInputEditText(this);
        tilName.addView(etName);
        layout.addView(tilName);

        View spacing = new View(this);
        spacing.setLayoutParams(new LinearLayout.LayoutParams(1, 16));
        layout.addView(spacing);

        TextInputLayout tilAddress = new TextInputLayout(this);
        tilAddress.setHint("Dirección");
        tilAddress.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        tilAddress.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        tilAddress.setEndIconDrawable(R.drawable.ic_qrcode_mdi);
        
        TextInputEditText etAddress = new TextInputEditText(this);
        tilAddress.addView(etAddress);
        layout.addView(tilAddress);

        tilAddress.setEndIconOnClickListener(v -> {
            targetScanEditText = etAddress;
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("Escanea la dirección de tu amigo");
            barcodeLauncher.launch(options);
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle("Agregar Amigo")
                .setView(layout)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    String address = etAddress.getText().toString().trim();
                    if (!name.isEmpty() && isValidAddress(address)) {
                        AuthManager.getInstance().saveContact(address.replaceAll("[.#$\\[\\]/]", "_"), name, address, () -> {
                            loadContacts();
                            Toast.makeText(this, "Amigo guardado", Toast.LENGTH_SHORT).show();
                        }, msg -> Toast.makeText(this, "Error: " + msg, Toast.LENGTH_SHORT).show());
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void loadContacts() {
        AuthManager.getInstance().getContacts(new AuthManager.ContactsCallback() {
            @Override
            public void onContactsLoaded(List<AuthManager.Contact> contacts) {
                currentContacts = contacts;
                if (contacts.isEmpty()) {
                    binding.rvContacts.setVisibility(View.GONE);
                    binding.tvEmptyContacts.setVisibility(View.VISIBLE);
                } else {
                    binding.rvContacts.setVisibility(View.VISIBLE);
                    binding.tvEmptyContacts.setVisibility(View.GONE);
                    contactAdapter.setContacts(contacts);
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(TransferActivity.this, "Error al cargar contactos: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showStepRecipient() {
        binding.layoutStepRecipient.setVisibility(View.VISIBLE);
        binding.layoutStepAmount.setVisibility(View.GONE);
        binding.btnContinue.setText("Continuar");
        binding.toolbar.setTitle("Enviar UESCoin");
    }

    private void showStepAmount() {
        binding.layoutStepRecipient.setVisibility(View.GONE);
        binding.layoutStepAmount.setVisibility(View.VISIBLE);
        binding.btnContinue.setText("Enviar");
        binding.toolbar.setTitle("Monto a enviar");
        
        binding.tvSelectedRecipient.setText(selectedName != null ? selectedName + " (" + selectedAddress + ")" : selectedAddress);
    }

    private void refreshBalance() {
        List<String> addresses = walletManager.getIssuedReceiveAddresses();
        if (addresses.isEmpty()) return;

        BitcoinRpcClient.getInstance().scanTxOutSetForAddresses(addresses, new Callback<BitcoinRpcResponse<BitcoinScanTxOutSetResult>>() {
            @Override
            public void onResponse(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Response<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().getResult() != null) {
                    BitcoinScanTxOutSetResult result = response.body().getResult();
                    spendableBalanceSats = result.getTotalSats();
                    binding.tvAvailableBalance.setText("Disponible: " + formatCoinAmount(spendableBalanceSats));
                }
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Throwable t) {
                Log.e(TAG, "Error al refrescar balance", t);
            }
        });
    }

    private void processTransaction() {
        String amountStr = binding.etAmount.getText().toString().trim();
        if (amountStr.isEmpty()) {
            binding.tilAmount.setError("Ingresa un monto");
            return;
        }

        try {
            long amountSats = parseCoinAmountToSats(amountStr);
            if (amountSats <= 0) {
                binding.tilAmount.setError("El monto debe ser mayor a 0");
                return;
            }
            if (amountSats > spendableBalanceSats) {
                binding.tilAmount.setError("Saldo insuficiente");
                return;
            }

            confirmAndSend(selectedAddress, amountSats);
        } catch (Exception e) {
            binding.tilAmount.setError("Monto inválido");
        }
    }

    private void confirmAndSend(String address, long amountSats) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Confirmar envío")
                .setMessage("¿Estás seguro de enviar " + formatCoinAmount(amountSats) + " a " + (selectedName != null ? selectedName : address) + "?")
                .setPositiveButton("Enviar", (dialog, which) -> sendTransaction(address, amountSats))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void sendTransaction(String address, long amountSats) {
        List<String> walletAddresses = walletManager.getIssuedReceiveAddresses();
        BitcoinRpcClient.getInstance().getBlockchainInfo(new Callback<BitcoinRpcResponse<Object>>() {
            @Override
            public void onResponse(Call<BitcoinRpcResponse<Object>> call, Response<BitcoinRpcResponse<Object>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    Integer height = extractBlockHeight(response.body().getResult());
                    if (height != null) {
                        scanAndPerformSend(address, amountSats, walletAddresses, height);
                    }
                }
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<Object>> call, Throwable t) {
                Toast.makeText(TransferActivity.this, "Error de red", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void scanAndPerformSend(String address, long amountSats, List<String> walletAddresses, int height) {
        BitcoinRpcClient.getInstance().scanTxOutSetForAddresses(walletAddresses, new Callback<BitcoinRpcResponse<BitcoinScanTxOutSetResult>>() {
            @Override
            public void onResponse(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Response<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> response) {
                if (response.isSuccessful() && response.body() != null && response.body().getResult() != null) {
                    BitcoinScanTxOutSetResult result = response.body().getResult();
                    List<WalletManager.WalletUtxo> utxos = toSpendableWalletUtxos(result, walletAddresses, height);
                    walletManager.setRpcUtxos(utxos, height);

                    long spendableSats = 0;
                    for (WalletManager.WalletUtxo u : utxos) {
                        spendableSats += u.amountSats;
                    }
                    long pendingOutgoing = walletManager.getPendingOutgoingSats();
                    long estimatedSpendable = walletManager.getEstimatedSpendableBalanceSats();
                    long realAvailable = Math.max(Math.max(0L, spendableSats - pendingOutgoing), estimatedSpendable);

                    boolean emptyWallet = amountSats >= realAvailable;
                    authenticateAndExecute(address, amountSats, emptyWallet);
                }
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> call, Throwable t) {}
        });
    }

    private interface LocalPasswordCallback {
        void onPasswordEntered(String password);
    }

    private void authenticateAndExecute(String address, long amountSats, boolean emptyWallet) {
        if (walletManager.isEncrypted()) {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            String savedPassword = (user != null) ? walletManager.getSavedWalletPassword(user.getUid()) : null;

            if (savedPassword != null && BiometricHelper.isBiometricOrPinAvailable(this)) {
                BiometricHelper.showPrompt(this, new BiometricHelper.BiometricCallback() {
                    @Override
                    public void onAuthenticated() {
                        performTransaction(address, amountSats, emptyWallet, savedPassword);
                    }

                    @Override
                    public void onError(String error) {
                        Toast.makeText(TransferActivity.this, "Error biométrico", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                requestWalletPassword(password -> performTransaction(address, amountSats, emptyWallet, password));
            }
        } else {
            performTransaction(address, amountSats, emptyWallet, null);
        }
    }

    private void performTransaction(String address, long amountSats, boolean emptyWallet, String password) {
        walletManager.createTransactionAsync(address, amountSats, emptyWallet, password, new WalletManager.TransactionCallback() {
            @Override
            public void onSuccess(Transaction tx) {
                try {
                    String txHex = toHex(tx.serialize());
                    BitcoinRpcClient.getInstance().sendRawTransaction(txHex, new Callback<BitcoinRpcResponse<String>>() {
                        @Override
                        public void onResponse(Call<BitcoinRpcResponse<String>> call, Response<BitcoinRpcResponse<String>> response) {
                            if (response.isSuccessful() && response.body() != null && response.body().getError() == null) {
                                String txId = response.body().getResult();
                                try {
                                    walletManager.commitBroadcastTransaction(tx);
                                } catch (Exception ignored) {}
                                
                                AuthManager.getInstance().saveTransaction(new TransactionRecord(txId, "SEND", amountSats, address));
                                Toast.makeText(TransferActivity.this, "Transacción enviada!", Toast.LENGTH_LONG).show();
                                checkAndPromptAddFriend(address);
                            } else {
                                Toast.makeText(TransferActivity.this, "Error RPC", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<BitcoinRpcResponse<String>> call, Throwable t) {}
                    });
                } catch (Exception e) {
                    Toast.makeText(TransferActivity.this, "Error de serialización", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Exception e) {
                Toast.makeText(TransferActivity.this, "Error al firmar", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void requestWalletPassword(LocalPasswordCallback callback) {
        final EditText etWalletPass = new EditText(this);
        etWalletPass.setHint("Contraseña");
        etWalletPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Contraseña")
                .setView(etWalletPass)
                .setPositiveButton("Confirmar", (dialog, which) -> callback.onPasswordEntered(etWalletPass.getText().toString()))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void checkAndPromptAddFriend(String address) {
        boolean exists = false;
        for (AuthManager.Contact contact : currentContacts) {
            if (contact.getPublicKey().equalsIgnoreCase(address)) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("¿Añadir a amigos?")
                    .setMessage("Esta dirección no está en tu lista de amigos. ¿Quieres guardarla?")
                    .setPositiveButton("Sí, añadir", (dialog, which) -> {
                        showAddFriendDialogWithAddress(address);
                    })
                    .setNegativeButton("No", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
        } else {
            finish();
        }
    }

    private void showAddFriendDialogWithAddress(String address) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(64, 32, 64, 0);

        TextInputLayout tilName = new TextInputLayout(this);
        tilName.setHint("Nombre");
        tilName.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText etName = new TextInputEditText(this);
        tilName.addView(etName);
        layout.addView(tilName);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Agregar Amigo")
                .setView(layout)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String name = etName.getText().toString().trim();
                    if (!name.isEmpty()) {
                        AuthManager.getInstance().saveContact(address.replaceAll("[.#$\\[\\]/]", "_"), name, address, () -> {
                            Toast.makeText(this, "Amigo guardado", Toast.LENGTH_SHORT).show();
                            finish();
                        }, msg -> {
                            Toast.makeText(this, "Error: " + msg, Toast.LENGTH_SHORT).show();
                            finish();
                        });
                    } else {
                        finish();
                    }
                })
                .setNegativeButton("Cancelar", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
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

    private String extractBitcoinAddress(String scannedValue) {
        String value = scannedValue.trim();
        if (value.toLowerCase(Locale.US).startsWith("bitcoin:")) {
            String addressWithQuery = value.substring(8);
            int queryIndex = addressWithQuery.indexOf('?');
            return queryIndex >= 0 ? addressWithQuery.substring(0, queryIndex) : addressWithQuery;
        }
        return value;
    }

    private String formatCoinAmount(long sats) {
        return com.example.viper_wallet.walletcore.CurrencyUtils.formatCoinAmount(sats);
    }

    private long parseCoinAmountToSats(String amount) {
        return com.example.viper_wallet.walletcore.CurrencyUtils.parseCoinAmountToSats(amount);
    }

    private String satsToPlainCoin(long sats) {
        return com.example.viper_wallet.walletcore.CurrencyUtils.satsToPlainCoin(sats);
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

    private Integer extractBlockHeight(Object blockchainInfoResult) {
        if (!(blockchainInfoResult instanceof Map)) return null;
        Object blocks = ((Map<String, Object>) blockchainInfoResult).get("blocks");
        if (blocks instanceof Number) return ((Number) blocks).intValue();
        return null;
    }

    private List<WalletManager.WalletUtxo> toSpendableWalletUtxos(BitcoinScanTxOutSetResult result, List<String> walletAddresses, int chainHeight) {
        List<WalletManager.WalletUtxo> walletUtxos = new ArrayList<>();
        if (result.getUnspents() == null) return walletUtxos;
        for (BitcoinScanTxOutSetResult.Unspent unspent : result.getUnspents()) {
            if (unspent.isCoinbase() && (chainHeight - unspent.getHeight() + 1) < 100) continue;
            String ownerAddress = null;
            for (String addr : walletAddresses) {
                if (unspent.getDescriptor().contains(addr)) { ownerAddress = addr; break; }
            }
            if (ownerAddress != null) {
                walletUtxos.add(new WalletManager.WalletUtxo(unspent.getTxid(), unspent.getVout(), unspent.getAmountSats(), unspent.getHeight(), unspent.isCoinbase(), unspent.getScriptPubKey(), ownerAddress));
            }
        }
        return walletUtxos;
    }
}
