package io.github.gdevenyi.claudeusage

import android.content.Context

/** All app state: tokens, cached usage, settings. Plain app-private prefs. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("claude_usage", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = p.getString("accessToken", null)
        set(v) = p.edit().putString("accessToken", v).apply()
    var refreshToken: String?
        get() = p.getString("refreshToken", null)
        set(v) = p.edit().putString("refreshToken", v).apply()
    var expiresAt: Long
        get() = p.getLong("expiresAt", 0)
        set(v) = p.edit().putLong("expiresAt", v).apply()

    val loggedIn get() = refreshToken != null

    /** True after a refresh fails with an auth error; user must log in again. */
    var authBroken: Boolean
        get() = p.getBoolean("authBroken", false)
        set(v) = p.edit().putBoolean("authBroken", v).apply()

    /** Refresh tokens are single-use: persist rotation synchronously. */
    fun saveTokens(access: String, refresh: String, expiresAtMs: Long) {
        p.edit()
            .putString("accessToken", access)
            .putString("refreshToken", refresh)
            .putLong("expiresAt", expiresAtMs)
            .putBoolean("authBroken", false)
            .commit()
    }

    fun clearTokens() {
        p.edit()
            .remove("accessToken").remove("refreshToken").remove("expiresAt")
            .putBoolean("authBroken", false)
            .commit()
    }

    /** PKCE verifier for the login round-trip currently in progress. */
    var pendingVerifier: String?
        get() = p.getString("pendingVerifier", null)
        set(v) = p.edit().putString("pendingVerifier", v).apply()

    var cachedUsage: String?
        get() = p.getString("cachedUsage", null)
        set(v) = p.edit().putString("cachedUsage", v).apply()
    var cachedAt: Long
        get() = p.getLong("cachedAt", 0)
        set(v) = p.edit().putLong("cachedAt", v).apply()

    /** e.g. "Max 5x". Empty when the token response carried no plan field. */
    var plan: String
        get() = p.getString("plan", "") ?: ""
        set(v) = p.edit().putString("plan", v).apply()

    var notifEnabled: Boolean
        get() = p.getBoolean("notifEnabled", true)
        set(v) = p.edit().putBoolean("notifEnabled", v).apply()
    var intervalMin: Int
        get() = p.getInt("intervalMin", 15)
        set(v) = p.edit().putInt("intervalMin", v).apply()
}
