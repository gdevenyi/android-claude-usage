package io.github.gdevenyi.claudeusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunOutTest {
    private val m = 60_000L
    private val now = 1_000_000_000_000L
    private val reset = now + 120 * m

    private fun pred(runOut: Long?) = History.Prediction(reset, now, 50.0, 0.0, runOut)

    @Test
    fun atRiskWindowIsDueOnce() {
        val preds = mapOf("session" to pred(now + 60 * m))
        assertEquals(setOf("session"), RunOut.due(preds, emptySet(), now).keys)
        val alerted = setOf(RunOut.key("session", preds.getValue("session")))
        assertTrue(RunOut.due(preds, alerted, now).isEmpty())
    }

    @Test
    fun runOutCloseToTheResetIsQuiet() {
        assertTrue(RunOut.due(mapOf("session" to pred(reset - 14 * m)), emptySet(), now).isEmpty())
        assertEquals(1, RunOut.due(mapOf("session" to pred(reset - 15 * m)), emptySet(), now).size)
    }

    @Test
    fun noRiskOrPastRunOutIsQuiet() {
        val preds = mapOf("a" to pred(null), "b" to pred(reset + m), "c" to pred(now - m))
        assertTrue(RunOut.due(preds, emptySet(), now).isEmpty())
    }

    @Test
    fun pruneDropsCyclesThatReset() {
        val kept = "weekly@${now + m}"
        assertEquals(setOf(kept), RunOut.prune(setOf("session@${now - m}", kept, "junk"), now))
    }
}
