package com.example.viper_wallet.network.api;

import com.example.viper_wallet.walletcore.Constants;

import java.util.LinkedHashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

public class MiningApiClient {
    private static MiningApiClient instance;
    private final MiningApiService service;

    private MiningApiClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(Constants.API_BASE_URL)
                .addConverterFactory(MoshiConverterFactory.create())
                .client(client)
                .build();

        service = retrofit.create(MiningApiService.class);
    }

    public static synchronized MiningApiClient getInstance() {
        if (instance == null) {
            instance = new MiningApiClient();
        }
        return instance;
    }

    public void getWork(String address, Callback<ApiEnvelope<MiningWork>> callback) {
        service.getWork(address).enqueue(callback);
    }

    public void submitSolution(
            String jobId,
            String address,
            String nonce,
            long attempts,
            double hashRate,
            Callback<ApiEnvelope<MiningSubmitResult>> callback
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", jobId);
        body.put("address", address);
        body.put("nonce", nonce);
        body.put("attempts", attempts);
        body.put("hash_rate", hashRate);
        service.submitSolution(body).enqueue(callback);
    }
}
