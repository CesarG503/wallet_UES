package com.example.viper_wallet.walletcore;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.RegTestParams;

public class Constants {
    public static final NetworkParameters NETWORK_PARAMETERS = RegTestParams.get();

    public static final String NETWORK_NAME = "regtest";
    public static final String COIN_TICKER = "UESCoin";
    public static final String COIN_DISPLAY_NAME = "UESCoin";

    public static final String NODE_HOST = "217.216.51.62";
    public static final int NODE_P2P_PORT = 18444;
    public static final int NODE_RPC_PORT = 18443;
    public static final String RPC_BASE_URL = "http://217.216.51.62:18443/";
    public static final String API_BASE_URL = "https://uexp-api.mixgyt.com/api/";
    public static final String SERVER_WALLET_PREFIX = "viper_android_";

    public static final String WALLET_FILENAME = "viper_wallet_regtest.protobuf";
    public static final String PREFS_NAME = "viper_prefs_regtest";
    public static final String KEY_HAS_WALLET = "has_wallet";
    public static final String KEY_SERVER_WALLET_NAME = "server_wallet_name";
}
