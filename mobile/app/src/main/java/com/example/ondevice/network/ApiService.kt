package com.example.ondevice.network

import com.example.ondevice.User
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

data class LoginResponse(
    val token: String,
    val role: String,
    val username: String
)

interface ApiService {
    @FormUrlEncoded
    @POST("/api/user/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String
    ): Response<LoginResponse>

    // 2. 개인 회원가입
    @POST("/api/user/signup/personal")
    suspend fun signupPersonal(@Body user: User): Response<Map<String, String>>

    // 3. 기관 회원가입
    @POST("/api/user/signup/company")
    suspend fun signupCompany(@Body user: User): Response<Map<String, String>>

    // 4. 보호자 회원가입
    @POST("/api/user/signup/guardian")
    suspend fun signupGuardian(@Body user: User): Response<Map<String, String>>
}

object RetrofitClient {
    private const val BASE_URL = "http://10.0.2.2:8080/"

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}