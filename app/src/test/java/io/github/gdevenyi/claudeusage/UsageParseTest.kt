package io.github.gdevenyi.claudeusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Response bodies in the shape the live endpoint returns. */
class UsageParseTest {
    private val body = """
        {
          "limits": [
            {"kind": "session", "percent": 12.5, "resets_at": "2026-10-07T17:00:00+00:00",
             "severity": "normal", "is_active": true},
            {"kind": "weekly_all", "percent": 40, "resets_at": "2026-10-12T09:00:00+00:00",
             "severity": "warning", "is_active": false},
            {"kind": "weekly_scoped", "percent": 70, "resets_at": "2026-10-10T09:00:00+00:00",
             "scope": {"model": {"display_name": "Opus"}}}
          ],
          "seven_day_breakdown": {"rows": [
            {"key": "claude_code", "display_name": "Claude Code", "percent": 62},
            {"key": "chats", "percent": 38},
            {"key": "idle", "display_name": "Idle", "percent": 0}
          ]}
        }
    """.trimIndent()

    @Test
    fun limitsCarrySeverityAndActive() {
        val d = Usage.parse(body, 0)!!
        assertEquals(12.5, d.session!!.pct, 0.0)
        assertTrue(d.session!!.active)
        assertEquals("warning", d.weekly!!.severity)
        assertEquals("Opus", d.scoped.single().name)
    }

    @Test
    fun breakdownKeepsNonZeroRowsAndFallsBackToTheKey() {
        val d = Usage.parse(body, 0)!!
        assertEquals(listOf("Claude Code", "chats"), d.breakdown.map { it.name })
        assertEquals(62.0, d.breakdown.first().pct, 0.0)
    }

    @Test
    fun noBreakdownIsEmpty() {
        assertTrue(Usage.parse("{}", 0)!!.breakdown.isEmpty())
    }
}
