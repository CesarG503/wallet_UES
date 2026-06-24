package com.example.viper_wallet.network.api;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface MiningApiService {
    @GET("mining/work")
    Call<ApiEnvelope<MiningWork>> getWork(@Query("address") String address);

    @POST("mining/submit")
    Call<ApiEnvelope<MiningSubmitResult>> submitSolution(@Body Map<String, Object> body);

    @POST("devices/register")
    Call<Map<String, Object>> registerDevice(@Body Map<String, String> body);
}
