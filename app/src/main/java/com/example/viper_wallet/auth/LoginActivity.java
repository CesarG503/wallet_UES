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
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.GoogleAuthProvider;

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
    private MaterialButton btnForgotPassword;
    private ProgressBar progressBar;
    private View layoutForm;
    private View layoutBiometricWait;

    private AuthManager authManager;
    private GoogleSignInClient mGoogleSignInClient;

    /** Evita que onStart dispare biometría múltiples veces en el mismo ciclo de vida. */
    private boolean biometricTriggered = false;

    private String pendingEmail = "";
    private String pendingPassword = "";
    private boolean isLinkingFlow = false;

    // Almacena la credencial de Google mientras se pide la contraseña al usuario
    private AuthCredential pendingGoogleCredential = null;

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
        btnLogin            = findViewById(R.id.btnLogin);
        btnForgotPassword   = findViewById(R.id.btnForgotPassword);
        progressBar         = findViewById(R.id.progressBar);
        layoutForm          = findViewById(R.id.layoutForm);
        layoutBiometricWait = findViewById(R.id.layoutBiometricWait);

        // Configurar Google Sign-In para LOGIN
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        btnLogin.setOnClickListener(v -> handleEmailLogin());
        btnForgotPassword.setOnClickListener(v -> handleForgotPassword());
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
        AuthCredential googleCredential = GoogleAuthProvider.getCredential(idToken, null);

        FirebaseAuth.getInstance().signInWithCredential(googleCredential)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = task.getResult().getUser();

                        if (isLinkingFlow) {
                            // Flujo inverso: el usuario intentó entrar con contraseña primero
                            // (cuenta era solo Google). Ahora vinculamos la contraseña al UID de Google.
                            isLinkingFlow = false;
                            AuthCredential emailCred = EmailAuthProvider.getCredential(pendingEmail, pendingPassword);
                            user.linkWithCredential(emailCred).addOnCompleteListener(linkTask -> {
                                if (linkTask.isSuccessful()) {
                                    Toast.makeText(LoginActivity.this, "Cuenta vinculada exitosamente.", Toast.LENGTH_SHORT).show();
                                    verifyWalletAndProceed(user);
                                } else {
                                    Toast.makeText(LoginActivity.this,
                                            "Error al vincular: " + linkTask.getException().getMessage(),
                                            Toast.LENGTH_LONG).show();
                                    FirebaseAuth.getInstance().signOut();
                                    mGoogleSignInClient.signOut().addOnCompleteListener(t -> setLoading(false));
                                }
                            });
                        } else {
                            // Login normal con Google exitoso
                            pendingGoogleCredential = null;
                            verifyWalletAndProceed(user);
                        }

                    } else {
                        Exception e = task.getException();
                        if (e instanceof com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                            // El email ya existe con proveedor de contraseña.
                            // Flujo nuevo: pedir contraseña → iniciar sesión con ella → vincular Google.
                            com.google.firebase.auth.FirebaseAuthUserCollisionException collision =
                                    (com.google.firebase.auth.FirebaseAuthUserCollisionException) e;
                            String email = collision.getEmail();
                            pendingGoogleCredential = googleCredential;
                            setLoading(false);
                            showPasswordDialogToLinkGoogle(email != null ? email : "");
                        } else {
                            setLoading(false);
                            Toast.makeText(LoginActivity.this,
                                    "Error Google: " + (e != null ? e.getMessage() : "Desconocido"),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    /**
     * Muestra un diálogo pidiendo la contraseña del usuario.
     * El usuario tiene cuenta con Email/Password y quiere vincularle Google.
     */
    private void showPasswordDialogToLinkGoogle(String email) {
        android.widget.EditText inputPassword = new android.widget.EditText(this);
        inputPassword.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        inputPassword.setHint("Tu contraseña");
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        inputPassword.setPadding(padding, padding, padding, padding);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Vincular Google a tu cuenta")
                .setMessage("El correo " + email + " ya tiene cuenta con contraseña.\n\n"
                        + "Ingresa tu contraseña para iniciar sesión y vincular Google. "
                        + "Después podrás entrar con cualquiera de los dos métodos.")
                .setView(inputPassword)
                .setPositiveButton("Vincular", (dialog, which) -> {
                    String password = inputPassword.getText().toString();
                    if (password.isEmpty()) {
                        Toast.makeText(this, "Por favor ingresa tu contraseña.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    linkGoogleToEmailAccount(email, password);
                })
                .setNegativeButton("Cancelar", (dialog, which) -> {
                    pendingGoogleCredential = null;
                })
                .setCancelable(false)
                .show();
    }

    /**
     * Inicia sesión con las credenciales de correo/contraseña existentes
     * y luego vincula la credencial de Google pendiente al mismo UID.
     */
    private void linkGoogleToEmailAccount(String email, String password) {
        setLoading(true);
        AuthCredential emailCredential = EmailAuthProvider.getCredential(email, password);

        FirebaseAuth.getInstance().signInWithCredential(emailCredential)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = task.getResult().getUser();
                        if (user != null && pendingGoogleCredential != null) {
                            user.linkWithCredential(pendingGoogleCredential)
                                    .addOnCompleteListener(linkTask -> {
                                        pendingGoogleCredential = null;
                                        if (linkTask.isSuccessful()) {
                                            Toast.makeText(this,
                                                    "¡Google vinculado! Ahora puedes entrar con ambos métodos.",
                                                    Toast.LENGTH_LONG).show();
                                            verifyWalletAndProceed(user);
                                        } else {
                                            setLoading(false);
                                            Toast.makeText(this,
                                                    "Error al vincular Google: " + linkTask.getException().getMessage(),
                                                    Toast.LENGTH_LONG).show();
                                            FirebaseAuth.getInstance().signOut();
                                        }
                                    });
                        } else {
                            pendingGoogleCredential = null;
                            setLoading(false);
                        }
                    } else {
                        pendingGoogleCredential = null;
                        setLoading(false);
                        Toast.makeText(this,
                                "Contraseña incorrecta. Inténtalo de nuevo.",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void verifyWalletAndProceed(FirebaseUser user) {
        // Verificar si la cuenta ya tiene una wallet registrada en Realtime Database
        authManager.checkUserExists(user.getUid(), new AuthManager.CheckUserCallback() {
            @Override
            public void onResult(boolean exists) {
                if (!exists) {
                    // No tiene wallet en la nube -> borrar el usuario de Firebase Auth y no permitir entrada
                    FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
                    if (firebaseUser != null) {
                        firebaseUser.delete().addOnCompleteListener(deleteTask -> {
                            FirebaseAuth.getInstance().signOut();
                            mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                                setLoading(false);
                                Toast.makeText(LoginActivity.this,
                                        "No se encontró una wallet asociada. Por favor regístrate.",
                                        Toast.LENGTH_LONG).show();
                            });
                        });
                    } else {
                        FirebaseAuth.getInstance().signOut();
                        mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                            setLoading(false);
                            Toast.makeText(LoginActivity.this,
                                    "No se encontró una wallet asociada. Por favor regístrate.",
                                    Toast.LENGTH_LONG).show();
                            });
                    }
                } else {
                    // Sí existe la wallet -> permitir entrada
                    setLoading(false);
                    biometricTriggered = true;
                    showBiometricWaitState();
                    requestBiometrics();
                }
            }

            @Override
            public void onError(String message) {
                FirebaseAuth.getInstance().signOut();
                mGoogleSignInClient.signOut().addOnCompleteListener(task -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this,
                            "Error al verificar cuenta: " + message,
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Email / Password Login
    // ─────────────────────────────────────────────────────────────────────────

    private void handleEmailLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Por favor completa todos los campos", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        authManager.login(email, password, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(FirebaseUser user) {
                verifyWalletAndProceed(user);
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                // fetchSignInMethodsForEmail está deprecado y no funciona con
                // Email Enumeration Protection activa. Mostramos un diálogo
                // amigable que guía al usuario sin depender de esa API.
                showLoginErrorDialog(email, password);
            }
        });
    }

    /**
     * Diálogo que aparece cuando el login con correo y contraseña falla.
     * Cubre dos causas posibles:
     *   1. La contraseña está mal → botón "Olvidé mi contraseña"
     *   2. La cuenta solo tiene Google → botón "Continuar con Google"
     *      que activará el Flujo B (Google → vincula contraseña automáticamente).
     */
    private void showLoginErrorDialog(String email, String password) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("No se pudo iniciar sesión")
                .setMessage("El correo o la contraseña son incorrectos.\n\n"
                        + "• Si olvidaste tu contraseña, puedes restablecerla.\n"
                        + "• Si te registraste con Google, usa el botón "
                        + "\"Continuar con Google\" abajo. La app vinculará "
                        + "tu contraseña automáticamente.")
                .setPositiveButton("Continuar con Google", (dialog, which) -> {
                    // Guardar credenciales para el flujo de vinculación (Flujo A)
                    pendingEmail = email;
                    pendingPassword = password;
                    isLinkingFlow = true;
                    signInWithGoogle();
                })
                .setNeutralButton("Olvidé mi contraseña", (dialog, which) -> {
                    // Pre-rellenar el correo en el diálogo de recuperación
                    if (etEmail.getText() != null && etEmail.getText().toString().trim().isEmpty()) {
                        etEmail.setText(email);
                    }
                    handleForgotPassword();
                })
                .setNegativeButton("Intentar de nuevo", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recuperación de contraseña
    // ─────────────────────────────────────────────────────────────────────────

    private void handleForgotPassword() {
        // Usar el email que ya esté en el campo si lo hay
        String prefillEmail = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";

        android.widget.EditText inputEmail = new android.widget.EditText(this);
        inputEmail.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                | android.text.InputType.TYPE_CLASS_TEXT);
        inputEmail.setHint("tu@correo.com");
        if (!prefillEmail.isEmpty()) {
            inputEmail.setText(prefillEmail);
        }

        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        inputEmail.setPadding(padding, padding, padding, padding);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Recuperar contraseña")
                .setMessage("Ingresa tu correo electrónico y te enviaremos un enlace para restablecer tu contraseña.\n\nSi tu cuenta estaba registrada solo con Google, este proceso también te habilitará el acceso con correo y contraseña.")
                .setView(inputEmail)
                .setPositiveButton("Enviar", (dialog, which) -> {
                    String email = inputEmail.getText().toString().trim();
                    if (email.isEmpty()) {
                        Toast.makeText(this, "Por favor ingresa tu correo.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    sendPasswordResetEmail(email);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void sendPasswordResetEmail(String email) {
        setLoading(true);
        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    setLoading(false);
                    if (task.isSuccessful()) {
                        Toast.makeText(this,
                                "Correo de recuperación enviado a " + email + ". Revisa tu bandeja de entrada.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        String msg = task.getException() != null
                                ? task.getException().getMessage()
                                : "Error desconocido";
                        Toast.makeText(this, "Error: " + msg, Toast.LENGTH_LONG).show();
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
