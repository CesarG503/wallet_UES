package com.example.viper_wallet.auth;

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
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * Paso 2 del registro: apodo (nickname) personal.
 * Solo visible para el usuario en su perfil. No aparece en transacciones.
 * Mínimo 2 caracteres para habilitar el botón Continuar.
 */
public class RegisterStep2NicknameFragment extends Fragment {

    private static final int MIN_NICKNAME_LENGTH = 2;

    private TextInputLayout    tilNickname;
    private TextInputEditText  etNickname;
    private TextView           tvNicknameError;
    private Button             btnNext;

    private RegisterViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_register_step2_nickname, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(RegisterViewModel.class);

        tilNickname     = view.findViewById(R.id.tilNickname);
        etNickname      = view.findViewById(R.id.etNickname);
        tvNicknameError = view.findViewById(R.id.tvNicknameError);
        btnNext         = view.findViewById(R.id.btnNextNickname);

        // Restaurar si el usuario regresó al paso anterior
        String saved = viewModel.nickname.getValue();
        if (saved != null && !saved.isEmpty()) {
            etNickname.setText(saved);
        }

        etNickname.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                tvNicknameError.setVisibility(View.GONE);
                tilNickname.setError(null);
                btnNext.setEnabled(s.toString().trim().length() >= MIN_NICKNAME_LENGTH);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnNext.setOnClickListener(v -> onNextClicked());
    }

    private void onNextClicked() {
        String nickname = getNicknameText();
        if (nickname.length() < MIN_NICKNAME_LENGTH) {
            tvNicknameError.setText(getString(R.string.reg_step2_nickname_too_short));
            tvNicknameError.setVisibility(View.VISIBLE);
            return;
        }

        // Guardar en ViewModel y avanzar al paso 4 (semilla)
        viewModel.nickname.setValue(nickname);
        viewModel.currentStep.setValue(4);
        ((RegisterActivity) requireActivity()).goToStep(4);
    }

    private String getNicknameText() {
        Editable e = etNickname.getText();
        return e != null ? e.toString().trim() : "";
    }
}
