package com.example.viper_wallet.walletcore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class CurrencyUtils {
    
    /**
     * Formatea una cantidad en satoshis a una cadena legible de UESCoin.
     * Muestra como mínimo 2 decimales y hasta 8, ocultando ceros sobrantes.
     * Ejemplo: 5000000000 -> "50.00 UESCoin"
     * Ejemplo: 12345678 -> "0.12345678 UESCoin"
     */
    public static String formatCoinAmount(long sats) {
        BigDecimal coin = BigDecimal.valueOf(sats).divide(BigDecimal.valueOf(100_000_000L), 8, RoundingMode.HALF_UP);
        
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        symbols.setDecimalSeparator('.');
        
        DecimalFormat df = new DecimalFormat("#,##0.00######", symbols);
        return df.format(coin) + " " + Constants.COIN_TICKER;
    }

    /**
     * Convierte satoshis a una cadena de texto simple para campos de entrada.
     * No incluye el ticker y usa punto como separador decimal.
     */
    public static String satsToPlainCoin(long sats) {
        return BigDecimal.valueOf(sats)
                .movePointLeft(8)
                .setScale(8, RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * Parsea una cadena de texto de moneda a satoshis.
     */
    public static long parseCoinAmountToSats(String amount) {
        if (amount == null || amount.isEmpty()) return 0L;
        try {
            return new BigDecimal(amount.replace(",", ""))
                    .movePointRight(8)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (Exception e) {
            return 0L;
        }
    }
}
