package com.enmoble.common.firestore.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class StringUtilsTest {

    @Test
    fun sha256Hash_isStable() {
        val h1 = "hello".sha256Hash()
        val h2 = "hello".sha256Hash()
        val h3 = "hello2".sha256Hash()

        assertEquals(h1, h2)
        assertTrue(h1.isNotEmpty())
        assertFalse(h1 == h3)
    }

    @Test
    fun trunc_preservesEnds() {
        val s = "0xabcdefgh123456789"
        val t = s.trunc(maxLen = 12, delim = "...")
        assertEquals(12, t.length)
        assertTrue(t.startsWith("0x"))
        assertTrue(t.contains("..."))
        assertTrue(t.endsWith("789"))
    }

    @Test
    fun oneLine_removesNewlinesTabs() {
        val s = "hello\nworld\t!"
        assertEquals("helloworld!", s.oneLine(maxLen = 100))
    }

    @Test
    fun anyIsEmpty_handlesCommonTypes() {
        assertTrue((null as Any?).isEmpty())
        assertTrue(("").isEmpty())
        assertFalse(("x").isEmpty())
        assertTrue((emptyList<String>()).isEmpty())
        assertFalse((listOf("x")).isEmpty())
        assertTrue((emptyMap<String, String>()).isEmpty())
        assertFalse((mapOf("k" to "v")).isEmpty())
        assertTrue((intArrayOf()).isEmpty())
        assertFalse((intArrayOf(1)).isEmpty())
    }

    @Test
    fun iso8601TimeToMillis_parsesOrNull() {
        val ok = "2025-01-01T00:00:00Z".iso8601TimeToMillis()
        assertNotNull(ok)

        val bad = "not-a-date".iso8601TimeToMillis()
        assertNull(bad)
    }

    @Test
    fun millisConversions_roundTripLocal() {
        val zone = ZoneId.of("UTC")
        val millis = 1_700_000_000_000L
        val str = millis.millisToLocalTime(zoneId = zone)
        val parsed = str.localTimeToMillis(zoneId = zone)
        // These use millisecond precision formatting so round-trip should match.
        assertEquals(millis, parsed)
    }
}