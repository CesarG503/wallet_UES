package com.example.viper_wallet.auth;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import java.util.concurrent.Executor;

public class BiometricHelper {

    public interface BiometricCallback {
        void onAuthenticated();
        void onError(String error);
    }

    public static boolean isBiometricAvailable(FragmentActivity activity) {
        BiometricManager biometricManager = BiometricManager.from(activity);
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) 
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static void showPrompt(FragmentActivity activity, BiometricCallback callback) {
        Executor executor = ContextCompat.getMainExecutor(activity);
        BiometricPrompt biometricPrompt = new BiometricPrompt(activity, executor, 
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    callback.onError(errString.toString());
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    callback.onAuthenticated();
                }
            });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verifica tu identidad")
                .setSubtitle("Usa tu huella para acceder a tu Viper Wallet")
                .setNegativeButtonText("Cancelar")
                .build();

        biometricPrompt.authenticate(promptInfo);
    }
}
