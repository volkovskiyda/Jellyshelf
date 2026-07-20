package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Builds a [JellyfinApi] against a runtime-configured server URL + API key.
 * The API key is injected as the X-Emby-Token header on every request.
 */
class JellyfinClient(
    private val baseOkHttp: OkHttpClient,
    private val moshi: Moshi,
) {
    fun create(serverUrl: String, apiKey: String): JellyfinApi {
        val client = baseOkHttp.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("X-Emby-Token", apiKey)
                    .addHeader("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(serverUrl))
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(JellyfinApi::class.java)
    }

    /** Retrofit requires the base URL to end in '/'. */
    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().removeSuffix("/")
        return "$trimmed/"
    }
}
