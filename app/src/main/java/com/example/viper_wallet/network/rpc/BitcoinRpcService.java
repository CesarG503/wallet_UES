package com.example.viper_wallet.network.rpc;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Url;

public interface BitcoinRpcService {
    @POST("/")
    Call<BitcoinRpcResponse<Object>> callRpc(
            @Header("Authorization") String authHeader,
            @Body BitcoinRpcRequest request
    );

    @POST("/")
    Call<BitcoinRpcResponse<Object>> createWallet(
            @Header("Authorization") String authHeader,
            @Body BitcoinRpcRequest request
    );

    @POST("/")
    Call<BitcoinRpcResponse<String>> sendRawTransaction(
            @Header("Authorization") String authHeader,
            @Body BitcoinRpcRequest request
    );

    @POST("/")
    Call<BitcoinRpcResponse<List<String>>> generateToAddress(
            @Header("Authorization") String authHeader,
            @Body BitcoinRpcRequest request
    );

    @POST("/")
    Call<BitcoinRpcResponse<Object>> getBlockchainInfo(
            @Header("Authorization") String authHeader,
            @Body BitcoinRpcRequest request
    );

    @POST("/")
    Call<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> scanTxOutSet(
            @Header("Authorization") String authHeader,
            @Body BitcoinRpcRequest request
    );

    @POST
    Call<BitcoinRpcResponse<Object>> callWalletRpc(
            @Url String walletUrl,
            @Header("Authorization") String authHeader,
            @Body BitcoinRpcRequest request
    );
}
