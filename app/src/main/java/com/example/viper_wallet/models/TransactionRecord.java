package com.example.viper_wallet.models;

public class TransactionRecord {
    private String txId;
    private String type; // "SEND" o "RECEIVE"
    private long amountSats;
    private String address;
    private long timestamp;

    public TransactionRecord() {} // Requerido para Realtime Database

    public TransactionRecord(String txId, String type, long amountSats, String address) {
        this.txId = txId;
        this.type = type;
        this.amountSats = amountSats;
        this.address = address;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters y Setters
    public String getTxId() { return txId; }
    public String getType() { return type; }
    public long getAmountSats() { return amountSats; }
    public String getAddress() { return address; }
    public long getTimestamp() { return timestamp; }
}
