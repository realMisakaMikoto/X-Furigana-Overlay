package com.sosdanfurigana.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JlptLevelFilterTest {
    @Test
    fun `all filter accepts every level`() {
        assertTrue(JlptLevelFilter.ALL.matches("N5"))
        assertTrue(JlptLevelFilter.ALL.matches(""))
    }

    @Test
    fun `level filters match only the selected normalized level`() {
        assertTrue(JlptLevelFilter.N5.matches(" n5 "))
        assertFalse(JlptLevelFilter.N5.matches("N4"))
        assertTrue(JlptLevelFilter.N1.matches("N1"))
    }

    @Test
    fun `unclassified filter includes blank and unknown values`() {
        assertTrue(JlptLevelFilter.UNCLASSIFIED.matches(""))
        assertTrue(JlptLevelFilter.UNCLASSIFIED.matches("unknown"))
        assertFalse(JlptLevelFilter.UNCLASSIFIED.matches("N3"))
    }
}
