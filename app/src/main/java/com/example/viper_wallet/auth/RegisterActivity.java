package com.example.viper_wallet.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.viper_wallet.R;
import com.example.viper_wallet.walletcore.CryptoHelper;
import com.example.viper_wallet.walletcore.WalletManager;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Pantalla de registro de nueva cuenta.
 *
 * Flujo de 4 pasos:
 *   Paso 1 — Crear cuenta Firebase (email+pass O Google)
 *   Paso 2 — Definir contraseña de cifrado AES (diálogo)
 *   Paso 3 — Generar semilla + cifrarla + guardar en Firestore
 *   Paso 4 — Mostrar frase semilla → regresar a LoginActivity
 *
 * Al terminar, se hace signOut para que el usuario pase por el flujo de Login
 * con su contraseña y huella digital antes de entrar a la wallet.
 */
public class RegisterActivity extends AppCompatActivity {

    private static final int RC_GOOGLE_REGISTER = 9002;

    private TextInputEditText etEmail, etPassword;
    private MaterialButton btnRegister;
    private ProgressBar progressBar;
    private AuthManager authManager;
    private WalletManager walletManager;
    private GoogleSignInClient mGoogleSignInClient;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        authManager  = AuthManager.getInstance();
        walletManager = WalletManager.getInstance(this);

        etEmail    = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnRegister = findViewById(R.id.btnRegister);
        progressBar = findViewById(R.id.progressBar);

        // Configurar Google Sign-In para REGISTRO
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        btnRegister.setOnClickListener(v -> handleEmailRegistration());
        findViewById(R.id.btnGoogleRegister).setOnClickListener(v -> registerWithGoogle());
        findViewById(R.id.btnLoginLink).setOnClickListener(v -> finish());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PASO 1-A: Registro con Google
    // ─────────────────────────────────────────────────────────────────────────

    private void registerWithGoogle() {
        // Forzar siempre el selector de cuenta para registro
        mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
            Intent signInIntent = mGoogleSignInClient.getSignInIntent();
            startActivityForResult(signInIntent, RC_GOOGLE_REGISTER);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_GOOGLE_REGISTER) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                authenticateGoogleAndProceed(account.getIdToken());
            } catch (ApiException e) {
                Toast.makeText(this, "Google sign in failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void authenticateGoogleAndProceed(String idToken) {
        setLoading(true);
        authManager.signInWithGoogle(idToken, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(FirebaseUser user) {
                // Verificar si ya tiene wallet (= ya está registrado, debe usar Login)
                authManager.checkUserExists(user.getUid(), new AuthManager.CheckUserCallback() {
                    @Override
                    public void onResult(boolean alreadyHasWallet) {
                        setLoading(false);
                        if (alreadyHasWallet) {
                            // Cuenta existente → redirigir al Login
                            FirebaseAuth.getInstance().signOut();
                            Toast.makeText(RegisterActivity.this,
                                    "Esta cuenta ya está registrada. Inicia sesión.",
                                    Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            // Cuenta nueva → continuar con configuración de wallet
                            showWalletPasswordSetup(user.getUid());
                        }
                    }

                    @Override
                    public void onError(String message) {
                        setLoading(false);
                        Toast.makeText(RegisterActivity.this,
                                "Error de red: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                Toast.makeText(RegisterActivity.this, "Error Google: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PASO 1-B: Registro con Email + Contraseña
    // ─────────────────────────────────────────────────────────────────────────

    private void handleEmailRegistration() {
        String email    = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Por favor completa todos los campos", Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, "La contraseña debe tener al menos 6 caracteres", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        authManager.createAccount(email, password, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(FirebaseUser user) {
                setLoading(false);
                showWalletPasswordSetup(user.getUid());
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                Toast.makeText(RegisterActivity.this, "Error: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PASO 2: Contraseña de cifrado AES (diálogo)
    // ─────────────────────────────────────────────────────────────────────────

    private void showWalletPasswordSetup(String uid) {
        final EditText etWalletPass = new EditText(this);
        etWalletPass.setHint("Ej: MiWallet@2024");
        etWalletPass.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("🔐 Contraseña de Cifrado (AES)")
                .setMessage(
                        "Esta contraseña cifrará tu frase semilla con AES-256 antes de guardarla en la nube.\n\n" +
                        "Requisitos:\n" +
                        "  • 8+ caracteres\n" +
                        "  • Una mayúscula  (A-Z)\n" +
                        "  • Una minúscula  (a-z)\n" +
                        "  • Un número      (0-9)\n" +
                        "  • Un símbolo     (!@#$…)\n\n" +
                        "⚠️ Si pierdes esta contraseña, pierdes acceso a tus fondos."
                )
                .setView(etWalletPass)
                .setCancelable(false)
                .setPositiveButton("Generar Wallet", null)
                .setNegativeButton("Cancelar registro", (d, w) -> {
                    FirebaseAuth.getInstance().signOut();
                    finish();
                })
                .create();

        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String walletPassword = etWalletPass.getText().toString();
            if (!CryptoHelper.isValidWalletPassword(walletPassword)) {
                etWalletPass.setError("No cumple los requisitos de seguridad");
                return;
            }
            dialog.dismiss();
            // Paso 3
            createAndSaveWallet(uid, walletPassword);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PASO 3: Generar semilla, cifrar y guardar en Firestore
    // ─────────────────────────────────────────────────────────────────────────

    private void createAndSaveWallet(String uid, String walletPassword) {
        setLoading(true);
        walletManager.createWalletAsync(walletPassword, new WalletManager.WalletCallback() {
            @Override
            public void onSuccess(org.bitcoinj.wallet.Wallet wallet) {
                walletManager.getMnemonicAsync(walletPassword, new WalletManager.MnemonicCallback() {
                    @Override
                    public void onSuccess(String mnemonic) {
                        if (mnemonic == null) {
                            setLoading(false);
                            Toast.makeText(RegisterActivity.this, "Error generando la semilla. Intenta nuevamente.", Toast.LENGTH_LONG).show();
                            return;
                        }

                        authManager.saveNewWalletForExistingUser(uid, walletPassword, mnemonic,
                                () -> {
                                    setLoading(false);
                                    // Paso 4: mostrar semilla
                                    showSeedPhraseAndFinish(mnemonic);
                                },
                                error -> {
                                    setLoading(false);
                                    Toast.makeText(RegisterActivity.this,
                                            "Error guardando backup en la nube: " + error, Toast.LENGTH_LONG).show();
                                }
                        );
                    }

                    @Override
                    public void onError(Exception e) {
                        setLoading(false);
                        Toast.makeText(RegisterActivity.this, "Error generando frase semilla: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                setLoading(false);
                Toast.makeText(RegisterActivity.this, "Error crítico al crear wallet: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PASO 4: Mostrar frase semilla → regresar al Login
    // ─────────────────────────────────────────────────────────────────────────

    private void showSeedPhraseAndFinish(String mnemonic) {
        // FLAG_SECURE evita screenshots/screen recorders mientras se muestra la semilla
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        new MaterialAlertDialogBuilder(this)
                .setTitle("📝 Tu Frase Semilla (12 palabras)")
                .setMessage(
                        mnemonic + "\n\n" +
                        "━━━━━━━━━━━━━━━━━━━━\n" +
                        "⚠️  IMPORTANTE:\n" +
                        "  • Escríbelas en papel ahora\n" +
                        "  • No las fotografíes\n" +
                        "  • No las guardes en notas digitales\n" +
                        "  • Son la ÚNICA forma de recuperar tus fondos\n" +
                        "━━━━━━━━━━━━━━━━━━━━"
                )
                .setCancelable(false)
                .setPositiveButton("✅ Ya las guardé", (d, w) -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    goBackToLogin();
                })
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navegación
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Al terminar el registro, se hace signOut para que el usuario pase por el
     * flujo de Login → huella antes de entrar a su wallet.
     */
    private void goBackToLogin() {
        FirebaseAuth.getInstance().signOut();
        Toast.makeText(this, "¡Registro completo! Ahora inicia sesión.", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean isLoading) {
        if (progressBar != null) progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        if (btnRegister != null)  btnRegister.setEnabled(!isLoading);
    }
}
