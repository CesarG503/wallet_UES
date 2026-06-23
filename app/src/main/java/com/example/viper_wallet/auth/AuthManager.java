package com.example.viper_wallet.auth;

import com.example.viper_wallet.models.TransactionRecord;
import com.example.viper_wallet.walletcore.CryptoHelper;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;

public class AuthManager {
    private final FirebaseAuth auth;
    private final DatabaseReference dbRef;
    private static AuthManager instance;

    private AuthManager() {
        auth = FirebaseAuth.getInstance();
        dbRef = FirebaseDatabase.getInstance().getReference();
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
        void onTransactionsLoaded(List<TransactionRecord> transactions);
        void onError(String message);
    }

    public interface ContactsCallback {
        void onContactsLoaded(List<Contact> contacts);
        void onError(String message);
    }

    public static class Contact {
        private String name;
        private String publicKey;

        public Contact() {} // Requerido para Realtime Database

        public Contact(String name, String publicKey) {
            this.name = name;
            this.publicKey = publicKey;
        }

        public String getName() { return name; }
        public String getPublicKey() { return publicKey; }
    }

    public void checkUserExists(String uid, CheckUserCallback callback) {
        final boolean[] finished = {false};
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timeoutRunnable = () -> {
            if (!finished[0]) {
                finished[0] = true;
                callback.onError("Timeout al verificar usuario en la nube.");
            }
        };
        handler.postDelayed(timeoutRunnable, 10000);

        dbRef.child("users").child(uid).child("encryptedSeed").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!finished[0]) {
                    finished[0] = true;
                    handler.removeCallbacks(timeoutRunnable);
                    callback.onResult(snapshot.exists());
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                if (!finished[0]) {
                    finished[0] = true;
                    handler.removeCallbacks(timeoutRunnable);
                    callback.onError(error.getMessage());
                }
            }
        });
    }

    public void fetchSignInMethods(String email, com.google.android.gms.tasks.OnCompleteListener<com.google.firebase.auth.SignInMethodQueryResult> listener) {
        auth.fetchSignInMethodsForEmail(email).addOnCompleteListener(listener);
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

    /** Guarda semilla cifrada sin nickname (compatibilidad). */
    public void saveNewWalletForExistingUser(String uid, String walletPassword, String mnemonic, Runnable onSuccess, ErrorListener onError) {
        saveEncryptedSeed(uid, walletPassword, mnemonic, "", onSuccess, onError);
    }

    /**
     * Guarda semilla cifrada incluyendo el nickname del usuario.
     * @param aesPassword Contraseña de WALLET (diferente a la de cuenta Firebase).
     * @param nickname    Apodo personal del usuario (no aparece en transacciones).
     */
    public void saveNewWalletForExistingUser(String uid, String aesPassword, String mnemonic, String nickname, Runnable onSuccess, ErrorListener onError) {
        saveEncryptedSeed(uid, aesPassword, mnemonic, nickname != null ? nickname : "", onSuccess, onError);
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
        final boolean[] finished = {false};
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timeoutRunnable = () -> {
            if (!finished[0]) {
                finished[0] = true;
                callback.onError("Timeout al descargar copia de seguridad.");
            }
        };
        handler.postDelayed(timeoutRunnable, 10000);

        dbRef.child("users").child(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!finished[0]) {
                    finished[0] = true;
                    handler.removeCallbacks(timeoutRunnable);
                    if (snapshot.exists()) {
                        try {
                            String encryptedSeed = snapshot.child("encryptedSeed").getValue(String.class);
                            String ivStr = snapshot.child("iv").getValue(String.class);
                            String saltStr = snapshot.child("salt").getValue(String.class);

                            if (encryptedSeed == null || ivStr == null || saltStr == null) {
                                callback.onError("Backup incompleto en la nube");
                                return;
                            }

                            byte[] iv = android.util.Base64.decode(ivStr, android.util.Base64.DEFAULT);
                            byte[] salt = android.util.Base64.decode(saltStr, android.util.Base64.DEFAULT);

                            SecretKey key = CryptoHelper.deriveKey(walletPassword, salt);
                            String mnemonic = CryptoHelper.decrypt(encryptedSeed, key, iv);
                            callback.onSeedReady(mnemonic);
                        } catch (Exception e) {
                            callback.onError("Password de wallet incorrecto");
                        }
                    } else {
                        callback.onError("No se encontró backup en la nube");
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                if (!finished[0]) {
                    finished[0] = true;
                    handler.removeCallbacks(timeoutRunnable);
                    callback.onError(error.getMessage());
                }
            }
        });
    }

    public void saveTransaction(TransactionRecord transaction) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;

        dbRef.child("users").child(user.getUid())
                .child("transactions").child(transaction.getTxId())
                .setValue(transaction)
                .addOnFailureListener(e -> android.util.Log.e("AuthManager", "Error saving tx", e));
    }

    public void saveTransactionIfAbsent(TransactionRecord transaction, Runnable onSaved) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || transaction == null || transaction.getTxId() == null) return;

        DatabaseReference txRef = dbRef.child("users").child(user.getUid())
                .child("transactions").child(transaction.getTxId());

        txRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) return;

                txRef.setValue(transaction)
                        .addOnSuccessListener(aVoid -> {
                            if (onSaved != null) onSaved.run();
                        })
                        .addOnFailureListener(e -> android.util.Log.e("AuthManager", "Error saving tx", e));
            }

            @Override
            public void onCancelled(DatabaseError error) {
                android.util.Log.e("AuthManager", "Error checking tx", error.toException());
            }
        });
    }

    public void getTransactions(TransactionsCallback callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError("Usuario no autenticado");
            return;
        }

        final boolean[] finished = {false};
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timeoutRunnable = () -> {
            if (!finished[0]) {
                finished[0] = true;
                callback.onError("Timeout al cargar transacciones.");
            }
        };
        handler.postDelayed(timeoutRunnable, 10000);

        dbRef.child("users").child(user.getUid()).child("transactions")
                .orderByChild("timestamp")
                .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!finished[0]) {
                    finished[0] = true;
                    handler.removeCallbacks(timeoutRunnable);
                    
                    List<TransactionRecord> list = new ArrayList<>();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        TransactionRecord tx = child.getValue(TransactionRecord.class);
                        if (tx != null) {
                            list.add(0, tx); // Orden descendente
                        }
                    }
                    callback.onTransactionsLoaded(list);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                if (!finished[0]) {
                    finished[0] = true;
                    handler.removeCallbacks(timeoutRunnable);
                    callback.onError(error.getMessage());
                }
            }
        });
    }

    public void saveContact(String contactUid, String name, String publicKey, Runnable onSuccess, ErrorListener onError) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            onError.onError("Usuario no autenticado");
            return;
        }

        Contact contact = new Contact(name, publicKey);
        dbRef.child("users").child(user.getUid()).child("contacts").child(contactUid)
                .setValue(contact)
                .addOnSuccessListener(aVoid -> onSuccess.run())
                .addOnFailureListener(e -> onError.onError(e.getMessage()));
    }

    public void getContacts(ContactsCallback callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError("Usuario no autenticado");
            return;
        }

        dbRef.child("users").child(user.getUid()).child("contacts")
                .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                List<Contact> contactsList = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Contact contact = child.getValue(Contact.class);
                    if (contact != null) {
                        contactsList.add(contact);
                    }
                }
                callback.onContactsLoaded(contactsList);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                callback.onError(error.getMessage());
            }
        });
    }

    private void saveEncryptedSeed(String uid, String walletPassword, String mnemonic, String nickname, Runnable onSuccess, ErrorListener onError) {
        try {
            FirebaseUser user = auth.getCurrentUser();
            byte[] salt = CryptoHelper.getSalt();
            byte[] iv = CryptoHelper.getIv();
            SecretKey key = CryptoHelper.deriveKey(walletPassword, salt);
            String encryptedSeed = CryptoHelper.encrypt(mnemonic, key, iv);

            Map<String, Object> data = new HashMap<>();
            data.put("uid", uid);
            data.put("email", user != null ? user.getEmail() : "");
            data.put("nickname", nickname);  // Apodo personal, solo visible para el usuario
            data.put("createdAt", System.currentTimeMillis());
            data.put("encryptedSeed", encryptedSeed);
            data.put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.DEFAULT));
            data.put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.DEFAULT));

            final boolean[] finished = {false};
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            Runnable timeoutRunnable = () -> {
                if (!finished[0]) {
                    finished[0] = true;
                    onError.onError("Timeout al guardar copia de seguridad en Realtime Database. Verifica tu configuración de red.");
                }
            };
            handler.postDelayed(timeoutRunnable, 10000);

            dbRef.child("users").child(uid).setValue(data)
                .addOnSuccessListener(aVoid -> {
                    if (!finished[0]) {
                        finished[0] = true;
                        handler.removeCallbacks(timeoutRunnable);
                        onSuccess.run();
                    }
                })
                .addOnFailureListener(e -> {
                    if (!finished[0]) {
                        finished[0] = true;
                        handler.removeCallbacks(timeoutRunnable);
                        onError.onError(e.getMessage());
                    }
                });
        } catch (Exception e) {
            onError.onError("Error cifrando semilla: " + e.getMessage());
        }
    }

    public interface ErrorListener { void onError(String message); }
}
