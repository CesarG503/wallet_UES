package com.example.viper_wallet.network.rpc;

public class BitcoinRpcResponse<T> {
    private T result;
    private BitcoinRpcError error;
    private String id;

    public T getResult() {
        return result;
    }

    public BitcoinRpcError getError() {
        return error;
    }

    public String getId() {
        return id;
    }

    public static class BitcoinRpcError {
        private int code;
        private String message;

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }
}
