package io.github.gdevenyi.claudeusage

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Usage history: one JSON line per successful fetch, every window present
 * (session, weekly, each scoped model) as fractional percent + its resets_at.
 * Samples sharing a resets_at belong to the same window cycle — that identity
 * is how the trend fit segments cycles, with no drop-detection heuristics.
 */
object History {
    private const val FILE = "usage-log.jsonl"
    const val KEEP_MS = 21L * 24 * 3600_000
    // Rewrite only once the oldest line is a day past retention: pruning
    // self-paces to roughly daily with no bookkeeping.
    private const val PRUNE_AT_MS = KEEP_MS + 24 * 3600_000
    const val SESSION_MS = 5L * 3600_000
    const val WEEKLY_MS = 7L * 24 * 3600_000

    data class Point(val t: Long, val p: Double, val r: Long)

    /**
     * resets_at comes back with per-request sub-second jitter (it can even
     * straddle a second boundary), and cycle identity is exact equality on r.
     * Rounding to the minute merges the jitter and can never merge two real
     * cycles, which sit hours or days apart. Applied on write and on read, so
     * samples logged before this fix heal too.
     */
    internal fun roundReset(ms: Long) = (ms + 30_000) / 60_000 * 60_000

    /** A confident fit. runOutAt is null when the trend never reaches 100%. */
    data class Prediction(
        val resetsAt: Long,
        val lastT: Long,
        val lastFitP: Double,
        val slopePerMs: Double,
        val runOutAt: Long?,
    ) {
        val atRisk: Boolean get() = runOutAt != null && runOutAt < resetsAt
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun append(ctx: Context, d: Usage.Data) {
        val w = JSONObject()
        fun put(name: String, win: Usage.Window?) {
            val at = win?.resetsAt ?: return // no resets_at: can't cycle it
            w.put(name, JSONObject().put("p", win.pct).put("r", roundReset(at.toEpochMilli())))
        }
        put("session", d.session)
        put("weekly", d.weekly)
        d.scoped.forEach { put(it.name, it.window) }
        if (w.length() == 0) return
        val f = file(ctx)
        f.appendText(JSONObject().put("t", d.fetchedAt).put("w", w).toString() + "\n")
        prune(f, d.fetchedAt)
    }

    private fun prune(f: File, now: Long) {
        val first = f.useLines { it.firstOrNull() } ?: return
        val oldest = runCatching { JSONObject(first).getLong("t") }.getOrNull() ?: return
        if (now - oldest < PRUNE_AT_MS) return
        val keep = f.readLines().filter {
            runCatching { JSONObject(it).getLong("t") >= now - KEEP_MS }.getOrDefault(false)
        }
        f.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n"))
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Window name -> samples, oldest first (file order is append order). */
    fun readAll(ctx: Context): Map<String, List<Point>> {
        val f = file(ctx)
        if (!f.exists()) return emptyMap()
        val out = mutableMapOf<String, MutableList<Point>>()
        f.forEachLine { line ->
            val j = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val t = j.optLong("t").takeIf { it > 0 } ?: return@forEachLine
            val w = j.optJSONObject("w") ?: return@forEachLine
            for (k in w.keys()) {
                val o = w.optJSONObject(k) ?: continue
                val r = o.optLong("r").takeIf { it > 0 } ?: continue
                out.getOrPut(k) { mutableListOf() } += Point(t, o.optDouble("p"), roundReset(r))
            }
        }
        return out
    }

    /** Window name -> confident prediction, for every logged window. */
    fun predictions(ctx: Context, now: Long = System.currentTimeMillis()): Map<String, Prediction> =
        readAll(ctx).mapNotNull { (k, pts) ->
            Trend.predict(pts, now)?.let { k to it }
        }.toMap()
}

/** Pure fit logic, kept free of Android types so a JVM test can run it. */
object Trend {
    const val STALE_MS = 60 * 60_000L
    // The forecast follows recent pace, not the whole-cycle average: fit the
    // last few samples, widened backward only until the fit spans enough
    // time to trust. A burst after a quiet stretch must move the forecast.
    private const val FIT_POINTS = 5
    private const val MIN_SPAN_MS = 45 * 60_000L

    /**
     * Least-squares line through the newest cycle's most recent samples.
     * Null unless the fit can be trusted: >= 3 samples spanning >= 45 min,
     * newest sample fresher than the stale threshold.
     */
    fun predict(points: List<History.Point>, now: Long): History.Prediction? {
        val last = points.lastOrNull() ?: return null
        if (now - last.t > STALE_MS) return null
        val cycle = points.filter { it.r == last.r }
        var from = (cycle.size - FIT_POINTS).coerceAtLeast(0)
        while (from > 0 && last.t - cycle[from].t < MIN_SPAN_MS) from--
        val fit = cycle.subList(from, cycle.size)
        if (fit.size < 3 || last.t - fit.first().t < MIN_SPAN_MS) return null
        val t0 = fit.first().t

        val n = fit.size.toDouble()
        var st = 0.0; var sp = 0.0; var stt = 0.0; var stp = 0.0
        for (c in fit) {
            val x = (c.t - t0).toDouble()
            st += x; sp += c.p; stt += x * x; stp += x * c.p
        }
        val den = n * stt - st * st
        if (den == 0.0) return null
        val slope = (n * stp - st * sp) / den
        val intercept = (sp - slope * st) / n
        val runOut = if (slope > 0) t0 + ((100.0 - intercept) / slope).toLong() else null
        return History.Prediction(
            resetsAt = last.r,
            lastT = last.t,
            lastFitP = intercept + slope * (last.t - t0),
            slopePerMs = slope,
            runOutAt = runOut,
        )
    }
}
