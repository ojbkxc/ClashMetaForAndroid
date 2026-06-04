package com.github.kr328.clash.v2board

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header

interface V2BoardApi {

    @GET("/api/v1/user/getSubscribe")
    suspend fun getSubscribe(
        @Header("Authorization") authData: String,
    ): Response<SubscribeResponse>
}

data class SubscribeResponse(
    @SerializedName("data") val data: SubscribeData?,
    @SerializedName("message") val message: String? = null,
)

data class SubscribeData(
    @SerializedName("subscribe_url") val subscribeUrl: String?,
    @SerializedName("token") val token: String?,
)
