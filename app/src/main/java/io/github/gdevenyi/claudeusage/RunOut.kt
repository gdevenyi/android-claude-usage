package io.github.gdevenyi.claudeusage

/**
 * Which forecasts deserve an audible alert. Kept free of Android types so a
 * JVM test can run it.
 */
object RunOut {
    /** A run-out this close to the reset costs almost nothing: stay quiet. */
    const val MIN_LEAD_MS = 15 * 60_000L

    /** Identifies one window's cycle; reset times are already rounded. */
    fun key(name: String, p: History.Prediction) = "$name@${p.resetsAt}"

    /**
     * At-risk windows not yet alerted this cycle. One alert per cycle, even
     * if the risk goes away and comes back: pace jitters from poll to poll.
     */
    fun due(
        preds: Map<String, History.Prediction>,
        alerted: Set<String>,
        now: Long,
    ): Map<String, History.Prediction> = preds.filter { (name, p) ->
        val out = p.runOutAt ?: return@filter false
        p.atRisk && out > now && p.resetsAt - out >= MIN_LEAD_MS && key(name, p) !in alerted
    }

    /** Drop keys of cycles that have already reset. */
    fun prune(alerted: Set<String>, now: Long): Set<String> =
        alerted.filterTo(mutableSetOf()) { it.substringAfterLast('@').toLongOrNull()?.let { r -> r > now } == true }
}
