package com.example.viper_wallet.walletcore;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

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
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;

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

    public boolean hasWallet() {
        File walletFile = new File(context.getFilesDir(), Constants.WALLET_FILENAME);
        return walletFile.exists();
    }

    public Wallet createWallet() throws IOException {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(Constants.KEY_SERVER_WALLET_NAME)
                .apply();
        wallet = Wallet.createDeterministic(Constants.NETWORK_PARAMETERS, ScriptType.P2WPKH);
        saveWallet();
        setHasWalletFlag(true);
        return wallet;
    }

    public void saveWallet() throws IOException {
        if (wallet != null) {
            File walletFile = new File(context.getFilesDir(), Constants.WALLET_FILENAME);
            wallet.saveToFile(walletFile);
        }
    }

    public Wallet loadWallet() throws IOException {
        File walletFile = new File(context.getFilesDir(), Constants.WALLET_FILENAME);
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
            // Fix for bitcoinj 0.17.1: MemoryBlockStore requires the genesis block
            BlockStore blockStore = new MemoryBlockStore(Constants.NETWORK_PARAMETERS.getGenesisBlock());
            BlockChain chain = new BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore);
            peerGroup = new PeerGroup(Constants.NETWORK_PARAMETERS, chain);
            
            // Port 18444 is the RegTest P2P port. RPC uses 18443 and is not used by PeerGroup.
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

    public Transaction createTransaction(String recipientAddress, long amountSats) throws Exception {
        Address address = Address.fromString(Constants.NETWORK_PARAMETERS, recipientAddress);
        SendRequest request = SendRequest.to(address, Coin.valueOf(amountSats));
        wallet.completeTx(request);
        saveWallet();
        return request.tx;
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
        String savedName = prefs.getString(Constants.KEY_SERVER_WALLET_NAME, null);
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
        prefs.edit().putString(Constants.KEY_SERVER_WALLET_NAME, walletName).apply();
        return walletName;
    }

    public String getMnemonic() {
        if (wallet == null) return null;
        DeterministicSeed seed = wallet.getKeyChainSeed();
        return (seed != null) ? String.join(" ", seed.getMnemonicCode()) : null;
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
        public List<UTXO> getOpenTransactionOutputs(List<ECKey> keys) throws UTXOProviderException {
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
                    throw new UTXOProviderException(e);
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
}
