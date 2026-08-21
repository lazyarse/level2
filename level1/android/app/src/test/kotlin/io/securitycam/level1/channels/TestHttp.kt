package io.securitycam.level1.channels

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Shared helpers for channel unit tests. */
object TestHttp {
    /** Client with no keep-alive so MockWebServer.shutdown() never blocks. */
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .retryOnConnectionFailure(false)
        .build()

    /** Client that redirects every request to the local mock server. */
    fun rewritingClient(mockBase: HttpUrl): OkHttpClient = client()
        .newBuilder()
        .addInterceptor { chain ->
            val req = chain.request()
            val rewritten = req.url.newBuilder()
                .scheme(mockBase.scheme)
                .host(mockBase.host)
                .port(mockBase.port)
                .build()
            chain.proceed(req.newBuilder().url(rewritten).build())
        }
        .build()
}