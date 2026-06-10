package com.example.viper_wallet.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.example.viper_wallet.MainActivity;
import com.example.viper_wallet.R;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Pantalla de inicio de sesión.
 *
 * Flujo:
 *   1. Si ya hay sesión Firebase activa → mostrar estado "verificando" → pedir huella → MainActivity
 *   2. Si no hay sesión → mostrar formulario (email/pass o Google)
 *   3. Tras autenticación exitosa → pedir huella → MainActivity
 *
 * La contraseña de wallet (AES) NO se pide aquí. Solo se pide en operaciones sensibles
 * dentro de MainActivity (ver semilla, firmar transacción) o al restaurar wallet en nuevo dispositivo.
 */
public class LoginActivity extends AppCompatActivity {

    private static final int RC_SIGN_IN = 9001;

    private TextInputEditText etEmail, etPassword;
    private MaterialButton btnLogin;
    private ProgressBar progressBar;
    private View layoutForm;
    private View layoutBiometricWait;

    private AuthManager authManager;
    private GoogleSignInClient mGoogleSignInClient;

    /** Evita que onStart dispare biometría múltiples veces en el mismo ciclo de vida. */
    private boolean biometricTriggered = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        authManager = AuthManager.getInstance();

        etEmail         = findViewById(R.id.etEmail);
        etPassword      = findViewById(R.id.etPassword);
        btnLogin        = findViewById(R.id.btnLogin);
        progressBar     = findViewById(R.id.progressBar);
        layoutForm      = findViewById(R.id.layoutForm);
        layoutBiometricWait = findViewById(R.id.layoutBiometricWait);

        // Configurar Google Sign-In para LOGIN
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        btnLogin.setOnClickListener(v -> handleEmailLogin());
        findViewById(R.id.btnGoogleSignIn).setOnClickListener(v -> signInWithGoogle());
        findViewById(R.id.btnRegisterLink).setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Si ya hay sesión activa (usuario que vuelve a abrir la app), ir directo a biometría
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null && !biometricTriggered) {
            biometricTriggered = true;
            showBiometricWaitState();
            requestBiometrics();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Estados de UI
    // ─────────────────────────────────────────────────────────────────────────

    /** Muestra pantalla "verificando identidad" y oculta el formulario. */
    private void showBiometricWaitState() {
        if (layoutForm != null)         layoutForm.setVisibility(View.GONE);
        if (layoutBiometricWait != null) layoutBiometricWait.setVisibility(View.VISIBLE);
    }

    /** Muestra el formulario de login y oculta la pantalla de espera. */
    private void showFormState() {
        if (layoutForm != null)         layoutForm.setVisibility(View.VISIBLE);
        if (layoutBiometricWait != null) layoutBiometricWait.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Biometría
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pide la huella digital. Si el dispositivo no la tiene, va directo a MainActivity.
     * Si falla, cierra sesión y muestra el formulario.
     */
    private void requestBiometrics() {
        if (BiometricHelper.isBiometricOrPinAvailable(this)) {
            BiometricHelper.showPrompt(this, new BiometricHelper.BiometricCallback() {
                @Override
                public void onAuthenticated() {
                    goToMain();
                }

                @Override
                public void onError(String error) {
                    // Verificación cancelada o falló → cerrar sesión y mostrar formulario
                    FirebaseAuth.getInstance().signOut();
                    biometricTriggered = false;
                    showFormState();
                    Toast.makeText(LoginActivity.this,
                            "Verificación cancelada. Inicia sesión para continuar.",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            // No hay biometría ni PIN/patrón configurados en este dispositivo → pasar directo
            goToMain();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Google Sign-In (Login)
    // ─────────────────────────────────────────────────────────────────────────

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
                firebaseAuthWithGoogle(account.getIdToken());
            } catch (ApiException e) {
                Toast.makeText(this, "Google sign in failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void firebaseAuthWithGoogle(String idToken) {
        setLoading(true);
        authManager.signInWithGoogle(idToken, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(FirebaseUser user) {
                setLoading(false);
                biometricTriggered = true;
                showBiometricWaitState();
                requestBiometrics();
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                Toast.makeText(LoginActivity.this, "Error Google: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Email / Password Login
    // ─────────────────────────────────────────────────────────────────────────

    private void handleEmailLogin() {
        String email    = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Por favor completa todos los campos", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        authManager.login(email, password, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(FirebaseUser user) {
                setLoading(false);
                biometricTriggered = true;
                showBiometricWaitState();
                requestBiometrics();
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                Toast.makeText(LoginActivity.this, "Error: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navegación
    // ─────────────────────────────────────────────────────────────────────────

    private void goToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean isLoading) {
        if (progressBar != null) progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        if (btnLogin != null)    btnLogin.setEnabled(!isLoading);
    }
}
