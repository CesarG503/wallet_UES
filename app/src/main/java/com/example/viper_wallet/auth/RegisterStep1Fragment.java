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

        // Intentar crear la cuenta temporalmente para validar si el correo ya existe.
        // Si el correo ya existe, fallará con FirebaseAuthUserCollisionException.
        // Si no existe, se creará de forma exitosa y la borraremos inmediatamente.
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, pass)
                .addOnCompleteListener(task -> {
                    if (!isAdded()) return;

                    if (task.isSuccessful()) {
                        // El correo NO existe. Borramos la cuenta temporal creada inmediatamente.
                        FirebaseUser user = task.getResult().getUser();
                        if (user != null) {
                            user.delete().addOnCompleteListener(deleteTask -> {
                                if (!isAdded()) return;
                                // Guardar en el ViewModel y avanzar al paso 2
                                viewModel.email.setValue(email);
                                viewModel.password.setValue(pass);
                                viewModel.currentStep.setValue(2);
                                ((RegisterActivity) requireActivity()).goToStep(2);
                            });
                        } else {
                            // Si por algún motivo user es nulo, avanzamos de todos modos
                            viewModel.email.setValue(email);
                            viewModel.password.setValue(pass);
                            viewModel.currentStep.setValue(2);
                            ((RegisterActivity) requireActivity()).goToStep(2);
                        }
                    } else {
                        btnNext.setEnabled(true);
                        Exception exception = task.getException();
                        if (exception instanceof FirebaseAuthUserCollisionException) {
                            showEmailError("Usuario existente");
                        } else {
                            showEmailError(exception != null ? exception.getMessage() : "Error de red. Intenta de nuevo.");
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
