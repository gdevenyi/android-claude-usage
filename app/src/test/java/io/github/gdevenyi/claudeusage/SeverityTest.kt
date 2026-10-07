package io.github.gdevenyi.claudeusage

import org.junit.Assert.assertEquals
import org.junit.Test

class SeverityTest {
    @Test
    fun percentSetsTheLevel() {
        assertEquals(0, Severity.level(49, ""))
        assertEquals(1, Severity.level(50, "normal"))
        assertEquals(2, Severity.level(80, "normal"))
    }

    @Test
    fun serverSeverityOnlyRaises() {
        assertEquals(1, Severity.level(10, "warning"))
        assertEquals(2, Severity.level(10, "exceeded"))
        assertEquals(2, Severity.level(90, "warning"))
    }
}
