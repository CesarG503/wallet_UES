package com.example.viper_wallet.walletcore;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class CryptoHelper {
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final int SALT_LENGTH_BYTE = 16;
    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH_BIT = 256;

    public static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BIT);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    public static String encrypt(String plainText, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipherText, Base64.DEFAULT);
    }

    public static String decrypt(String cipherText, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
        byte[] plainText = cipher.doFinal(Base64.decode(cipherText, Base64.DEFAULT));
        return new String(plainText, StandardCharsets.UTF_8);
    }

    public static byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    public static byte[] getIv() { return generateRandomBytes(IV_LENGTH_BYTE); }
    public static byte[] getSalt() { return generateRandomBytes(SALT_LENGTH_BYTE); }

    /**
     * Valida que la contraseña cumpla:
     * 8+ caracteres, 1 mayúscula, 1 minúscula, 1 número y 1 símbolo.
     */
    public static boolean isValidWalletPassword(String password) {
        if (password == null || password.length() < 8) return false;
        boolean hasUpper = false, hasLower = false, hasDigit = false, hasSymbol = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else if (!Character.isLetterOrDigit(c)) hasSymbol = true;
        }
        return hasUpper && hasLower && hasDigit && hasSymbol;
    }
}
