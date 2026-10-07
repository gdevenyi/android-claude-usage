package io.github.gdevenyi.claudeusage

/**
 * Traffic-light level of a window: 0 = ok, 1 = warn, 2 = crit. The percent
 * sets it; the server's own `severity` can only raise it, never lower it.
 * Kept free of Android types so a JVM test can run it.
 */
object Severity {
    fun level(pct: Int, severity: String): Int {
        val byPct = when {
            pct < 50 -> 0
            pct < 80 -> 1
            else -> 2
        }
        return maxOf(byPct, serverLevel(severity))
    }

    // Only "normal" has been seen from the API; "warning" is a guess, and any
    // other non-normal value is treated as critical.
    private fun serverLevel(severity: String) = when (severity) {
        "", "normal" -> 0
        "warning" -> 1
        else -> 2
    }

    /** The colour resource for a window, per theme via values-night. */
    fun colorRes(w: Usage.Window?): Int =
        when (level(w?.pctInt ?: 0, w?.severity.orEmpty())) {
            0 -> R.color.usage_ok
            1 -> R.color.usage_warn
            else -> R.color.usage_crit
        }
}
