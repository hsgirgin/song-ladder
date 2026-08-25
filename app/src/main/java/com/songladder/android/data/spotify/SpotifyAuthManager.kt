package com.songladder.android.data.spotify

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.songladder.android.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume

sealed interface SpotifyAuthState {
    data object LoggedOut : SpotifyAuthState
    data class LoggedIn(val displayName: String) : SpotifyAuthState
}

class SpotifyAuthManager(
    context: Context,
    private val httpClient: OkHttpClient
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "spotify_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val authState = MutableStateFlow<SpotifyAuthState>(SpotifyAuthState.LoggedOut)
    val state: StateFlow<SpotifyAuthState> = authState

    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null

    val isConfigured: Boolean get() = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()

    init {
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null)
        if (displayName != null && storedRefreshToken() != null) {
            authState.update { SpotifyAuthState.LoggedIn(displayName) }
        }
    }

    fun buildAuthorizeUri(): Uri {
        val verifier = generateRandomToken(64)
        val challenge = codeChallenge(verifier)
        val state = generateRandomToken(16)
        pendingCodeVerifier = verifier
        pendingState = state

        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", "playlist-read-private playlist-read-collaborative")
            .appendQueryParameter("state", state)
            .build()
    }

    suspend fun handleRedirect(uri: Uri): Result<Unit> = runCatching {
        val redirectError = uri.getQueryParameter("error")
        if (redirectError != null) {
            error("Spotify sign-in was cancelled or denied.")
        }

        val expectedState = pendingState
        if (expectedState == null || uri.getQueryParameter("state") != expectedState) {
            error("Spotify sign-in could not be verified. Please try again.")
        }
        val code = uri.getQueryParameter("code")
            ?: error("Spotify did not return an authorization code.")
        val verifier = pendingCodeVerifier
            ?: error("Spotify sign-in session expired. Please try again.")

        pendingCodeVerifier = null
        pendingState = null

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", BuildConfig.SPOTIFY_REDIRECT_URI)
            .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .add("code_verifier", verifier)
            .build()

        persistTokens(executeTokenRequest(body))
        fetchAndStoreProfile()
    }

    suspend fun getValidAccessToken(): Result<String> = runCatching {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val cachedAccessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        if (cachedAccessToken != null && System.currentTimeMillis() < expiresAt - TOKEN_REFRESH_BUFFER_MS) {
            return@runCatching cachedAccessToken
        }

        val refreshToken = storedRefreshToken() ?: error("Not connected to Spotify.")
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
            .build()

        persistTokens(executeTokenRequest(body), fallbackRefreshToken = refreshToken)
        prefs.getString(KEY_ACCESS_TOKEN, null) ?: error("Could not refresh your Spotify session.")
    }

    fun signOut() {
        prefs.edit().clear().apply()
        authState.update { SpotifyAuthState.LoggedOut }
    }

    private fun storedRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    private suspend fun fetchAndStoreProfile() {
        val token = getValidAccessToken().getOrThrow()
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/me")
            .header("Authorization", "Bearer $token")
            .build()
        val body = executeRequest(request, failureMessage = "Could not read your Spotify profile.")
        val root = json.parseToJsonElement(body).jsonObject
        val displayName = root["display_name"]?.jsonPrimitive?.contentOrNull
            ?: root["id"]?.jsonPrimitive?.contentOrNull
            ?: "Spotify"
        prefs.edit().putString(KEY_DISPLAY_NAME, displayName).apply()
        authState.update { SpotifyAuthState.LoggedIn(displayName) }
    }

    private fun persistTokens(tokenResponseBody: String, fallbackRefreshToken: String? = null) {
        val root = json.parseToJsonElement(tokenResponseBody).jsonObject
        val accessToken = root["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Spotify did not return an access token.")
        val refreshToken = root["refresh_token"]?.jsonPrimitive?.contentOrNull ?: fallbackRefreshToken
        val expiresInSeconds = root["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L

        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000)
        if (refreshToken != null) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken)
        }
        editor.apply()
    }

    private suspend fun executeTokenRequest(body: FormBody): String {
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .build()
        return executeRequest(request, failureMessage = "Could not complete Spotify sign-in.")
    }

    private suspend fun executeRequest(request: Request, failureMessage: String): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            if (!continuation.isCancelled) {
                                continuation.resumeWith(
                                    Result.failure(IllegalStateException("$failureMessage (${response.code})"))
                                )
                            }
                            return
                        }
                        val responseBody = response.body?.string().orEmpty()
                        if (!continuation.isCancelled) continuation.resume(responseBody)
                    }
                }
            })
        }

    private fun generateRandomToken(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_DISPLAY_NAME = "display_name"
        const val TOKEN_REFRESH_BUFFER_MS = 60_000L
    }
}
