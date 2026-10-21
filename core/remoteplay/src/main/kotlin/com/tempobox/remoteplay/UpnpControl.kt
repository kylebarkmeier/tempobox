package com.tempobox.remoteplay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP side of UPnP: fetching device descriptions and POSTing SOAP control
 * actions. Owns its own OkHttp client with short timeouts — a renderer that
 * takes 15 seconds to answer is a dead renderer for playback purposes, and
 * the session fallback should trigger quickly.
 *
 * Every failure path logs a warning and returns null; callers decide whether
 * that is a retry, a failure count, or a fallback.
 */
@Singleton
class UpnpControl @Inject constructor() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** GETs and parses a device description; null on any failure. */
    suspend fun fetchDevice(location: String): UpnpDevice? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(location).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Device description $location returned HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body.string()
                DeviceDescription.parse(body, location).also {
                    if (it == null) Log.w(TAG, "Unparseable device description from $location")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch device description $location", e)
            null
        }
    }

    /**
     * Invokes one control action and returns its out-arguments, or null on
     * network errors and SOAP faults.
     */
    suspend fun action(
        controlUrl: String,
        serviceType: String,
        action: String,
        arguments: List<Pair<String, String>>,
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(controlUrl)
                .header("SOAPACTION", Soap.actionHeader(serviceType, action))
                .post(Soap.envelope(serviceType, action, arguments).toRequestBody(SOAP_MEDIA_TYPE))
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    Log.w(TAG, "$action on $controlUrl returned HTTP ${response.code}")
                    return@withContext null
                }
                Soap.parseResponse(body).also {
                    if (it == null) Log.w(TAG, "$action on $controlUrl returned a fault or bad XML")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$action on $controlUrl failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "UpnpControl"
        private const val CONNECT_TIMEOUT_S = 4L
        private const val READ_TIMEOUT_S = 6L
        private val SOAP_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
    }
}
