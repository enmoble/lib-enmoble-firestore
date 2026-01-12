package com.enmoble.common.firestore

import org.junit.Assert.assertEquals
import org.junit.Test

class FirestoreReactiveTest {

    @Test
    fun queryKey_withNullQueryName_usesDefaultSuffix() {
        val key = FirestoreReactive.queryKey("root/path", null)
        assertEquals("root/path/${FirestoreReactive.QUERY_DEFAULT}", key)
    }

    @Test
    fun queryKey_withQueryName_appendsQueryName() {
        val key = FirestoreReactive.queryKey("root/path", "MY_QUERY")
        assertEquals("root/path/MY_QUERY", key)
    }

    @Test
    fun queryKey_preservesTrailingSlashBehavior() {
        // This test documents current behavior (caller should pass normalized paths).
        val key = FirestoreReactive.queryKey("root/path/", null)
        assertEquals("root/path//${FirestoreReactive.QUERY_DEFAULT}", key)
    }
}