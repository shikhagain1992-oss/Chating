package com.example.data.remote

import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MediaStorageNotConfiguredException(
    message: String = "Upload Unavailable — Storage Not Configured: Set CLOUDINARY_SIGNING_ENDPOINT (or CLOUDINARY_UPLOAD_PRESET) for cloud f06cifgw in Secrets."
) : IllegalStateException(message)

data class UploadedMediaResult(
    val secureUrl: String,
    val publicId: String,
    val format: String,
    val resourceType: String,
    val bytes: Long,
    val durationSec: Int = 0
)

private data class SignedUploadParams(
    val signature: String,
    val timestamp: Long,
    val apiKey: String,
    val folder: String,
    val cloudName: String
)

/**
 * Handles media uploads (images, videos, audio/voice-notes, and raw files) via Cloudinary
 * (Cloud Name: `f06cifgw`) using the secure backend signing service (`CLOUDINARY_SIGNING_ENDPOINT`)
 * or a restricted unsigned upload preset.
 *
 * SECURITY INVARIANTS:
 * 1. Never references or embeds CLOUDINARY_API_SECRET in client code.
 * 2. Never falls back to Base64 or chunked Firestore documents.
 * 3. Fails fast with MediaStorageNotConfiguredException when unconfigured.
 */
class CloudinaryMediaService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val cloudNameOverride: String? = null,
    private val uploadPresetOverride: String? = null,
    private val signingEndpointOverride: String? = null
) {
    companion object {
        const val DEFAULT_CLOUD_NAME = "f06cifgw"
        const val DEFAULT_UPLOAD_PRESET = "ml_default"
    }

    val cloudName: String
        get() {
            val raw = (cloudNameOverride ?: BuildConfig.CLOUDINARY_CLOUD_NAME).trim()
            return if (raw.isBlank() || raw.startsWith("MY_") || raw.startsWith("YOUR_")) {
                if (cloudNameOverride != null) raw else DEFAULT_CLOUD_NAME
            } else {
                raw
            }
        }

    val uploadPreset: String
        get() {
            val raw = (uploadPresetOverride ?: BuildConfig.CLOUDINARY_UPLOAD_PRESET).trim()
            return if (raw.isBlank() || raw.startsWith("MY_") || raw.startsWith("YOUR_")) {
                if (uploadPresetOverride != null) raw else DEFAULT_UPLOAD_PRESET
            } else {
                raw
            }
        }

    val signingEndpoint: String
        get() = (signingEndpointOverride ?: BuildConfig.CLOUDINARY_SIGNING_ENDPOINT).trim()

    private fun String.isConfiguredSecret(): Boolean =
        isNotBlank() && !startsWith("MY_") && !startsWith("YOUR_") && this != "UNCONFIGURED"

    fun isConfigured(): Boolean {
        return cloudName.isConfiguredSecret() &&
            (signingEndpoint.isConfiguredSecret() || uploadPreset.isConfiguredSecret())
    }

    /**
     * Requests a short-lived upload signature from the trusted backend signing service
     * so that CLOUDINARY_API_SECRET remains exclusively on the backend.
     */
    private suspend fun fetchSignedUploadParams(
        folder: String,
        resourceType: String
    ): Result<SignedUploadParams> = withContext(Dispatchers.IO) {
        try {
            val idToken = try {
                Firebase.auth.currentUser?.getIdToken(false)?.await()?.token.orEmpty()
            } catch (_: Exception) {
                ""
            }

            val timestamp = System.currentTimeMillis() / 1000L
            val requestJson = JSONObject().apply {
                put("folder", folder)
                put("timestamp", timestamp)
                put("resource_type", resourceType)
                put("cloud_name", cloudName)
            }

            val reqBuilder = Request.Builder()
                .url(signingEndpoint)
                .post(
                    requestJson.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                )

            if (idToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $idToken")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Backend signing service returned HTTP ${response.code}: $bodyStr")
                    )
                }
                val json = JSONObject(bodyStr)
                val signature = json.optString("signature")
                val apiKey = json.optString("apiKey").ifBlank { json.optString("api_key") }
                val signedTimestamp = json.optLong("timestamp", timestamp)
                val signedFolder = json.optString("folder", folder)
                val resolvedCloud = json.optString("cloudName").ifBlank {
                    json.optString("cloud_name").ifBlank { cloudName }
                }

                if (signature.isBlank() || apiKey.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Backend signing response missing signature or api_key")
                    )
                }

                Result.success(
                    SignedUploadParams(
                        signature = signature,
                        timestamp = signedTimestamp,
                        apiKey = apiKey,
                        folder = signedFolder,
                        cloudName = resolvedCloud
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Failed to contact Cloudinary signing service: ${e.localizedMessage}", e))
        }
    }

    suspend fun uploadBytes(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        folder: String = "nexchat_pro",
        durationSecHint: Int = 0
    ): Result<UploadedMediaResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(MediaStorageNotConfiguredException())
        }

        try {
            // Cloudinary uses resource_type "video" for both video and audio/voice-note files,
            // "image" for images, and "raw" for documents/other files.
            val resourceType = when {
                mimeType.startsWith("image/") -> "image"
                mimeType.startsWith("video/") || mimeType.startsWith("audio/") -> "video"
                else -> "raw"
            }

            val mediaType = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
            val fileBody = bytes.toRequestBody(mediaType)

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, fileBody)

            val isCustomSigningEndpoint = signingEndpoint.isConfiguredSecret() &&
                !signingEndpoint.contains("api.cloudinary.com")

            var signedParams: SignedUploadParams? = null
            if (isCustomSigningEndpoint) {
                val signedRes = fetchSignedUploadParams(folder = folder, resourceType = resourceType)
                signedParams = signedRes.getOrNull()
                if (signedParams == null && !uploadPreset.isConfiguredSecret()) {
                    return@withContext Result.failure(
                        signedRes.exceptionOrNull() ?: IllegalStateException("Signing service unavailable")
                    )
                }
            }

            val targetCloudName: String
            val effectivePreset = if (uploadPreset.isConfiguredSecret()) uploadPreset else DEFAULT_UPLOAD_PRESET
            if (signedParams != null) {
                targetCloudName = signedParams.cloudName
                multipartBuilder
                    .addFormDataPart("api_key", signedParams.apiKey)
                    .addFormDataPart("timestamp", signedParams.timestamp.toString())
                    .addFormDataPart("signature", signedParams.signature)
                    .addFormDataPart("folder", signedParams.folder)
            } else {
                targetCloudName = cloudName
                multipartBuilder
                    .addFormDataPart("folder", folder)
                    .addFormDataPart("upload_preset", effectivePreset)
            }

            val url = "https://api.cloudinary.com/v1_1/$targetCloudName/$resourceType/upload"
            val request = Request.Builder()
                .url(url)
                .post(multipartBuilder.build())
                .build()

            var statusCode = 0
            var bodyStr = ""
            httpClient.newCall(request).execute().use { response ->
                statusCode = response.code
                bodyStr = response.body?.string().orEmpty()
            }

            // If unsigned upload with folder or specific resource_type was rejected (e.g. voice m4a on video/upload),
            // retry cleanly without folder override across auto/raw endpoints.
            if (statusCode !in 200..299 && signedParams == null) {
                val fallbackType = if (resourceType == "video") "raw" else "auto"
                val fallbackBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, bytes.toRequestBody(mediaType))
                    .addFormDataPart("upload_preset", effectivePreset)
                    .build()
                val fallbackReq = Request.Builder()
                    .url("https://api.cloudinary.com/v1_1/$targetCloudName/$fallbackType/upload")
                    .post(fallbackBody)
                    .build()
                httpClient.newCall(fallbackReq).execute().use { fallbackResp ->
                    if (fallbackResp.isSuccessful) {
                        statusCode = fallbackResp.code
                        bodyStr = fallbackResp.body?.string().orEmpty()
                    }
                }
            }

            if (statusCode !in 200..299) {
                val errMsg = try {
                    JSONObject(bodyStr).optJSONObject("error")?.optString("message")
                        ?: "HTTP $statusCode"
                } catch (_: Exception) {
                    "HTTP $statusCode"
                }
                return@withContext Result.failure(IllegalStateException("Cloudinary upload failed: $errMsg"))
            }

            val json = JSONObject(bodyStr)
            val secureUrl = json.optString("secure_url")
            if (secureUrl.isBlank() || !secureUrl.startsWith("https://")) {
                return@withContext Result.failure(IllegalStateException("Cloudinary response missing https secure_url"))
            }

            val duration = json.optDouble("duration", durationSecHint.toDouble()).toInt()
            Result.success(
                UploadedMediaResult(
                    secureUrl = secureUrl,
                    publicId = json.optString("public_id"),
                    format = json.optString("format"),
                    resourceType = json.optString("resource_type", resourceType),
                    bytes = json.optLong("bytes", bytes.size.toLong()),
                    durationSec = duration.coerceAtLeast(durationSecHint)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadFile(
        file: File,
        mimeType: String,
        folder: String = "nexchat_pro",
        durationSecHint: Int = 0
    ): Result<UploadedMediaResult> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(MediaStorageNotConfiguredException())
        }
        if (!file.exists() || file.length() <= 0L) {
            return@withContext Result.failure(IllegalArgumentException("Media file is empty or missing"))
        }
        uploadBytes(
            bytes = file.readBytes(),
            fileName = file.name,
            mimeType = mimeType,
            folder = folder,
            durationSecHint = durationSecHint
        )
    }
}
