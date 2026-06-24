package com.example.viper_wallet;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.viper_wallet.databinding.ActivityReceiveBinding;
import com.example.viper_wallet.walletcore.WalletManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.io.IOException;

public class ReceiveActivity extends AppCompatActivity {
    private ActivityReceiveBinding binding;
    private WalletManager walletManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityReceiveBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainReceive, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        walletManager = WalletManager.getInstance(this);

        setupToolbar();
        setupListeners();
        displayCurrentAddress();
    }

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupListeners() {
        binding.btnCopyAddress.setOnClickListener(v -> {
            String address = binding.tvAddress.getText().toString();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Dirección de UESCoin", address);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Dirección copiada al portapapeles", Toast.LENGTH_SHORT).show();
        });

        binding.btnNewAddress.setOnClickListener(v -> {
            try {
                String newAddress = walletManager.getFreshReceiveAddress();
                updateAddressUI(newAddress);
            } catch (IOException e) {
                Toast.makeText(this, "Error al generar nueva dirección", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void displayCurrentAddress() {
        String address = walletManager.getCurrentReceiveAddress();
        if (address != null) {
            updateAddressUI(address);
        }
    }

    private void updateAddressUI(String address) {
        binding.tvAddress.setText(address);
        try {
            Bitmap qrBitmap = generateQrBitmap(address);
            binding.ivQrCode.setImageBitmap(qrBitmap);
        } catch (WriterException e) {
            Log.e("ReceiveActivity", "Error generando QR", e);
        }
    }

    private Bitmap generateQrBitmap(String content) throws WriterException {
        int size = 512;
        BitMatrix bitMatrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }
}
