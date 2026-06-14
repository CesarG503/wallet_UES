package com.example.viper_wallet.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.viper_wallet.R;
import com.example.viper_wallet.walletcore.WalletManager;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;

import java.util.regex.Pattern;

/**
 * Paso 4 del registro: contraseña de WALLET para cifrar la semilla con AES-256.
 *
 * ⚠️ Esta contraseña es COMPLETAMENTE DIFERENTE a la contraseña de cuenta (Firebase).
 *   - Contraseña de cuenta (Paso 1): se usa para iniciar sesión en la app.
 *   - Contraseña de wallet (Paso 4): se usa para cifrar/descifrar la semilla en la nube.
 *
 * Al pulsar "Crear Wallet":
 *   1. createAccount Firebase (email + contraseña de cuenta)
 *   2. saveNewWalletForExistingUser (uid + contraseña de WALLET + mnemonic + nickname)
 *   3. signOut → LoginActivity
 */
public class RegisterStep4AesFragment extends Fragment {

    // ──── Patrones AES ───────────────────────────────────────────────────────
    private static final Pattern HAS_UPPER  = Pattern.compile("[A-Z]");
    private static final Pattern HAS_LOWER  = Pattern.compile("[a-z]");
    private static final Pattern HAS_DIGIT  = Pattern.compile("[0-9]");
    private static final Pattern HAS_SYMBOL = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"|,.<>/?]");
    private static final int     MIN_LENGTH = 8;

    // ──── Views ──────────────────────────────────────────────────────────────
    private TextInputEditText etAesPassword;
    private TextView          tvAesError;
    private TextView          aesReqLengthIcon, aesReqUpperIcon, aesReqLowerIcon,
                              aesReqNumberIcon, aesReqSymbolIcon;
    private Button            btnCreateWallet;
    private ProgressBar       progressCreate;

    // ──── Managers ───────────────────────────────────────────────────────────
    private RegisterViewModel viewModel;
    private AuthManager       authManager;
    private WalletManager     walletManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_register_step4_aes, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel     = new ViewModelProvider(requireActivity()).get(RegisterViewModel.class);
        authManager   = AuthManager.getInstance();
        walletManager = WalletManager.getInstance(requireContext());

        etAesPassword      = view.findViewById(R.id.etAesPassword);
        tvAesError         = view.findViewById(R.id.tvAesError);
        aesReqLengthIcon   = view.findViewById(R.id.aesReqLengthIcon);
        aesReqUpperIcon    = view.findViewById(R.id.aesReqUpperIcon);
        aesReqLowerIcon    = view.findViewById(R.id.aesReqLowerIcon);
        aesReqNumberIcon   = view.findViewById(R.id.aesReqNumberIcon);
        aesReqSymbolIcon   = view.findViewById(R.id.aesReqSymbolIcon);
        btnCreateWallet    = view.findViewById(R.id.btnCreateWallet);
        progressCreate     = view.findViewById(R.id.progressCreate);

        etAesPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                tvAesError.setVisibility(View.GONE);
                updateAesRequirements(s.toString());
                btnCreateWallet.setEnabled(isAesPasswordValid(s.toString()));
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnCreateWallet.setOnClickListener(v -> onCreateWalletClicked());
    }

    // ──── Requisitos AES ─────────────────────────────────────────────────────

    private void updateAesRequirements(String pass) {
        setReq(aesReqLengthIcon, pass.length() >= MIN_LENGTH);
        setReq(aesReqUpperIcon,  HAS_UPPER.matcher(pass).find());
        setReq(aesReqLowerIcon,  HAS_LOWER.matcher(pass).find());
        setReq(aesReqNumberIcon, HAS_DIGIT.matcher(pass).find());
        setReq(aesReqSymbolIcon, HAS_SYMBOL.matcher(pass).find());
    }

    private void setReq(TextView icon, boolean met) {
        if (met) {
            icon.setText("●");
            icon.setTextColor(requireContext().getColor(R.color.primary));
        } else {
            icon.setText("○");
            icon.setTextColor(requireContext().getColor(android.R.color.darker_gray));
        }
    }

    private boolean isAesPasswordValid(String pass) {
        return pass.length() >= MIN_LENGTH
                && HAS_UPPER.matcher(pass).find()
                && HAS_LOWER.matcher(pass).find()
                && HAS_DIGIT.matcher(pass).find()
                && HAS_SYMBOL.matcher(pass).find();
    }

    // ──── Crear wallet ───────────────────────────────────────────────────────

    private void onCreateWalletClicked() {
        // La contraseña que el usuario acaba de ingresar aquí = contraseña de WALLET (AES)
        String aesPassword = getAesText();
        if (!isAesPasswordValid(aesPassword)) return;

        setLoading(true);

        // Datos del ViewModel
        String email    = viewModel.email.getValue();    // contraseña de CUENTA (Firebase)
        String password = viewModel.password.getValue(); // contraseña de CUENTA (Firebase)
        String mnemonic = viewModel.mnemonic.getValue();
        String nickname = viewModel.nickname.getValue();

        if (email == null || password == null || mnemonic == null) {
            showError("Error interno. Reinicia el registro.");
            setLoading(false);
            return;
        }

        if (nickname == null) nickname = "";
        final String finalNickname = nickname;

        // 1. Crear cuenta Firebase con la contraseña de CUENTA
        authManager.createAccount(email, password, new AuthManager.AuthCallback() {
            @Override
            public void onSuccess(com.google.firebase.auth.FirebaseUser user) {
                // 2. Guardar semilla cifrada en Realtime Database con la contraseña de WALLET
                authManager.saveNewWalletForExistingUser(
                        user.getUid(),
                        aesPassword,     // ← contraseña de WALLET para cifrado AES
                        mnemonic,
                        finalNickname,
                        () -> {
                            // 3. Sign out y volver al Login
                            if (!isAdded()) return;
                            setLoading(false);
                            FirebaseAuth.getInstance().signOut();
                            Toast.makeText(requireContext(),
                                    "¡Registro completo! Ahora inicia sesión.",
                                    Toast.LENGTH_LONG).show();
                            Intent intent = new Intent(requireContext(), LoginActivity.class);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                        },
                        errorMsg -> {
                            if (!isAdded()) return;
                            setLoading(false);
                            showError("Error guardando backup: " + errorMsg);
                            // Limpiar cuenta parcial para evitar estado inconsistente
                            user.delete();
                        }
                );
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                setLoading(false);
                showError("Error al crear cuenta: " + message);
            }
        });
    }

    // ──── Helpers ────────────────────────────────────────────────────────────

    private void setLoading(boolean loading) {
        btnCreateWallet.setEnabled(!loading);
        progressCreate.setVisibility(loading ? View.VISIBLE : View.GONE);
        etAesPassword.setEnabled(!loading);
    }

    private void showError(String msg) {
        tvAesError.setText(msg);
        tvAesError.setVisibility(View.VISIBLE);
    }

    private String getAesText() {
        Editable e = etAesPassword.getText();
        return e != null ? e.toString() : "";
    }
}
