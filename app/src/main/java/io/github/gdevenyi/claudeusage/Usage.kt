package io.github.gdevenyi.claudeusage

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.pow
import kotlin.math.roundToInt

/** Fetches and caches https://api.anthropic.com/api/oauth/usage. */
object Usage {
    // Required: without a claude-code User-Agent the endpoint 429s aggressively.
    private const val USER_AGENT = "claude-code/1.0.83"
    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    private const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"

    private class Unauthorized : Exception()

    // Fractional percent straight from the API: displays round, but the
    // trend fit needs the resolution (integer steps quantize early slopes).
    // severity is the server's own level for the window ("normal", ...) and
    // active marks the limit that binds right now; the legacy fields carry
    // neither.
    data class Window(
        val pct: Double,
        val resetsAt: Instant?,
        val severity: String = "",
        val active: Boolean = false,
    ) {
        val pctInt: Int get() = pct.roundToInt()
    }
    data class Scoped(val name: String, val window: Window)
    /** One product's share of the weekly usage, e.g. "Claude Code" 62%. */
    data class Share(val name: String, val pct: Double)
    /**
     * The paid overage budget. Amounts are in minor units (cents) of the
     * account's own currency. disabledReason is set when the server turned
     * it off (e.g. "out_of_credits"), not when the user did.
     */
    data class Extra(
        val used: Double,
        val limit: Double,
        val currency: String,
        val decimals: Int,
        val enabled: Boolean,
        val disabledReason: String,
    ) {
        val pct: Int get() = if (limit > 0) (used * 100 / limit).roundToInt() else 0

        fun money(minor: Double): String {
            val v = String.format(java.util.Locale.ROOT, "%.${decimals}f", minor / 10.0.pow(decimals))
            return if (currency == "USD") "$$v" else "$v $currency"
        }
    }

    data class Data(
        val session: Window?,
        val weekly: Window?,
        val scoped: List<Scoped>,
        val fetchedAt: Long,
        val breakdown: List<Share> = emptyList(),
        val extra: Extra? = null,
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
        return parse(s.cachedUsage ?: return null, s.cachedAt)
    }

    /** Pure, so a JVM test can feed it a response body. */
    fun parse(raw: String, fetchedAt: Long): Data? {
        val j = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        fun window(o: JSONObject?, pctKey: String): Window? {
            o ?: return null
            // The API sends "+00:00" offsets, which Instant.parse only accepts
            // on newer runtimes — fall back to an offset-tolerant parse.
            val resets = o.optString("resets_at").takeIf { it.isNotEmpty() }
                ?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                        ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
                }
            return Window(
                o.optDouble(pctKey, 0.0), resets, o.optString("severity"), o.optBoolean("is_active"),
            )
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
        // Server-side, so it counts the web and desktop apps too.
        val breakdown = mutableListOf<Share>()
        j.optJSONObject("seven_day_breakdown")?.optJSONArray("rows")?.let { rows ->
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                val pct = r.optDouble("percent", 0.0)
                val name = r.optString("display_name").ifEmpty { r.optString("key") }
                if (pct > 0 && name.isNotEmpty()) breakdown += Share(name, pct)
            }
        }
        // Shown while it is on, or while the server holds it off (say, out
        // of credits); a budget the user turned off themselves stays hidden.
        val extra = j.optJSONObject("extra_usage")?.let { e ->
            val limit = e.optDouble("monthly_limit", 0.0)
            val enabled = e.optBoolean("is_enabled")
            val reason = if (!enabled && !e.optBoolean("user_disabled")) {
                e.optString("disabled_reason").takeUnless { it == "null" }.orEmpty()
            } else ""
            Extra(
                used = e.optDouble("used_credits", 0.0),
                limit = limit,
                currency = e.optString("currency").ifEmpty { "USD" },
                decimals = e.optInt("decimal_places", 2),
                enabled = enabled,
                disabledReason = reason,
            ).takeIf { limit > 0 && (enabled || reason.isNotEmpty()) }
        }
        return Data(session, weekly, scoped, fetchedAt, breakdown, extra)
    }
}
