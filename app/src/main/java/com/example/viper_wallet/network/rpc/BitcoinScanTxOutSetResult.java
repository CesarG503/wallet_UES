package com.example.viper_wallet.network.rpc;

import java.math.BigDecimal;
import java.util.List;

public class BitcoinScanTxOutSetResult {
    private boolean success;
    private int txouts;
    private double total_amount;
    private List<Unspent> unspents;

    public boolean isSuccess() {
        return success;
    }

    public int getTxouts() {
        return txouts;
    }

    public double getTotalAmount() {
        return total_amount;
    }

    public long getTotalSats() {
        return BigDecimal.valueOf(total_amount).movePointRight(8).longValue();
    }

    public List<Unspent> getUnspents() {
        return unspents;
    }

    public static class Unspent {
        private String txid;
        private int vout;
        private double amount;
        private int height;

        public String getTxid() {
            return txid;
        }

        public int getVout() {
            return vout;
        }

        public double getAmount() {
            return amount;
        }

        public long getAmountSats() {
            return BigDecimal.valueOf(amount).movePointRight(8).longValue();
        }

        public int getHeight() {
            return height;
        }
    }
}
