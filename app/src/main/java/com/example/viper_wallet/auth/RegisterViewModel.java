package com.example.viper_wallet.auth;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * ViewModel compartido entre RegisterActivity y los 4 Fragments.
 * Sobrevive rotaciones de pantalla.
 *
 * Flujo:
 *  Paso 1 — correo + contraseña de CUENTA (Firebase)
 *  Paso 2 — nickname (apodo personal, solo para la app)
 *  Paso 3 — frase semilla de 12 palabras
 *  Paso 4 — contraseña de WALLET (AES-256, diferente a la de cuenta)
 */
public class RegisterViewModel extends ViewModel {

    // Paso 1: credenciales Firebase
    public final MutableLiveData<String> email    = new MutableLiveData<>("");
    /** Contraseña de CUENTA — usada para Firebase Auth (login). */
    public final MutableLiveData<String> password = new MutableLiveData<>("");

    // Paso 2: apodo personal
    /** Nickname local — se almacena en Firebase bajo users/{uid}/nickname. No aparece en transacciones. */
    public final MutableLiveData<String> nickname = new MutableLiveData<>("");

    // Paso 3: frase semilla generada
    public final MutableLiveData<String> mnemonic = new MutableLiveData<>(null);

    // UID Firebase — se establece al crear la cuenta en el paso 4
    public final MutableLiveData<String> uid = new MutableLiveData<>(null);

    // Paso actual (1-4)
    public final MutableLiveData<Integer> currentStep = new MutableLiveData<>(1);
}
