package com.example.viper_wallet.network.api;

import java.util.List;

public class MiningSubmitResult {
    private boolean accepted;
    private String status;
    private String message;
    private String job_id;
    private String address;
    private String nonce;
    private String hash;
    private String target_prefix;
    private long reward_sats;
    private String block_hash;
    private String coinbase_txid;
    private List<String> block_hashes;
    private String winner_address;

    public boolean isAccepted() {
        return accepted;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getJobId() {
        return job_id;
    }

    public String getAddress() {
        return address;
    }

    public String getNonce() {
        return nonce;
    }

    public String getHash() {
        return hash;
    }

    public String getTargetPrefix() {
        return target_prefix;
    }

    public long getRewardSats() {
        return reward_sats;
    }

    public String getBlockHash() {
        return block_hash;
    }

    public String getCoinbaseTxId() {
        return coinbase_txid;
    }

    public List<String> getBlockHashes() {
        return block_hashes;
    }

    public String getWinnerAddress() {
        return winner_address;
    }
}
