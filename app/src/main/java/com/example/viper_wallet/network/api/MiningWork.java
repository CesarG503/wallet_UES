package com.example.viper_wallet.network.api;

public class MiningWork {
    private String job_id;
    private int height;
    private String previous_block_hash;
    private String challenge;
    private String target_prefix;
    private String payload_prefix;
    private String algorithm;
    private long reward_sats;
    private String expires_at;

    public String getJobId() {
        return job_id;
    }

    public int getHeight() {
        return height;
    }

    public String getPreviousBlockHash() {
        return previous_block_hash;
    }

    public String getChallenge() {
        return challenge;
    }

    public String getTargetPrefix() {
        return target_prefix;
    }

    public String getPayloadPrefix() {
        return payload_prefix;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public long getRewardSats() {
        return reward_sats;
    }

    public String getExpiresAt() {
        return expires_at;
    }
}
