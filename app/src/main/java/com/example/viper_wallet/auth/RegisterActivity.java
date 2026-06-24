package com.example.viper_wallet.auth;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.example.viper_wallet.R;

/**
 * Contenedor del flujo de registro en 4 pasos.
 *
 * Paso 1 — RegisterStep1Fragment          : correo + contraseña de CUENTA
 * Paso 2 — RegisterStep2NicknameFragment  : apodo personal (nickname)
 * Paso 3 — RegisterStep3SeedFragment      : frase semilla de 12 palabras
 * Paso 4 — RegisterStep4AesFragment       : contraseña de WALLET (AES, diferente a la de cuenta)
 *
 * Google Sign-In ha sido eliminado del registro. Solo se admite email/contraseña.
 */
public class RegisterActivity extends AppCompatActivity {

    // ──── Views ──────────────────────────────────────────────────────────────
    private ImageButton btnBack;
    private View        dot1, dot2, dot3, dot4, dot5;

    // ──── ViewModel ──────────────────────────────────────────────────────────
    private RegisterViewModel viewModel;

    // ──── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        // Ajustar insets para evitar traslapes con la barra de estado/notch en SDKs modernos
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.registerRoot), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        viewModel = new ViewModelProvider(this).get(RegisterViewModel.class);

        btnBack = findViewById(R.id.btnBack);
        dot1    = findViewById(R.id.dot1);
        dot2    = findViewById(R.id.dot2);
        dot3    = findViewById(R.id.dot3);
        dot4    = findViewById(R.id.dot4);
        dot5    = findViewById(R.id.dot5);

        btnBack.setOnClickListener(v -> onBackPressed());

        // Lanzar paso 1 solo en la primera creación (no en rotaciones)
        if (savedInstanceState == null) {
            loadFragment(new RegisterStep1Fragment(), false);
            updateDots(1);
        }
    }

    // ──── Navegación entre pasos ─────────────────────────────────────────────

    /**
     * Llamado por cada Fragment cuando el usuario avanza al siguiente paso.
     */
    public void goToStep(int step) {
        Fragment nextFragment;
        switch (step) {
            case 2:  nextFragment = new RegisterStepEmailVerificationFragment(); break;
            case 3:  nextFragment = new RegisterStep2NicknameFragment(); break;
            case 4:  nextFragment = new RegisterStep3SeedFragment();     break;
            case 5:  nextFragment = new RegisterStep4AesFragment();      break;
            default: return;
        }
        loadFragment(nextFragment, true);
        updateDots(step);
    }

    /**
     * Carga un Fragment con animación de deslizamiento hacia la izquierda (o sin animación).
     */
    private void loadFragment(Fragment fragment, boolean addToBackStack) {
        FragmentTransaction ft = getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        R.anim.slide_in_right,
                        R.anim.slide_out_left,
                        R.anim.slide_in_left,
                        R.anim.slide_out_right
                )
                .replace(R.id.registerFragmentContainer, fragment);

        if (addToBackStack) {
            ft.addToBackStack(null);
        }
        ft.commit();
    }

    // ──── Barra de progreso (dots) ────────────────────────────────────────────

    private void updateDots(int activeStep) {
        dot1.setBackground(activeStep == 1
                ? getDrawable(R.drawable.bg_step_dot_active)
                : getDrawable(R.drawable.bg_step_dot_inactive));
        dot2.setBackground(activeStep == 2
                ? getDrawable(R.drawable.bg_step_dot_active)
                : getDrawable(R.drawable.bg_step_dot_inactive));
        dot3.setBackground(activeStep == 3
                ? getDrawable(R.drawable.bg_step_dot_active)
                : getDrawable(R.drawable.bg_step_dot_inactive));
        dot4.setBackground(activeStep == 4
                ? getDrawable(R.drawable.bg_step_dot_active)
                : getDrawable(R.drawable.bg_step_dot_inactive));
        dot5.setBackground(activeStep == 5
                ? getDrawable(R.drawable.bg_step_dot_active)
                : getDrawable(R.drawable.bg_step_dot_inactive));

        // Mostrar el botón atrás solo en los pasos 2, 3, 4 y 5
        btnBack.setVisibility(activeStep > 1 ? View.VISIBLE : View.INVISIBLE);
    }

    // ──── Back press ─────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        int backStackCount = getSupportFragmentManager().getBackStackEntryCount();
        if (backStackCount > 0) {
            getSupportFragmentManager().popBackStack();
            // Calcular el paso al que se regresa
            int previousStep = backStackCount; // backStackCount antes del pop
            updateDots(previousStep);
        } else {
            // Paso 1 sin historial → cerrar el registro
            finish();
        }
    }
}
