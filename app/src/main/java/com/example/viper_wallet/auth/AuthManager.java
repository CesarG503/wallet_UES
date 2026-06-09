package com.example.viper_wallet.auth;

import com.example.viper_wallet.models.TransactionRecord;
import com.example.viper_wallet.walletcore.CryptoHelper;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKey;

public class AuthManager {
    private final FirebaseAuth auth;
    private final FirebaseFirestore db;
    private static AuthManager instance;

    private AuthManager() {
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
    }

    public static synchronized AuthManager getInstance() {
        if (instance == null) {
            instance = new AuthManager();
        }
        return instance;
    }

    public interface AuthCallback {
        void onSuccess(FirebaseUser user);
        void onError(String message);
    }

    public interface SeedCallback {
        void onSeedReady(String mnemonic);
        void onError(String message);
    }

    public interface CheckUserCallback {
        void onResult(boolean exists);
        void onError(String message);
    }

    public interface TransactionsCallback {
        void onTransactionsLoaded(java.util.List<TransactionRecord> transactions);
        void onError(String message);
    }

    public void checkUserExists(String uid, CheckUserCallback callback) {
        db.collection("users").document(uid).get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                callback.onResult(task.getResult().exists());
            } else {
                callback.onError(task.getException().getMessage());
            }
        });
    }

    public void signInWithGoogle(String idToken, AuthCallback callback) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        auth.signInWithCredential(credential).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                callback.onSuccess(task.getResult().getUser());
            } else {
                callback.onError(task.getException().getMessage());
            }
        });
    }

    /** Solo crea la cuenta Firebase (sin wallet). Úsalo en el flujo de registro por email. */
    public void createAccount(String email, String password, AuthCallback callback) {
        auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                callback.onSuccess(task.getResult().getUser());
            } else {
                callback.onError(task.getException().getMessage());
            }
        });
    }

    /** @deprecated Usar createAccount() + saveNewWalletForExistingUser() por separado. */
    @Deprecated
    public void registerWithWallet(String email, String password, String walletPassword, String mnemonic, AuthCallback callback) {
        auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                FirebaseUser user = task.getResult().getUser();
                if (user != null) {
                    saveEncryptedSeed(user.getUid(), walletPassword, mnemonic, () -> callback.onSuccess(user), callback::onError);
                }
            } else {
                callback.onError(task.getException().getMessage());
            }
        });
    }


    public void saveNewWalletForExistingUser(String uid, String walletPassword, String mnemonic, Runnable onSuccess, ErrorListener onError) {
        saveEncryptedSeed(uid, walletPassword, mnemonic, onSuccess, onError);
    }

    public void login(String email, String password, AuthCallback callback) {
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                callback.onSuccess(task.getResult().getUser());
            } else {
                callback.onError(task.getException().getMessage());
            }
        });
    }

    public void getDecryptedSeed(String uid, String walletPassword, SeedCallback callback) {
        db.collection("users").document(uid).get().addOnSuccessListener(doc -> {
            if (doc.exists()) {
                try {
                    String encryptedSeed = doc.getString("encryptedSeed");
                    byte[] iv = android.util.Base64.decode(doc.getString("iv"), android.util.Base64.DEFAULT);
                    byte[] salt = android.util.Base64.decode(doc.getString("salt"), android.util.Base64.DEFAULT);

                    SecretKey key = CryptoHelper.deriveKey(walletPassword, salt);
                    String mnemonic = CryptoHelper.decrypt(encryptedSeed, key, iv);
                    callback.onSeedReady(mnemonic);
                } catch (Exception e) {
                    callback.onError("Password de wallet incorrecto");
                }
            } else {
                callback.onError("No se encontró backup en la nube");
            }
        }).addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    public void saveTransaction(TransactionRecord transaction) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;

        db.collection("users").document(user.getUid())
                .collection("transactions").document(transaction.getTxId())
                .set(transaction)
                .addOnFailureListener(e -> android.util.Log.e("AuthManager", "Error saving tx", e));
    }

    public void getTransactions(TransactionsCallback callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;

        db.collection("users").document(user.getUid())
                .collection("transactions")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    callback.onTransactionsLoaded(queryDocumentSnapshots.toObjects(TransactionRecord.class));
                })
                .addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    private void saveEncryptedSeed(String uid, String walletPassword, String mnemonic, Runnable onSuccess, ErrorListener onError) {
        try {
            FirebaseUser user = auth.getCurrentUser();
            byte[] salt = CryptoHelper.getSalt();
            byte[] iv = CryptoHelper.getIv();
            SecretKey key = CryptoHelper.deriveKey(walletPassword, salt);
            String encryptedSeed = CryptoHelper.encrypt(mnemonic, key, iv);

            Map<String, Object> data = new HashMap<>();
            data.put("uid", uid);
            data.put("email", user != null ? user.getEmail() : "");
            data.put("createdAt", com.google.firebase.Timestamp.now());
            data.put("encryptedSeed", encryptedSeed);
            data.put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.DEFAULT));
            data.put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.DEFAULT));

            db.collection("users").document(uid).set(data)
                .addOnSuccessListener(aVoid -> onSuccess.run())
                .addOnFailureListener(e -> onError.onError(e.getMessage()));
        } catch (Exception e) {
            onError.onError("Error cifrando semilla: " + e.getMessage());
        }
    }

    public interface ErrorListener { void onError(String message); }
}
