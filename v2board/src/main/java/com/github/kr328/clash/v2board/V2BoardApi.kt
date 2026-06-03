package com.github.kr328.clash.v2board

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded

interface V2BoardApi {

    @FormUrlEncoded
    @POST("/api/v1/passport/auth/login")
    suspend fun login(
        @Field("email") email: String,
        @Field("password") password: String,
    ): Response<LoginResponse>

    @GET("/api/v1/user/getSubscribe")
    suspend fun getSubscribe(
        @Header("Authorization") authData: String,
    ): Response<SubscribeResponse>

    data class LoginResponse(
        @SerializedName("data") val data: LoginData?,
        @SerializedName("message") val message: String? = null,
    )

    data class LoginData(
        @SerializedName("auth_data") val authData: String,
        @SerializedName("token") val token: String,
    )

    data class SubscribeResponse(
        @SerializedName("data") val data: SubscribeData?,
        @SerializedName("message") val message: String? = null,
    )

    data class SubscribeData(
        @SerializedName("subscribe_url") val subscribeUrl: String?,
        @SerializedName("token") val token: String?,
    )
}
