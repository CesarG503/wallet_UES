package com.example.viper_wallet.auth;

import android.os.Bundle;
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
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Paso 2 del registro: Espera de verificación de correo electrónico.
 * Muestra el correo al que se le envió el enlace y permite comprobar el estado o reenviar.
 */
public class RegisterStepEmailVerificationFragment extends Fragment {

    private TextView tvVerificationEmail;
    private ProgressBar progressVerification;
    private Button btnVerifyDone;
    private MaterialButton btnResendEmail;

    private RegisterViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_register_step_email_verification, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(requireActivity()).get(RegisterViewModel.class);

        tvVerificationEmail  = view.findViewById(R.id.tvVerificationEmail);
        progressVerification = view.findViewById(R.id.progressVerification);
        btnVerifyDone        = view.findViewById(R.id.btnVerifyDone);
        btnResendEmail       = view.findViewById(R.id.btnResendEmail);

        // Mostrar el correo guardado en el ViewModel
        String email = viewModel.email.getValue();
        if (email != null) {
            tvVerificationEmail.setText(email);
        }

        btnVerifyDone.setOnClickListener(v -> checkEmailVerificationState());
        btnResendEmail.setOnClickListener(v -> resendVerificationEmail());
    }

    private void checkEmailVerificationState() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(requireContext(), "No hay sesión activa. Intenta iniciar sesión.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);

        // Recargar el usuario para obtener el estado de verificación actualizado de los servidores de Firebase
        user.reload().addOnCompleteListener(task -> {
            if (!isAdded()) return;
            setLoading(false);

            if (task.isSuccessful()) {
                if (user.isEmailVerified()) {
                    Toast.makeText(requireContext(), "¡Correo verificado con éxito!", Toast.LENGTH_SHORT).show();
                    // Guardar estado y avanzar al paso 3 (Nickname)
                    viewModel.currentStep.setValue(3);
                    ((RegisterActivity) requireActivity()).goToStep(3);
                } else {
                    Toast.makeText(requireContext(), 
                            "El correo aún no ha sido verificado. Por favor haz clic en el enlace enviado a tu correo.", 
                            Toast.LENGTH_LONG).show();
                }
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Error desconocido";
                Toast.makeText(requireContext(), "Error al verificar estado: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void resendVerificationEmail() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(requireContext(), "No hay sesión activa.", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        user.sendEmailVerification().addOnCompleteListener(task -> {
            if (!isAdded()) return;
            setLoading(false);

            if (task.isSuccessful()) {
                Toast.makeText(requireContext(), 
                        "Enlace de verificación reenviado. Revisa tu bandeja de entrada.", 
                        Toast.LENGTH_LONG).show();
            } else {
                String error = task.getException() != null ? task.getException().getMessage() : "Error desconocido";
                Toast.makeText(requireContext(), "Error al enviar: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setLoading(boolean loading) {
        progressVerification.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnVerifyDone.setEnabled(!loading);
        btnResendEmail.setEnabled(!loading);
    }
}
