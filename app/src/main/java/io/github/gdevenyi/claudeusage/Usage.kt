package io.github.gdevenyi.claudeusage

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Fetches and caches https://api.anthropic.com/api/oauth/usage. */
object Usage {
    // Required: without a claude-code User-Agent the endpoint 429s aggressively.
    private const val USER_AGENT = "claude-code/1.0.83"
    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    private const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"

    private class Unauthorized : Exception()

    data class Window(val pct: Int, val resetsAt: Instant?)
    data class Scoped(val name: String, val window: Window)
    data class Data(
        val session: Window?,
        val weekly: Window?,
        val scoped: List<Scoped>,
        val fetchedAt: Long,
    )

    fun fetchAndCache(ctx: Context) {
        val body = try {
            get(USAGE_URL, Auth.freshAccessToken(ctx))
        } catch (_: Unauthorized) {
            // token rejected despite the freshness check: one refresh retry
            Auth.refresh(ctx)
            get(USAGE_URL, Store(ctx).accessToken.orEmpty())
        }
        store(ctx, body)
        // Cosmetic, and one extra call: never let it fail the refresh.
        runCatching { fetchPlanIfMissing(ctx) }
    }

    private fun get(url: String, token: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) throw Unauthorized()
            if (code !in 200..299) throw RuntimeException("$url -> $code")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The plan badge ("Max 5x") comes from the profile endpoint, not the usage
     * one. It only changes when the subscription does, so fetch it once.
     */
    private fun fetchPlanIfMissing(ctx: Context) {
        val s = Store(ctx)
        if (s.plan.isNotEmpty()) return
        val org = JSONObject(get(PROFILE_URL, Auth.freshAccessToken(ctx)))
            .optJSONObject("organization") ?: return
        val tier = org.optString("rate_limit_tier").ifEmpty { return }
        s.plan = tier.removePrefix("default_claude_").replace('_', ' ').trim()
            .split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private fun store(ctx: Context, json: String) {
        JSONObject(json) // validate before caching
        val s = Store(ctx)
        s.cachedUsage = json
        s.cachedAt = System.currentTimeMillis()
    }

    fun cached(ctx: Context): Data? {
        val s = Store(ctx)
        val raw = s.cachedUsage ?: return null
        val j = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        fun window(o: JSONObject?, pctKey: String): Window? {
            o ?: return null
            val resets = o.optString("resets_at").takeIf { it.isNotEmpty() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            return Window(o.optDouble(pctKey, 0.0).toInt(), resets)
        }

        var session = window(j.optJSONObject("five_hour"), "utilization")
        var weekly = window(j.optJSONObject("seven_day"), "utilization")
        val scoped = mutableListOf<Scoped>()

        // Current shape: a `limits` array carrying session, weekly, and
        // per-model ("weekly_scoped") entries. The legacy seven_day_sonnet /
        // seven_day_opus fields come back null on present-day accounts.
        val limits = j.optJSONArray("limits")
        if (limits != null) {
            for (i in 0 until limits.length()) {
                val e = limits.optJSONObject(i) ?: continue
                val w = window(e, "percent") ?: continue
                when (e.optString("kind")) {
                    "session" -> session = w
                    "weekly_all" -> weekly = w
                    "weekly_scoped" -> {
                        val name = e.optJSONObject("scope")?.optJSONObject("model")
                            ?.optString("display_name").orEmpty()
                        if (name.isNotEmpty()) scoped += Scoped(name, w)
                    }
                }
            }
        }
        if (scoped.isEmpty()) {
            window(j.optJSONObject("seven_day_sonnet"), "utilization")
                ?.let { scoped += Scoped("Sonnet", it) }
            window(j.optJSONObject("seven_day_opus"), "utilization")
                ?.let { scoped += Scoped("Opus", it) }
        }
        return Data(session, weekly, scoped, s.cachedAt)
    }
}
