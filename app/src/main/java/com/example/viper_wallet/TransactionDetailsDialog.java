package com.example.viper_wallet;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.example.viper_wallet.auth.AuthManager;
import com.example.viper_wallet.models.TransactionRecord;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class TransactionDetailsDialog {

    private static final int TEXT_MUTED = Color.parseColor("#64748B");

    private TransactionDetailsDialog() {}

    public static void show(Context context, TransactionRecord transaction) {
        AuthManager.getInstance().getContacts(new AuthManager.ContactsCallback() {
            @Override
            public void onContactsLoaded(List<AuthManager.Contact> contacts) {
                showDialog(context, transaction, findContact(transaction.getAddress(), contacts));
            }

            @Override
            public void onError(String message) {
                Toast.makeText(context, "No se pudieron cargar amigos: " + message, Toast.LENGTH_SHORT).show();
                showDialog(context, transaction, null);
            }
        });
    }

    private static AuthManager.Contact findContact(String address, List<AuthManager.Contact> contacts) {
        if (TextUtils.isEmpty(address)) return null;
        for (AuthManager.Contact contact : contacts) {
            if (contact != null && address.equals(contact.getPublicKey())) {
                return contact;
            }
        }
        return null;
    }

    private static void showDialog(Context context, TransactionRecord transaction, AuthManager.Contact contact) {
        ScrollView scrollView = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 20);
        content.setPadding(padding, dp(context, 12), padding, 0);
        scrollView.addView(content);

        addRow(context, content, "Tipo", displayType(transaction));
        addRow(context, content, "Monto", displayAmount(transaction));
        addRow(context, content, "Fecha", displayDate(transaction));

        if (!TextUtils.isEmpty(transaction.getTxId())) {
            addSelectableRow(context, content, "TxID", transaction.getTxId());
        }

        String address = transaction.getAddress();
        if (!TextUtils.isEmpty(address)) {
            addSelectableRow(context, content, addressLabel(transaction), address);
            if (contact != null) {
                addRow(context, content, "Amigo", contact.getName());
            } else {
                addMissingContactSection(context, content, transaction, address);
            }
        } else {
            addRow(context, content, "Dirección", "No disponible");
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle("Detalle de transacción")
                .setView(scrollView)
                .setPositiveButton("Cerrar", null)
                .show();
    }

    private static void addMissingContactSection(
            Context context,
            LinearLayout content,
            TransactionRecord transaction,
            String address
    ) {
        if (!"SEND".equals(transaction.getType())) {
            return;
        }

        TextView infoView = new TextView(context);
        infoView.setText("Esta dirección no está guardada en tus amigos.");
        infoView.setTextColor(TEXT_MUTED);
        infoView.setPadding(0, dp(context, 10), 0, dp(context, 8));
        content.addView(infoView);

        MaterialButton addButton = new MaterialButton(context);
        addButton.setText("Agregar amigo");
        addButton.setOnClickListener(v -> showSaveFriendDialog(context, address));
        content.addView(addButton);
    }

    private static void showSaveFriendDialog(Context context, String address) {
        EditText nameInput = new EditText(context);
        nameInput.setHint("Nombre del amigo");
        nameInput.setSingleLine(true);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle("Guardar amigo")
                .setMessage(address)
                .setView(nameInput)
                .setPositiveButton("Guardar", null)
                .setNegativeButton("Cancelar", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                nameInput.setError("Escribe un nombre");
                return;
            }
            AuthManager.getInstance().saveContact(
                    contactKeyForAddress(address),
                    name,
                    address,
                    () -> {
                        Toast.makeText(context, "Amigo guardado", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    },
                    message -> Toast.makeText(context, "No se pudo guardar amigo: " + message, Toast.LENGTH_LONG).show()
            );
        }));

        dialog.show();
    }

    private static void addRow(Context context, LinearLayout content, String label, String value) {
        LinearLayout row = buildRow(context, label);
        TextView valueView = buildValueView(context, value);
        row.addView(valueView);
        content.addView(row);
    }

    private static void addSelectableRow(Context context, LinearLayout content, String label, String value) {
        LinearLayout row = buildRow(context, label);
        TextView valueView = buildValueView(context, value);
        valueView.setTextIsSelectable(true);
        row.addView(valueView);
        content.addView(row);
    }

    private static LinearLayout buildRow(Context context, String label) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(context, 8), 0, dp(context, 8));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(TEXT_MUTED);
        labelView.setTextSize(12);
        labelView.setGravity(Gravity.START);
        row.addView(labelView);
        return row;
    }

    private static TextView buildValueView(Context context, String value) {
        TextView valueView = new TextView(context);
        valueView.setText(TextUtils.isEmpty(value) ? "No disponible" : value);
        valueView.setTextSize(15);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        return valueView;
    }

    private static String displayType(TransactionRecord transaction) {
        if ("SEND".equals(transaction.getType())) return "Envío";
        if ("MINING".equals(transaction.getType())) return "Ganancia por minería";
        return "Recepción";
    }

    private static String displayAmount(TransactionRecord transaction) {
        boolean isSend = "SEND".equals(transaction.getType());
        String sign = isSend ? "-" : "+";
        return sign + com.example.viper_wallet.walletcore.CurrencyUtils.formatCoinAmount(transaction.getAmountSats());
    }

    private static String displayDate(TransactionRecord transaction) {
        if (transaction.getTimestamp() == 0) return "No disponible";
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd MMM, yyyy - HH:mm", Locale.getDefault());
        return dateFormat.format(new Date(transaction.getTimestamp()));
    }

    private static String addressLabel(TransactionRecord transaction) {
        if ("SEND".equals(transaction.getType())) return "Dirección destino";
        if ("MINING".equals(transaction.getType())) return "Dirección de recompensa";
        return "Dirección que recibió";
    }

    private static String contactKeyForAddress(String address) {
        return address.replaceAll("[.#$\\[\\]/]", "_");
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
