package io.github.gdevenyi.claudeusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one runnable check: segmentation, fit, and every suppression rule. */
class TrendTest {
    private val h = 3600_000L
    private val t0 = 1_000_000_000_000L

    private fun pts(r: Long, vararg tp: Pair<Long, Double>) =
        tp.map { History.Point(it.first, it.second, r) }

    @Test
    fun steadyBurnPredictsRunOutBeforeReset() {
        val reset = t0 + 5 * h
        // 30%/h from t0: hits 100 at t0+3h20m, before the reset.
        val p = Trend.predict(
            pts(reset, t0 to 0.0, t0 + h to 30.0, t0 + 2 * h to 60.0),
            now = t0 + 2 * h,
        )
        assertNotNull(p)
        assertTrue(p!!.atRisk)
        assertEquals((t0 + 10 * h / 3).toDouble(), p.runOutAt!!.toDouble(), 60_000.0)
        assertEquals(60.0, p.lastFitP, 0.01)
    }

    @Test
    fun slowBurnHasTrendButNoRisk() {
        val reset = t0 + 5 * h
        // 5%/h: 100% is 20h away, far past the reset — line shown, no warning.
        val p = Trend.predict(
            pts(reset, t0 to 0.0, t0 + h to 5.0, t0 + 2 * h to 10.0),
            now = t0 + 2 * h,
        )
        assertNotNull(p)
        assertTrue(!p!!.atRisk)
        assertNotNull(p.runOutAt)
    }

    @Test
    fun flatTrendNeverRunsOut() {
        val reset = t0 + 5 * h
        val p = Trend.predict(
            pts(reset, t0 to 40.0, t0 + h to 40.0, t0 + 2 * h to 40.0),
            now = t0 + 2 * h,
        )
        assertNotNull(p)
        assertNull(p!!.runOutAt)
        assertTrue(!p.atRisk)
    }

    @Test
    fun onlyNewestCycleIsFitted() {
        val oldReset = t0 + 5 * h
        val newReset = t0 + 10 * h
        // Steep old cycle, flat new cycle: old samples must not steepen the fit.
        val p = Trend.predict(
            pts(oldReset, t0 to 0.0, t0 + h to 50.0, t0 + 2 * h to 99.0) +
                pts(newReset, t0 + 5 * h to 1.0, t0 + 6 * h to 1.0, t0 + 7 * h to 1.0),
            now = t0 + 7 * h,
        )
        assertNotNull(p)
        assertNull(p!!.runOutAt)
    }

    @Test
    fun recentBurstMovesTheForecast() {
        val reset = t0 + 5 * h
        // Quiet for 2.5 h, then a hard burst: the whole-cycle average would
        // say safe, but the recent slope (~37%/h) runs out before the reset.
        val p = Trend.predict(
            pts(
                reset,
                t0 to 10.0, t0 + h / 2 to 10.0, t0 + h to 10.0,
                t0 + 3 * h / 2 to 10.0, t0 + 2 * h to 10.0, t0 + 5 * h / 2 to 10.0,
                t0 + 3 * h to 30.0, t0 + 7 * h / 2 to 55.0, t0 + 4 * h to 80.0,
            ),
            now = t0 + 4 * h,
        )
        assertNotNull(p)
        assertTrue(p!!.atRisk)
        assertTrue(p.runOutAt!! < reset)
    }

    @Test
    fun suppressedWhenUntrustworthy() {
        val reset = t0 + 5 * h
        val rising = pts(reset, t0 to 0.0, t0 + h to 30.0, t0 + 2 * h to 60.0)
        // Too few samples.
        assertNull(Trend.predict(rising.take(2), now = t0 + h))
        // Span under 45 min.
        assertNull(
            Trend.predict(
                pts(reset, t0 to 0.0, t0 + 10 * 60_000 to 5.0, t0 + 20 * 60_000 to 10.0),
                now = t0 + 20 * 60_000,
            )
        )
        // Stale: newest sample older than an hour.
        assertNull(Trend.predict(rising, now = t0 + 4 * h))
    }

    @Test
    fun resetJitterRoundsToOneCycle() {
        // The API recomputes resets_at per request with sub-second jitter,
        // even across a second boundary — all must key to the same cycle.
        val a = History.roundReset(1_755_162_000_217L) // ..:00.217
        val b = History.roundReset(1_755_161_999_718L) // ..59.718 the fetch before
        assertEquals(a, b)
        assertEquals(0L, a % 60_000)
    }
}
