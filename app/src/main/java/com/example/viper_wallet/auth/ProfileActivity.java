package com.example.viper_wallet.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.viper_wallet.R;
import com.example.viper_wallet.walletcore.WalletManager;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import androidx.annotation.Nullable;


public class ProfileActivity extends AppCompatActivity {

    private static final int RC_SIGN_IN = 9001;
    private TextView tvGoogleStatus;
    private com.google.android.material.button.MaterialButton btnGoogleAction;
    private GoogleSignInClient mGoogleSignInClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }

        TextView tvNickname = findViewById(R.id.tvNickname);
        TextView tvEmail    = findViewById(R.id.tvUserEmail);
        TextView tvUid      = findViewById(R.id.tvUserId);
        tvGoogleStatus      = findViewById(R.id.tvGoogleStatus);
        btnGoogleAction     = findViewById(R.id.btnGoogleAction);

        tvEmail.setText(user.getEmail());
        tvUid.setText(user.getUid());

        // Configurar Google Sign-In para la vinculación
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        updateProviderUI();

        // Cargar nickname desde Firebase Realtime Database
        FirebaseDatabase.getInstance().getReference()
                .child("users")
                .child(user.getUid())
                .child("nickname")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        String nickname = snapshot.getValue(String.class);
                        if (nickname != null && !nickname.isEmpty()) {
                            tvNickname.setText(nickname);
                        } else {
                            tvNickname.setText("—");
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        tvNickname.setText("—");
                    }
                });

        findViewById(R.id.tvViewSeed).setOnClickListener(v -> showSeedPhrase());

        findViewById(R.id.btnLogout).setOnClickListener(v -> {
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser != null) {
                com.example.viper_wallet.walletcore.WalletManager.getInstance(this).clearSavedWalletPassword(currentUser.getUid());
            }
            // 1. Cerrar sesión en Firebase
            FirebaseAuth.getInstance().signOut();

            // 2. Cerrar sesión en Google para limpiar caché de cuenta
            GoogleSignInOptions logoutGso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build();
            GoogleSignIn.getClient(this, logoutGso).signOut().addOnCompleteListener(task -> {
                // 3. Reiniciar wallet en memoria
                com.example.viper_wallet.walletcore.WalletManager.getInstance(this).reset();

                // 4. Redirigir a LoginActivity
                Intent intent = new Intent(ProfileActivity.this, LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        });
    }

    private void showSeedPhrase() {
        WalletManager walletManager = WalletManager.getInstance(this);
        if (walletManager.isEncrypted()) {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            String savedPassword = (user != null) ? walletManager.getSavedWalletPassword(user.getUid()) : null;

            if (savedPassword != null && BiometricHelper.isBiometricOrPinAvailable(this)) {
                BiometricHelper.showPrompt(this, new BiometricHelper.BiometricCallback() {
                    @Override
                    public void onAuthenticated() {
                        walletManager.getMnemonicAsync(savedPassword, new WalletManager.MnemonicCallback() {
                            @Override
                            public void onSuccess(String mnemonic) {
                                displaySeedDialog(mnemonic);
                            }

                            @Override
                            public void onError(Exception e) {
                                Toast.makeText(ProfileActivity.this, "Error al descifrar semilla: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Toast.makeText(ProfileActivity.this, "Autenticación biométrica fallida: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                requestWalletPassword(password -> {
                    walletManager.getMnemonicAsync(password, new WalletManager.MnemonicCallback() {
                        @Override
                        public void onSuccess(String mnemonic) {
                            displaySeedDialog(mnemonic);
                        }

                        @Override
                        public void onError(Exception e) {
                            Toast.makeText(ProfileActivity.this, "Contraseña incorrecta", Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            }
        } else {
            walletManager.getMnemonicAsync(null, new WalletManager.MnemonicCallback() {
                @Override
                public void onSuccess(String mnemonic) {
                    displaySeedDialog(mnemonic);
                }

                @Override
                public void onError(Exception e) {
                    Toast.makeText(ProfileActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
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

    private interface PasswordCallback {
        void onPasswordEntered(String password);
    }

    private void displaySeedDialog(String mnemonic) {
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

    private void updateProviderUI() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        boolean isGoogleLinked = false;
        for (UserInfo profile : user.getProviderData()) {
            if (GoogleAuthProvider.PROVIDER_ID.equals(profile.getProviderId())) {
                isGoogleLinked = true;
                break;
            }
        }

        if (isGoogleLinked) {
            tvGoogleStatus.setText("Vinculada");
            btnGoogleAction.setText("Vinculada");
            btnGoogleAction.setOnClickListener(v -> Toast.makeText(ProfileActivity.this, "Cuenta vinculada", Toast.LENGTH_SHORT).show());
        } else {
            tvGoogleStatus.setText("No vinculada");
            btnGoogleAction.setText("Vincular");
            btnGoogleAction.setOnClickListener(v -> signInWithGoogle());
        }
    }

    private void signInWithGoogle() {
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        startActivityForResult(signInIntent, RC_SIGN_IN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                if (account != null) {
                    FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                    String googleEmail = account.getEmail();
                    String userEmail = user != null ? user.getEmail() : null;

                    if (userEmail == null || googleEmail == null || !googleEmail.trim().equalsIgnoreCase(userEmail.trim())) {
                        Toast.makeText(this, "El correo de Google debe ser el mismo de la cuenta registrada.", Toast.LENGTH_LONG).show();
                        mGoogleSignInClient.signOut(); // Limpiar caché para permitir elegir otra cuenta
                        return;
                    }
                    linkGoogleAccount(account.getIdToken());
                }
            } catch (ApiException e) {
                Toast.makeText(this, "Error de Google: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void linkGoogleAccount(String idToken) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || idToken == null) return;

        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        user.linkWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Toast.makeText(ProfileActivity.this, "¡Cuenta de Google vinculada con éxito!", Toast.LENGTH_SHORT).show();
                        updateProviderUI();
                    } else {
                        Exception exception = task.getException();
                        if (exception instanceof com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                            Toast.makeText(ProfileActivity.this, "Esta cuenta de Google ya está vinculada a otro usuario.", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(ProfileActivity.this, "Error al vincular: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }
}
