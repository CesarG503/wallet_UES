package com.example.viper_wallet.auth;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.viper_wallet.R;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
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

        findViewById(R.id.btnLogout).setOnClickListener(v -> {
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
}
