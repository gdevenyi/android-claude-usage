package io.github.gdevenyi.claudeusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun extra(json: String) = Usage.parse("""{"extra_usage": $json}""", 0)!!.extra

    @Test
    fun extraUsageInTheAccountCurrency() {
        val e = extra(
            """{"is_enabled": true, "monthly_limit": 5000, "used_credits": 1240,
               "currency": "EUR", "decimal_places": 2}"""
        )!!
        assertEquals("12.40 EUR / 50.00 EUR", "${e.money(e.used)} / ${e.money(e.limit)}")
        assertEquals(25, e.pct)
        assertEquals("$3", Usage.Extra(3.0, 10.0, "USD", 0, true, "").money(3.0))
    }

    @Test
    fun extraUsageShowsTheServerReasonButNotAUserChoice() {
        assertEquals(
            "out_of_credits",
            extra("""{"is_enabled": false, "monthly_limit": 5000, "disabled_reason": "out_of_credits"}""")!!
                .disabledReason,
        )
        assertNull(extra("""{"is_enabled": false, "user_disabled": true, "monthly_limit": 5000,
                             "disabled_reason": "user"}"""))
        assertNull(extra("""{"is_enabled": true, "monthly_limit": 0}"""))
    }
}
