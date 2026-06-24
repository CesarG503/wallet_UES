package com.example.viper_wallet.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.viper_wallet.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

import java.util.regex.Pattern;

/**
 * Paso 1 del registro: correo electrónico + contraseña juntos,
 * para que los gestores de contraseñas (Bitwarden, Autofill) los relacionen y guarden.
 *
 * Validaciones:
 *  - Formato de correo válido (regex)
 *  - Correo ya registrado → error inline, no Toast
 *  - Contraseña: 5 requisitos animados en tiempo real
 */
public class RegisterStep1Fragment extends Fragment {

    // ──── Patrón de email ────────────────────────────────────────────────────
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    // ──── Patrones de contraseña ─────────────────────────────────────────────
    private static final Pattern HAS_UPPER  = Pattern.compile("[A-Z]");
    private static final Pattern HAS_LOWER  = Pattern.compile("[a-z]");
    private static final Pattern HAS_DIGIT  = Pattern.compile("[0-9]");
    private static final Pattern HAS_SYMBOL = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]");
    private static final int     MIN_LENGTH = 6;

    // ──── Views ──────────────────────────────────────────────────────────────
    private TextInputLayout    tilEmail, tilPassword;
    private TextInputEditText  etEmail, etPassword;
    private TextView           tvEmailError;
    private TextView           reqLengthIcon, reqUpperIcon, reqLowerIcon, reqNumberIcon, reqSymbolIcon;
    private Button             btnNext;
    private MaterialButton     btnGoToLogin;

    // ──── ViewModel ──────────────────────────────────────────────────────────
    private RegisterViewModel viewModel;

    private static final int RC_SIGN_IN = 9002;
    private GoogleSignInClient mGoogleSignInClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_register_step1, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(RegisterViewModel.class);

        // Bind views
        tilEmail        = view.findViewById(R.id.tilEmail);
        tilPassword     = view.findViewById(R.id.tilPassword);
        etEmail         = view.findViewById(R.id.etEmail);
        etPassword      = view.findViewById(R.id.etPassword);
        tvEmailError    = view.findViewById(R.id.tvEmailError);
        reqLengthIcon   = view.findViewById(R.id.reqLengthIcon);
        reqUpperIcon    = view.findViewById(R.id.reqUpperIcon);
        reqLowerIcon    = view.findViewById(R.id.reqLowerIcon);
        reqNumberIcon   = view.findViewById(R.id.reqNumberIcon);
        reqSymbolIcon   = view.findViewById(R.id.reqSymbolIcon);
        btnNext         = view.findViewById(R.id.btnNext);
        btnGoToLogin    = view.findViewById(R.id.btnGoToLogin);

        // Restaurar estado si el usuario vuelve atrás
        String savedEmail = viewModel.email.getValue();
        String savedPass  = viewModel.password.getValue();
        if (savedEmail != null && !savedEmail.isEmpty()) etEmail.setText(savedEmail);
        if (savedPass  != null && !savedPass.isEmpty())  etPassword.setText(savedPass);

        // Observar cambios en el campo de email para limpiar error en tiempo real
        etEmail.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                tvEmailError.setVisibility(View.GONE);
                tilEmail.setError(null);
                updateNextButtonState();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Watcher contraseña — actualiza los 5 chips en tiempo real
        etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                tilPassword.setError(null);
                updateRequirements(s.toString());
                updateNextButtonState();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnNext.setOnClickListener(v -> onNextClicked());

        btnGoToLogin.setOnClickListener(v -> requireActivity().finish());

        // Configurar Google Sign-In para la vinculación
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(requireContext(), gso);
    }

    // ──── Lógica de requisitos ───────────────────────────────────────────────

    private void updateRequirements(String pass) {
        setRequirement(reqLengthIcon, pass.length() >= MIN_LENGTH);
        setRequirement(reqUpperIcon,  HAS_UPPER.matcher(pass).find());
        setRequirement(reqLowerIcon,  HAS_LOWER.matcher(pass).find());
        setRequirement(reqNumberIcon, HAS_DIGIT.matcher(pass).find());
        setRequirement(reqSymbolIcon, HAS_SYMBOL.matcher(pass).find());
    }

    private void setRequirement(TextView icon, boolean met) {
        if (met) {
            icon.setText("●");
            icon.setTextColor(requireContext().getColor(R.color.primary));
        } else {
            icon.setText("○");
            icon.setTextColor(icon.getContext().getTheme()
                    .obtainStyledAttributes(new int[]{com.google.android.material.R.attr.colorOnSurfaceVariant})
                    .getColor(0, 0xFF64748B));
        }
    }

    private boolean isPasswordValid(String pass) {
        return pass.length() >= MIN_LENGTH
                && HAS_UPPER.matcher(pass).find()
                && HAS_LOWER.matcher(pass).find()
                && HAS_DIGIT.matcher(pass).find()
                && HAS_SYMBOL.matcher(pass).find();
    }

    private void updateNextButtonState() {
        String email = getEmailText();
        String pass  = getPasswordText();
        btnNext.setEnabled(!email.isEmpty() && !pass.isEmpty());
    }

    // ──── Acción del botón Siguiente ─────────────────────────────────────────

    private void onNextClicked() {
        String email = getEmailText();
        String pass  = getPasswordText();

        boolean isEmailValid = EMAIL_PATTERN.matcher(email).matches();
        boolean isPassValid  = isPasswordValid(pass);

        if (!isEmailValid) {
            showEmailError(getString(R.string.reg_step1_error_email_invalid));
        }
        if (!isPassValid) {
            tilPassword.setError("La contraseña no cumple con los requisitos");
        }

        if (!isEmailValid || !isPassValid) {
            return;
        }

        // Deshabilitar mientras consultamos Firebase
        btnNext.setEnabled(false);

        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            String currentEmail = currentUser.getEmail();
            if (email.equalsIgnoreCase(currentEmail)) {
                // El usuario ya existe y es el que está logueado en este momento.
                // Si cambió la contraseña, la actualizamos. Si no, solo pasamos al paso 2.
                String savedPass = viewModel.password.getValue();
                if (savedPass != null && !savedPass.equals(pass)) {
                    currentUser.updatePassword(pass).addOnCompleteListener(updateTask -> {
                        if (!isAdded()) return;
                        if (updateTask.isSuccessful()) {
                            viewModel.password.setValue(pass);
                            currentUser.sendEmailVerification();
                            viewModel.currentStep.setValue(2);
                            ((RegisterActivity) requireActivity()).goToStep(2);
                        } else {
                            btnNext.setEnabled(true);
                            showEmailError(updateTask.getException() != null ? updateTask.getException().getMessage() : "Error al actualizar la contraseña.");
                        }
                    });
                } else {
                    // Si no cambió la contraseña, enviamos/reenviamos verificación y avanzamos
                    currentUser.sendEmailVerification();
                    viewModel.currentStep.setValue(2);
                    ((RegisterActivity) requireActivity()).goToStep(2);
                }
                return;
            } else {
                // El usuario cambió de correo. Eliminamos la cuenta temporal anterior
                // y luego creamos la nueva cuenta.
                currentUser.delete().addOnCompleteListener(deleteTask -> {
                    FirebaseAuth.getInstance().signOut();
                    createNewUser(email, pass);
                });
                return;
            }
        }

        createNewUser(email, pass);
    }

    private void createNewUser(String email, String pass) {
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, pass)
                .addOnCompleteListener(task -> {
                    if (!isAdded()) return;

                    if (task.isSuccessful()) {
                        FirebaseUser user = task.getResult().getUser();
                        if (user != null) {
                            user.sendEmailVerification();
                        }
                        viewModel.email.setValue(email);
                        viewModel.password.setValue(pass);
                        viewModel.isGoogleLinked.setValue(false);
                        viewModel.currentStep.setValue(2);
                        ((RegisterActivity) requireActivity()).goToStep(2);
                    } else {
                        Exception exception = task.getException();
                        if (exception instanceof FirebaseAuthUserCollisionException) {
                            // Dado que fetchSignInMethods no funciona bajo protección de enumeración de correo,
                            // asumimos que el usuario podría haber iniciado con Google y le ofrecemos vincularlos.
                            // Si en realidad era una cuenta de contraseña, el flujo de Google fallará con colisión
                            // y se lo indicaremos al usuario oportunamente.
                            showLinkDialog(email, pass);
                        } else {
                            btnNext.setEnabled(true);
                            showEmailError(exception != null ? exception.getMessage() : "Error de red. Intenta de nuevo.");
                        }
                    }
                });
    }

    private void showLinkDialog(String email, String pass) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Cuenta de Google detectada")
                .setMessage("Este correo electrónico ya está registrado con Google. ¿Deseas iniciar sesión con tu cuenta de Google para vincular tu contraseña y poder entrar con ambos métodos?")
                .setPositiveButton("Sí, vincular", (dialog, which) -> {
                    btnNext.setEnabled(false);
                    Intent signInIntent = mGoogleSignInClient.getSignInIntent();
                    startActivityForResult(signInIntent, RC_SIGN_IN);
                })
                .setNegativeButton("Cancelar", (dialog, which) -> btnNext.setEnabled(true))
                .setCancelable(false)
                .show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount account = task.getResult(ApiException.class);
                firebaseAuthWithGoogleAndLink(account.getIdToken());
            } catch (ApiException e) {
                btnNext.setEnabled(true);
                showEmailError("Google sign in failed: " + e.getMessage());
            }
        }
    }

    private void firebaseAuthWithGoogleAndLink(String idToken) {
        String email = getEmailText();
        String pass  = getPasswordText();

        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        FirebaseAuth.getInstance().signInWithCredential(credential)
                .addOnCompleteListener(task -> {
                    if (!isAdded()) return;

                    if (task.isSuccessful()) {
                        FirebaseUser user = task.getResult().getUser();
                        if (user != null) {
                            AuthCredential emailCred = EmailAuthProvider.getCredential(email, pass);
                            user.linkWithCredential(emailCred)
                                    .addOnCompleteListener(linkTask -> {
                                        if (!isAdded()) return;
                                        if (linkTask.isSuccessful()) {
                                            AuthManager.getInstance().checkUserExists(user.getUid(), new AuthManager.CheckUserCallback() {
                                                @Override
                                                public void onResult(boolean exists) {
                                                    if (!isAdded()) return;
                                                    if (exists) {
                                                        android.widget.Toast.makeText(requireContext(), 
                                                                "Esta cuenta ya tiene una wallet. Iniciando sesión...", 
                                                                android.widget.Toast.LENGTH_SHORT).show();
                                                        Intent intent = new Intent(requireContext(), com.example.viper_wallet.MainActivity.class);
                                                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                                        startActivity(intent);
                                                    } else {
                                                        viewModel.email.setValue(email);
                                                        viewModel.password.setValue(pass);
                                                        viewModel.isGoogleLinked.setValue(true);
                                                        viewModel.currentStep.setValue(3);
                                                        ((RegisterActivity) requireActivity()).goToStep(3);
                                                    }
                                                }

                                                @Override
                                                public void onError(String message) {
                                                    if (!isAdded()) return;
                                                    btnNext.setEnabled(true);
                                                    showEmailError("Error al verificar wallet: " + message);
                                                }
                                            });
                                        } else {
                                            btnNext.setEnabled(true);
                                            Exception e = linkTask.getException();
                                            showEmailError("Error al vincular proveedores: " + (e != null ? e.getMessage() : "Desconocido"));
                                            FirebaseAuth.getInstance().signOut();
                                        }
                                    });
                        } else {
                            btnNext.setEnabled(true);
                            showEmailError("Usuario de Firebase nulo");
                        }
                    } else {
                        btnNext.setEnabled(true);
                        Exception e = task.getException();
                        if (e instanceof com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                            showEmailError("Esta cuenta ya está registrada con contraseña. Por favor, inicia sesión.");
                        } else {
                            showEmailError("Error al autenticar con Google: " + (e != null ? e.getMessage() : "Desconocido"));
                        }
                    }
                });
    }

    // ──── Helpers ────────────────────────────────────────────────────────────

    private void showEmailError(String msg) {
        tvEmailError.setText(msg);
        tvEmailError.setVisibility(View.VISIBLE);
    }

    private String getEmailText() {
        Editable e = etEmail.getText();
        return e != null ? e.toString().trim() : "";
    }

    private String getPasswordText() {
        Editable e = etPassword.getText();
        return e != null ? e.toString() : "";
    }
}
