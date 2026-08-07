package io.github.gdevenyi.claudeusage

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Claude Code's OAuth PKCE flow (unofficial, reverse-engineered).
 * The app performs its own login and owns an independent grant —
 * never copies of desktop credentials (rotation is single-use and
 * shared copies log each other out).
 */
object Auth {
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private const val REDIRECT_URI = "https://console.anthropic.com/oauth/code/callback"
    private const val SCOPES = "org:create_api_key user:profile user:inference"
    private val TOKEN_URLS = listOf(
        "https://console.anthropic.com/v1/oauth/token",
        "https://platform.claude.com/v1/oauth/token",
    )

    class AuthFailed(msg: String) : Exception(msg)

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun newVerifier(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let(::b64url)

    fun authorizeUrl(verifier: String): String {
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val params = listOf(
            "code" to "true",
            "client_id" to CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to verifier, // flow quirk: state carries the verifier
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "https://claude.ai/oauth/authorize?$params"
    }

    /** Exchange the pasted "code#state" for tokens and persist them. */
    fun exchangeCode(ctx: Context, pasted: String, verifier: String) {
        val parts = pasted.trim().split("#")
        val body = mapOf(
            "grant_type" to "authorization_code",
            "code" to parts[0],
            "redirect_uri" to REDIRECT_URI,
            "client_id" to CLIENT_ID,
            "code_verifier" to verifier,
            "state" to parts.getOrElse(1) { verifier },
        )
        saveTokens(ctx, post(body))
    }

    /** Refresh the access token. Marks auth broken on invalid_grant. */
    fun refresh(ctx: Context) {
        val store = Store(ctx)
        val rt = store.refreshToken ?: throw AuthFailed("not logged in")
        val json = try {
            post(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to rt,
                    "client_id" to CLIENT_ID,
                )
            )
        } catch (e: AuthFailed) {
            store.authBroken = true
            throw e
        }
        saveTokens(ctx, json)
    }

    /** Returns a valid access token, refreshing if it expires within 5 minutes. */
    fun freshAccessToken(ctx: Context): String {
        val store = Store(ctx)
        if (!store.loggedIn || store.authBroken) throw AuthFailed("login required")
        if (store.expiresAt - System.currentTimeMillis() < 5 * 60_000) refresh(ctx)
        return store.accessToken ?: throw AuthFailed("login required")
    }

    private fun saveTokens(ctx: Context, j: JSONObject) {
        val store = Store(ctx)
        store.saveTokens(
            access = j.getString("access_token"),
            refresh = j.optString("refresh_token").ifEmpty { store.refreshToken ?: "" },
            expiresAtMs = System.currentTimeMillis() + j.optLong("expires_in", 28800) * 1000,
        )
    }

    // Endpoint quirks are community-documented, not official: try both domains
    // and both encodings (form first; some reports say JSON, some say form).
    private fun post(body: Map<String, String>): JSONObject {
        var last: Exception? = null
        for (url in TOKEN_URLS) {
            for (asJson in listOf(false, true)) {
                try {
                    return postOnce(url, body, asJson)
                } catch (e: AuthFailed) {
                    last = e
                } catch (e: Exception) {
                    last = e
                    break // network/server error: switching encoding won't help
                }
            }
        }
        throw last!!
    }

    private fun postOnce(url: String, body: Map<String, String>, asJson: Boolean): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        val bytes = if (asJson) {
            conn.setRequestProperty("Content-Type", "application/json")
            JSONObject(body as Map<*, *>).toString().toByteArray()
        } else {
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            body.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
                .toByteArray()
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            if (code in 400..499) throw AuthFailed("token endpoint $code: ${text.take(200)}")
            if (code !in 200..299) throw RuntimeException("token endpoint $code: ${text.take(200)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
