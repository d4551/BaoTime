package com.baotime.app.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RawHttpResponse(val code: Int, val body: String)

internal object BlueBubblesHttpTransport {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun execute(
        httpClient: OkHttpClient,
        baseUrl: URL,
        password: String,
        path: String,
        method: String,
        jsonOutput: String?,
        extraQuery: String? = null
    ): RawHttpResponse {
        val encodedPassword = URLEncoder.encode(password, Charsets.UTF_8.name())
        val query = if (extraQuery.isNullOrBlank()) {
            "password=$encodedPassword"
        } else {
            "$extraQuery&password=$encodedPassword"
        }
        val builder = Request.Builder()
            .url(URL(baseUrl, "$path?$query"))
            .header("Accept", "application/json")
            .method(method, jsonOutput?.toRequestBody(JSON_MEDIA_TYPE))
        val call = httpClient.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCompleted) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        // Every failure resumes the caller's coroutine; throwing here would
                        // crash OkHttp's dispatcher thread instead of the typed client path.
                        runCatching { res.body.string() }.fold(
                            onSuccess = {
                                if (!continuation.isCompleted) {
                                    continuation.resume(RawHttpResponse(res.code, it))
                                }
                            },
                            onFailure = {
                                if (!continuation.isCompleted) continuation.resumeWithException(it)
                            }
                        )
                    }
                }
            })
        }
    }
}
