package com.example.viper_wallet.auth;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.viper_wallet.R;
import com.example.viper_wallet.walletcore.WalletManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;

/**
 * Paso 3 del registro: genera la wallet bitcoinj, muestra las 12 palabras semilla en un grid 3x4.
 * El botón "continuar" se habilita solo cuando el usuario marca el checkbox.
 *
 * NOTA: La wallet se genera usando la contraseña de CUENTA (Firebase) como clave interna de bitcoinj.
 * Esa contraseña ya fue validada y guardada en el ViewModel en el Paso 1.
 * La contraseña AES (para cifrar el backup en la nube) se solicita en el Paso 4.
 */
public class RegisterStep3SeedFragment extends Fragment {

    private RegisterViewModel viewModel;
    private WalletManager walletManager;

    // Views
    private LinearLayout loadingContainer;
    private View         seedContent;
    private LinearLayout actionsContainer;
    private MaterialButton btnCopy;
    private MaterialCheckBox checkboxSaved;
    private Button btnContinue;

    // Arrays para acceder a los TextViews del grid de forma dinámica
    private int[] wordNumIds;
    private int[] wordTextIds;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_register_step3_seed, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel     = new ViewModelProvider(requireActivity()).get(RegisterViewModel.class);
        walletManager = WalletManager.getInstance(requireContext());

        loadingContainer = view.findViewById(R.id.loadingContainer);
        seedContent      = view.findViewById(R.id.seedContent);
        actionsContainer = view.findViewById(R.id.actionsContainer);
        btnCopy          = view.findViewById(R.id.btnCopy);
        checkboxSaved    = view.findViewById(R.id.checkboxSaved);
        btnContinue      = view.findViewById(R.id.btnContinue);

        wordNumIds  = new int[]{ R.id.tvWordNum1, R.id.tvWordNum2, R.id.tvWordNum3,
                                 R.id.tvWordNum4, R.id.tvWordNum5, R.id.tvWordNum6,
                                 R.id.tvWordNum7, R.id.tvWordNum8, R.id.tvWordNum9,
                                 R.id.tvWordNum10,R.id.tvWordNum11,R.id.tvWordNum12 };
        wordTextIds = new int[]{ R.id.tvWord1, R.id.tvWord2, R.id.tvWord3,
                                 R.id.tvWord4, R.id.tvWord5, R.id.tvWord6,
                                 R.id.tvWord7, R.id.tvWord8, R.id.tvWord9,
                                 R.id.tvWord10,R.id.tvWord11,R.id.tvWord12 };

        // Si ya tenemos el mnemonic guardado (ej. rotación de pantalla), no regenerar
        String savedMnemonic = viewModel.mnemonic.getValue();
        if (savedMnemonic != null && !savedMnemonic.isEmpty()) {
            displayMnemonic(view, savedMnemonic);
        } else {
            generateWallet(view);
        }

        // Checkbox habilita el botón
        checkboxSaved.setOnCheckedChangeListener((compoundButton, checked) ->
                btnContinue.setEnabled(checked));

        btnCopy.setOnClickListener(v -> copyToClipboard());

        btnContinue.setOnClickListener(v -> {
            viewModel.currentStep.setValue(4);
            ((RegisterActivity) requireActivity()).goToStep(4);
        });
    }

    // ──── Generación de wallet ────────────────────────────────────────────────

    private void generateWallet(View root) {
        showLoading(true);

        // La contraseña de CUENTA (Paso 1) se usa como clave interna de bitcoinj.
        // La contraseña AES para el backup en la nube se pedirá en el Paso 4.
        String password = viewModel.password.getValue();
        if (password == null) password = "";
        final String finalPassword = password;

        walletManager.createWalletAsync(finalPassword, new WalletManager.WalletCallback() {
            @Override
            public void onSuccess(org.bitcoinj.wallet.Wallet wallet) {
                walletManager.getMnemonicAsync(finalPassword, new WalletManager.MnemonicCallback() {
                    @Override
                    public void onSuccess(String mnemonic) {
                        if (!isAdded()) return;
                        viewModel.mnemonic.setValue(mnemonic);
                        displayMnemonic(root, mnemonic);
                    }

                    @Override
                    public void onError(Exception e) {
                        if (!isAdded()) return;
                        showLoading(false);
                        Toast.makeText(requireContext(),
                                "Error generando frase semilla: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                if (!isAdded()) return;
                showLoading(false);
                Toast.makeText(requireContext(),
                        "Error creando wallet: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    // ──── Mostrar el grid de palabras ─────────────────────────────────────────

    private void displayMnemonic(View root, String mnemonic) {
        String[] words = mnemonic.split(" ");
        for (int i = 0; i < Math.min(words.length, 12); i++) {
            TextView numView  = root.findViewById(wordNumIds[i]);
            TextView wordView = root.findViewById(wordTextIds[i]);
            if (numView  != null) numView.setText(String.valueOf(i + 1));
            if (wordView != null) wordView.setText(words[i]);
        }
        showLoading(false);
    }

    private void showLoading(boolean loading) {
        loadingContainer.setVisibility(loading ? View.VISIBLE : View.GONE);
        seedContent.setVisibility(loading ? View.GONE : View.VISIBLE);
        actionsContainer.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    // ──── Copiar al portapapeles ──────────────────────────────────────────────

    private void copyToClipboard() {
        String mnemonic = viewModel.mnemonic.getValue();
        if (mnemonic == null) return;

        ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("seed_phrase", mnemonic);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(),
                    getString(R.string.reg_step3_copied), Toast.LENGTH_SHORT).show();
        }
    }
}
