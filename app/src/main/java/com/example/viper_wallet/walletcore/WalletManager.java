package com.example.viper_wallet.walletcore;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Network;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.core.UTXOProvider;
import org.bitcoinj.core.UTXOProviderException;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WalletManager {
    private static final String TAG = "WalletManager";
    private static WalletManager instance;
    private Wallet wallet;
    private PeerGroup peerGroup;
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService walletExecutor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context context;

    private WalletManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized WalletManager getInstance(Context context) {
        if (instance == null) {
            instance = new WalletManager(context);
        }
        return instance;
    }

    private String getWalletFileName() {
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            return "wallet_" + user.getUid() + ".protobuf";
        }
        return "wallet_default.protobuf";
    }

    private String getServerWalletPrefKey() {
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            return Constants.KEY_SERVER_WALLET_NAME + "_" + user.getUid();
        }
        return Constants.KEY_SERVER_WALLET_NAME + "_default";
    }

    public void reset() {
        if (peerGroup != null) {
            final PeerGroup tempGroup = peerGroup;
            syncExecutor.execute(() -> {
                try {
                    tempGroup.stop();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping peerGroup during reset", e);
                }
            });
            peerGroup = null;
        }
        wallet = null;
    }

    public boolean hasWallet() {
        File walletFile = new File(context.getFilesDir(), getWalletFileName());
        return walletFile.exists();
    }

    public Wallet createWallet(String password) throws IOException {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(getServerWalletPrefKey())
                .apply();
        wallet = Wallet.createDeterministic(Constants.NETWORK_PARAMETERS, ScriptType.P2WPKH);
        
        if (password != null && !password.isEmpty()) {
            KeyCrypterScrypt keyCrypter = new KeyCrypterScrypt();
            AesKey aesKey = keyCrypter.deriveKey(password);
            wallet.encrypt(keyCrypter, aesKey);
        }
        
        saveWallet();
        setHasWalletFlag(true);
        return wallet;
    }

    public void restoreWalletFromMnemonic(String mnemonic, String password) throws Exception {
        DeterministicSeed seed = new DeterministicSeed(mnemonic, null, "", 0);
        wallet = Wallet.fromSeed(Constants.NETWORK_PARAMETERS, seed, ScriptType.P2WPKH);
        
        if (password != null && !password.isEmpty()) {
            KeyCrypterScrypt keyCrypter = new KeyCrypterScrypt();
            AesKey aesKey = keyCrypter.deriveKey(password);
            wallet.encrypt(keyCrypter, aesKey);
        }
        
        saveWallet();
        setHasWalletFlag(true);
    }

    public void saveWallet() throws IOException {
        if (wallet != null) {
            File walletFile = new File(context.getFilesDir(), getWalletFileName());
            wallet.saveToFile(walletFile);
        }
    }

    public Wallet loadWallet() throws IOException {
        File walletFile = new File(context.getFilesDir(), getWalletFileName());
        if (!walletFile.exists()) return null;
        try {
            wallet = Wallet.loadFromFile(walletFile);
            return wallet;
        } catch (Exception e) {
            Log.e(TAG, "Error loading wallet", e);
            throw new IOException("Failed to load wallet", e);
        }
    }

    public void startSyncAsync() {
        if (wallet == null || peerGroup != null) return;
        syncExecutor.execute(this::startSync);
    }

    private void startSync() {
        if (wallet == null || peerGroup != null) return;
        try {
            BlockStore blockStore = new MemoryBlockStore(Constants.NETWORK_PARAMETERS.getGenesisBlock());
            BlockChain chain = new BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore);
            peerGroup = new PeerGroup(Constants.NETWORK_PARAMETERS, chain);
            
            peerGroup.addAddress(PeerAddress.simple(
                    InetAddress.getByName(Constants.NODE_HOST),
                    Constants.NODE_P2P_PORT
            ));

            peerGroup.addWallet(wallet);
            peerGroup.startAsync();
            peerGroup.downloadBlockChain();
            
            wallet.addCoinsReceivedEventListener((w, tx, prevBalance, newBalance) -> {
                try { saveWallet(); } catch (IOException e) { e.printStackTrace(); }
            });
            wallet.addCoinsSentEventListener((w, tx, prevBalance, newBalance) -> {
                try { saveWallet(); } catch (IOException e) { e.printStackTrace(); }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error starting sync", e);
        }
    }

    public Transaction createTransaction(String recipientAddress, long amountSats, String password) throws Exception {
        Address address = Address.fromString(Constants.NETWORK_PARAMETERS, recipientAddress);
        SendRequest request = SendRequest.to(address, Coin.valueOf(amountSats));
        return createTransaction(request, password);
    }

    public Transaction createEmptyWalletTransaction(String recipientAddress, String password) throws Exception {
        Address address = Address.fromString(Constants.NETWORK_PARAMETERS, recipientAddress);
        SendRequest request = SendRequest.emptyWallet(address);
        return createTransaction(request, password);
    }

    private Transaction createTransaction(SendRequest request, String password) throws Exception {
        request.allowUnconfirmed();
        
        if (wallet.isEncrypted()) {
            if (password == null || password.isEmpty()) {
                throw new Exception("Wallet is encrypted, password required");
            }
            KeyCrypter crypter = wallet.getKeyCrypter();
            if (crypter == null) throw new Exception("Wallet has no key crypter");
            request.aesKey = crypter.deriveKey(password);
        }
        
        wallet.completeTx(request);
        saveWallet();
        return request.tx;
    }

    public void commitBroadcastTransaction(Transaction tx) throws IOException, VerificationException {
        if (wallet == null || tx == null) return;
        wallet.commitTx(tx);
        saveWallet();
    }

    public long getEstimatedSpendableBalanceSats() {
        if (wallet == null) return 0L;
        return wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE).value;
    }

    public long getPendingOutgoingSats() {
        if (wallet == null) return 0L;

        long pendingOutgoing = 0L;
        for (Transaction tx : wallet.getPendingTransactions()) {
            try {
                long sent = tx.getValueSentFromMe(wallet).value;
                long received = tx.getValueSentToMe(wallet).value;
                long netOutgoing = sent - received;
                if (netOutgoing > 0) {
                    pendingOutgoing += netOutgoing;
                }
            } catch (Exception e) {
                Log.w(TAG, "No se pudo calcular tx pendiente: " + tx.getTxId(), e);
            }
        }
        return pendingOutgoing;
    }

    public long getOutputAmountToAddress(Transaction tx, String recipientAddress) {
        if (tx == null || recipientAddress == null || recipientAddress.isEmpty()) return 0L;

        Address destination = Address.fromString(Constants.NETWORK_PARAMETERS, recipientAddress);
        long amount = 0L;
        for (org.bitcoinj.core.TransactionOutput output : tx.getOutputs()) {
            try {
                Address outputAddress = output.getScriptPubKey().getToAddress(Constants.NETWORK_PARAMETERS);
                if (destination.equals(outputAddress)) {
                    amount += output.getValue().value;
                }
            } catch (Exception ignored) {
                // Non-address outputs are not destination payments for this flow.
            }
        }
        return amount;
    }

    public void setRpcUtxos(List<WalletUtxo> unspents, int chainHeadHeight) {
        if (wallet == null) return;
        wallet.setUTXOProvider(new RpcUtxoProvider(unspents, chainHeadHeight));
    }

    public boolean broadcastTransaction(Transaction tx) {
        if (peerGroup != null) {
            peerGroup.broadcastTransaction(tx);
            return true;
        }
        return false;
    }

    public String getCurrentReceiveAddress() {
        if (wallet == null) return null;
        return wallet.currentReceiveAddress().toString();
    }

    public String getFreshReceiveAddress() throws IOException {
        if (wallet == null) return null;
        String address = wallet.freshReceiveAddress().toString();
        saveWallet();
        return address;
    }

    public List<String> getIssuedReceiveAddresses() {
        Set<String> addresses = new LinkedHashSet<>();
        if (wallet == null) return new ArrayList<>(addresses);

        for (Address address : wallet.getIssuedReceiveAddresses()) {
            addresses.add(address.toString());
        }

        Address currentAddress = wallet.currentReceiveAddress();
        if (currentAddress != null) {
            addresses.add(currentAddress.toString());
        }

        Address currentChangeAddress = wallet.currentChangeAddress();
        if (currentChangeAddress != null) {
            addresses.add(currentChangeAddress.toString());
        }

        return new ArrayList<>(addresses);
    }

    public String getServerWalletName() {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String savedName = prefs.getString(getServerWalletPrefKey(), null);
        if (savedName != null && !savedName.isEmpty()) {
            return savedName;
        }

        String address = getCurrentReceiveAddress();
        if (address == null || address.isEmpty()) {
            return Constants.SERVER_WALLET_PREFIX + "default";
        }

        String safeAddress = address.replaceAll("[^a-zA-Z0-9_-]", "");
        String walletName = Constants.SERVER_WALLET_PREFIX
                + safeAddress.substring(0, Math.min(safeAddress.length(), 24));
        prefs.edit().putString(getServerWalletPrefKey(), walletName).apply();
        return walletName;
    }

    public String getMnemonic(String password) throws Exception {
        if (wallet == null) return null;
        DeterministicSeed seed = wallet.getKeyChainSeed();
        if (seed == null) return null;
        
        if (seed.isEncrypted()) {
            if (password == null || password.isEmpty()) {
                throw new Exception("Seed is encrypted, password required");
            }
            KeyCrypter crypter = wallet.getKeyCrypter();
            if (crypter == null) throw new Exception("Wallet has no key crypter");
            seed = seed.decrypt(crypter, "", crypter.deriveKey(password));
        }
        
        List<String> mnemonicCode = seed.getMnemonicCode();
        return (mnemonicCode != null) ? String.join(" ", mnemonicCode) : null;
    }

    public boolean isEncrypted() {
        return wallet != null && wallet.isEncrypted();
    }

    private void setHasWalletFlag(boolean hasWallet) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(Constants.KEY_HAS_WALLET, hasWallet).apply();
    }

    public Wallet getWallet() {
        return wallet;
    }

    public static class WalletUtxo {
        private final String txid;
        private final long vout;
        private final long amountSats;
        private final int height;
        private final boolean coinbase;
        private final String scriptPubKey;
        private final String address;

        public WalletUtxo(
                String txid,
                long vout,
                long amountSats,
                int height,
                boolean coinbase,
                String scriptPubKey,
                String address
        ) {
            this.txid = txid;
            this.vout = vout;
            this.amountSats = amountSats;
            this.height = height;
            this.coinbase = coinbase;
            this.scriptPubKey = scriptPubKey;
            this.address = address;
        }
    }

    private static class RpcUtxoProvider implements UTXOProvider {
        private final List<WalletUtxo> unspents;
        private final int chainHeadHeight;

        private RpcUtxoProvider(List<WalletUtxo> unspents, int chainHeadHeight) {
            this.unspents = new ArrayList<>(unspents);
            this.chainHeadHeight = chainHeadHeight;
        }

        @Override
        public List<UTXO> getOpenTransactionOutputs(List<ECKey> keys) throws org.bitcoinj.core.UTXOProviderException {
            List<UTXO> utxos = new ArrayList<>();
            for (WalletUtxo unspent : unspents) {
                try {
                    utxos.add(new UTXO(
                            Sha256Hash.wrap(unspent.txid),
                            unspent.vout,
                            Coin.valueOf(unspent.amountSats),
                            unspent.height,
                            unspent.coinbase,
                            Script.parse(hexToBytes(unspent.scriptPubKey)),
                            unspent.address
                    ));
                } catch (Exception e) {
                    throw new org.bitcoinj.core.UTXOProviderException(e);
                }
            }
            return utxos;
        }

        @Override
        public int getChainHeadHeight() {
            return chainHeadHeight;
        }

        @Override
        public Network network() {
            return Constants.NETWORK_PARAMETERS.network();
        }

        private static byte[] hexToBytes(String hex) {
            int length = hex.length();
            byte[] bytes = new byte[length / 2];
            for (int i = 0; i < length; i += 2) {
                bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                        + Character.digit(hex.charAt(i + 1), 16));
            }
            return bytes;
        }
    }

    // --- Asynchronous Cryptographic Helpers ---

    public interface WalletCallback {
        void onSuccess(Wallet wallet);
        void onError(Exception e);
    }

    public interface SimpleCallback {
        void onSuccess();
        void onError(Exception e);
    }

    public interface MnemonicCallback {
        void onSuccess(String mnemonic);
        void onError(Exception e);
    }

    public interface TransactionCallback {
        void onSuccess(Transaction tx);
        void onError(Exception e);
    }

    public void createWalletAsync(String password, WalletCallback callback) {
        walletExecutor.execute(() -> {
            try {
                Wallet result = createWallet(password);
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void restoreWalletFromMnemonicAsync(String mnemonic, String password, SimpleCallback callback) {
        walletExecutor.execute(() -> {
            try {
                restoreWalletFromMnemonic(mnemonic, password);
                mainHandler.post(callback::onSuccess);
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void getMnemonicAsync(String password, MnemonicCallback callback) {
        walletExecutor.execute(() -> {
            try {
                String mnemonic = getMnemonic(password);
                mainHandler.post(() -> callback.onSuccess(mnemonic));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void createTransactionAsync(String recipientAddress, long amountSats, String password, TransactionCallback callback) {
        createTransactionAsync(recipientAddress, amountSats, false, password, callback);
    }

    public void createTransactionAsync(
            String recipientAddress,
            long amountSats,
            boolean emptyWallet,
            String password,
            TransactionCallback callback
    ) {
        walletExecutor.execute(() -> {
            try {
                Transaction tx = emptyWallet
                        ? createEmptyWalletTransaction(recipientAddress, password)
                        : createTransaction(recipientAddress, amountSats, password);
                mainHandler.post(() -> callback.onSuccess(tx));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    public void saveWalletPasswordSecurely(String uid, String password) {
        if (uid == null || password == null) return;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences sharedPreferences = EncryptedSharedPreferences.create(
                    "secure_wallet_prefs_" + uid,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            sharedPreferences.edit().putString("wallet_password", password).apply();
            Log.d(TAG, "Contraseña guardada de forma segura para el usuario: " + uid);
        } catch (Exception e) {
            Log.e(TAG, "Error al guardar la contraseña de forma segura", e);
        }
    }

    public String getSavedWalletPassword(String uid) {
        if (uid == null) return null;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences sharedPreferences = EncryptedSharedPreferences.create(
                    "secure_wallet_prefs_" + uid,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return sharedPreferences.getString("wallet_password", null);
        } catch (Exception e) {
            Log.e(TAG, "Error al recuperar la contraseña segura", e);
            return null;
        }
    }

    public void clearSavedWalletPassword(String uid) {
        if (uid == null) return;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            SharedPreferences sharedPreferences = EncryptedSharedPreferences.create(
                    "secure_wallet_prefs_" + uid,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            sharedPreferences.edit().remove("wallet_password").apply();
            Log.d(TAG, "Contraseña segura eliminada para el usuario: " + uid);
        } catch (Exception e) {
            Log.e(TAG, "Error al eliminar la contraseña segura", e);
        }
    }
}
