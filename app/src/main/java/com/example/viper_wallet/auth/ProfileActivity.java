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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class ProfileActivity extends AppCompatActivity {

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

        tvEmail.setText(user.getEmail());
        tvUid.setText(user.getUid());

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
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build();
            GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener(task -> {
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
}
