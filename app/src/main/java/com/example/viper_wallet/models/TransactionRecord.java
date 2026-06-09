package com.example.viper_wallet.models;

import com.google.firebase.Timestamp;

public class TransactionRecord {
    private String txId;
    private String type; // "SEND" o "RECEIVE"
    private long amountSats;
    private String address;
    private Timestamp timestamp;

    public TransactionRecord() {} // Requerido para Firestore

    public TransactionRecord(String txId, String type, long amountSats, String address) {
        this.txId = txId;
        this.type = type;
        this.amountSats = amountSats;
        this.address = address;
        this.timestamp = Timestamp.now();
    }

    // Getters y Setters
    public String getTxId() { return txId; }
    public String getType() { return type; }
    public long getAmountSats() { return amountSats; }
    public String getAddress() { return address; }
    public Timestamp getTimestamp() { return timestamp; }
}
