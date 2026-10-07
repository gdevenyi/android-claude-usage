package io.github.gdevenyi.claudeusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FmtTest {
    private val weekly = Instant.parse("2026-10-12T09:00:00Z")

    @Test
    fun modelResetWithinAMinuteIsTheWeeklyOne() {
        assertNull(Fmt.ownReset(weekly.plusSeconds(40), weekly))
        assertNull(Fmt.ownReset(null, weekly))
    }

    @Test
    fun modelResetApartFromWeeklyIsShown() {
        val own = weekly.minusSeconds(3 * 86400)
        assertEquals(own, Fmt.ownReset(own, weekly))
        assertEquals(own, Fmt.ownReset(own, null))
    }
}
