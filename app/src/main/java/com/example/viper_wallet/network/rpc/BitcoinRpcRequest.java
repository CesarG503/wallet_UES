package com.example.viper_wallet.network.rpc;

import java.util.List;

public class BitcoinRpcRequest {
    private final String jsonrpc = "1.0";
    private final String id;
    private final String method;
    private final List<Object> params;

    public BitcoinRpcRequest(String id, String method, List<Object> params) {
        this.id = id;
        this.method = method;
        this.params = params;
    }
}
