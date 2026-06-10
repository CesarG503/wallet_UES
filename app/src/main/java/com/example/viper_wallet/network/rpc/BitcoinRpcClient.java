package com.example.viper_wallet.network.rpc;

import com.example.viper_wallet.walletcore.Constants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

public class BitcoinRpcClient {
    private static BitcoinRpcClient instance;
    private final BitcoinRpcService service;
    private final String authHeader;
    private final Retrofit retrofit;

    private BitcoinRpcClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();

        retrofit = new Retrofit.Builder()
                .baseUrl(Constants.RPC_BASE_URL)
                .addConverterFactory(MoshiConverterFactory.create())
                .client(client)
                .build();

        service = retrofit.create(BitcoinRpcService.class);
        // RegTest credentials for the local server
        authHeader = Credentials.basic("admin", "admin123");
    }

    public Retrofit getRetrofit() {
        return retrofit;
    }

    public static synchronized BitcoinRpcClient getInstance() {
        if (instance == null) {
            instance = new BitcoinRpcClient();
        }
        return instance;
    }

    public static String serverWalletNameForAddress(String address) {
        if (address == null || address.isEmpty()) {
            return Constants.SERVER_WALLET_PREFIX + "default";
        }
        String safeAddress = address.replaceAll("[^a-zA-Z0-9_-]", "");
        int end = Math.min(safeAddress.length(), 24);
        return Constants.SERVER_WALLET_PREFIX + safeAddress.substring(0, end);
    }

    public void createServerWallet(String walletName, Callback<BitcoinRpcResponse<Object>> callback) {
        BitcoinRpcRequest request = new BitcoinRpcRequest(
                "createwallet",
                "createwallet",
                Arrays.asList(walletName, true, true, "", false, true, true)
        );
        service.createWallet(authHeader, request).enqueue(callback);
    }

    public void loadServerWallet(String walletName, Callback<BitcoinRpcResponse<Object>> callback) {
        BitcoinRpcRequest request = new BitcoinRpcRequest(
                "loadwallet",
                "loadwallet",
                Collections.singletonList(walletName)
        );
        service.callRpc(authHeader, request).enqueue(callback);
    }

    public void registerWatchOnlyAddress(
            String walletName,
            String address,
            Callback<BitcoinRpcResponse<Object>> callback
    ) {
        BitcoinRpcRequest descriptorRequest = new BitcoinRpcRequest(
                "descriptor",
                "getdescriptorinfo",
                Collections.singletonList("addr(" + address + ")")
        );

        service.callRpc(authHeader, descriptorRequest).enqueue(new Callback<BitcoinRpcResponse<Object>>() {
            @Override
            public void onResponse(
                    Call<BitcoinRpcResponse<Object>> call,
                    Response<BitcoinRpcResponse<Object>> response
            ) {
                BitcoinRpcResponse<Object> body = response.body();
                if (!response.isSuccessful() || body == null || body.getError() != null) {
                    callback.onResponse(call, response);
                    return;
                }

                String descriptor = descriptorFromResult(body.getResult());
                if (descriptor == null) {
                    callback.onFailure(call, new IllegalStateException("Bitcoin Core no devolvió descriptor."));
                    return;
                }

                Map<String, Object> importEntry = new LinkedHashMap<>();
                importEntry.put("desc", descriptor);
                importEntry.put("timestamp", 0);
                importEntry.put("label", "android");

                BitcoinRpcRequest importRequest = new BitcoinRpcRequest(
                        "import-address",
                        "importdescriptors",
                        Collections.singletonList(Collections.singletonList(importEntry))
                );
                service.callWalletRpc(walletUrl(walletName), authHeader, importRequest).enqueue(callback);
            }

            @Override
            public void onFailure(Call<BitcoinRpcResponse<Object>> call, Throwable t) {
                callback.onFailure(call, t);
            }
        });
    }

    private String walletUrl(String walletName) {
        HttpUrl base = HttpUrl.get(Constants.RPC_BASE_URL);
        return base.newBuilder()
                .addPathSegment("wallet")
                .addPathSegment(walletName)
                .build()
                .toString();
    }

    @SuppressWarnings("unchecked")
    private String descriptorFromResult(Object result) {
        if (!(result instanceof Map)) return null;
        Object descriptor = ((Map<String, Object>) result).get("descriptor");
        return descriptor != null ? descriptor.toString() : null;
    }

    public void generateToAddress(String address, int blocks, Callback<BitcoinRpcResponse<List<String>>> callback) {
        BitcoinRpcRequest request = new BitcoinRpcRequest("mine", "generatetoaddress", Arrays.asList(blocks, address));
        service.generateToAddress(authHeader, request).enqueue(callback);
    }

    public void sendRawTransaction(String hexTransaction, Callback<BitcoinRpcResponse<String>> callback) {
        BitcoinRpcRequest request = new BitcoinRpcRequest("send", "sendrawtransaction", Collections.singletonList(hexTransaction));
        service.sendRawTransaction(authHeader, request).enqueue(callback);
    }

    public void getBlockchainInfo(Callback<BitcoinRpcResponse<Object>> callback) {
        BitcoinRpcRequest request = new BitcoinRpcRequest("info", "getblockchaininfo", Collections.emptyList());
        service.getBlockchainInfo(authHeader, request).enqueue(callback);
    }

    public void scanTxOutSetForAddresses(List<String> addresses, Callback<BitcoinRpcResponse<BitcoinScanTxOutSetResult>> callback) {
        List<Object> descriptors = new ArrayList<>();
        for (String address : addresses) {
            descriptors.add("addr(" + address + ")");
        }

        BitcoinRpcRequest request = new BitcoinRpcRequest(
                "scan-utxos",
                "scantxoutset",
                Arrays.asList("start", descriptors)
        );
        service.scanTxOutSet(authHeader, request).enqueue(callback);
    }
}
