package com.enmoble.common.firestore.util.datastruct
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A simple coroutine-friendly LRU cache with a fixed maximum size.
 *
 * - LRU = least-recently-used eviction (access-order).
 * - When inserting into a full cache, the least-recently-accessed entry is evicted.
 *
 * Thread-safety / concurrency:
 * - All operations are guarded by an internal [Mutex].
 * - This makes it safe to use from multiple coroutines.
 *
 * Implementation notes:
 * - Backed by a [LinkedHashMap] configured with `accessOrder=true`.
 * - Accessing a key via [get] updates access order, affecting eviction and MRU/LRU queries.
 *
 * @param K key type
 * @param V value type
 * @param maxSize max number of entries to retain (must be >= 1)
 */
class SimpleLRUCache<K, V>(
    private val maxSize: Int = 50,
) {
    init {
        require(maxSize >= 1) { "maxSize must be >= 1 (was $maxSize)" }
    }

    private val mutex = Mutex()

    private val cache = object : LinkedHashMap<K, V>(maxSize, 1.0f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            // Evict when size exceeds capacity.
            return size > maxSize
        }
    }

    /**
     * Returns the value for [key] or null if absent.
     *
     * Note: This updates access order (i.e., makes [key] the most recently used entry) when the key exists.
     */
    suspend fun get(key: K): V? = mutex.withLock {
        cache[key]
    }

    /**
     * Inserts or replaces [key] with [value].
     *
     * Note: This may trigger eviction if inserting causes the cache to exceed [maxSize].
     *
     * @return the previous value associated with [key], or null if none.
     */
    suspend fun put(key: K, value: V): V? = mutex.withLock {
        cache.put(key, value)
    }

    /**
     * Removes [key] from the cache.
     *
     * @return the removed value, or null if [key] was not present.
     */
    suspend fun remove(key: K): V? = mutex.withLock {
        cache.remove(key)
    }

    /** Clears all entries from the cache. */
    suspend fun clear() = mutex.withLock {
        cache.clear()
    }

    /** Returns the current number of entries. */
    suspend fun size(): Int = mutex.withLock {
        cache.size
    }

    /** Returns true if the cache has no entries. */
    suspend fun isEmpty(): Boolean = mutex.withLock {
        cache.isEmpty()
    }

    /** Returns true if [key] exists in the cache. */
    suspend fun containsKey(key: K): Boolean = mutex.withLock {
        cache.containsKey(key)
    }

    /**
     * Returns keys as a snapshot [Set].
     *
     * Iteration order is **access order** (LRU -> MRU) per [LinkedHashMap] when `accessOrder=true`.
     */
    suspend fun keys(): Set<K> = mutex.withLock {
        cache.keys.toSet()
    }

    /**
     * Returns keys in **access order** (LRU -> MRU).
     */
    suspend fun keysInAccessOrder(): List<K> = mutex.withLock {
        cache.keys.toList()
    }

    /**
     * Returns keys in MRU order (most-recent first).
     */
    suspend fun keysInMRUOrder(): List<K> = keysInAccessOrder().asReversed()

    /**
     * Returns values in access order (LRU -> MRU).
     */
    suspend fun values(): List<V> = mutex.withLock {
        cache.values.toList()
    }

    /**
     * Returns values in MRU order (most-recent first).
     */
    suspend fun valuesInMRUOrder(): List<V> = values().asReversed()

    /**
     * Returns entries in access order (LRU -> MRU).
     */
    suspend fun entries(): List<Map.Entry<K, V>> = mutex.withLock {
        cache.entries.toList()
    }

    /**
     * Returns entries in MRU order (most-recent first).
     */
    suspend fun entriesInMRUOrder(): List<Map.Entry<K, V>> = entries().asReversed()

    /**
     * Returns the most recently used entry without updating access order.
     *
     * @return Pair(key, value) for MRU entry, or null if cache is empty.
     */
    suspend fun getMostRecentlyUsed(): Pair<K, V>? = mutex.withLock {
        if (cache.isEmpty()) return@withLock null
        val lastEntry = cache.entries.lastOrNull()
        lastEntry?.let { it.key to it.value }
    }

    /**
     * Returns the least recently used entry without updating access order.
     *
     * @return Pair(key, value) for LRU entry, or null if cache is empty.
     */
    suspend fun getLeastRecentlyUsed(): Pair<K, V>? = mutex.withLock {
        if (cache.isEmpty()) return@withLock null
        val firstEntry = cache.entries.firstOrNull()
        firstEntry?.let { it.key to it.value }
    }

    /**
     * Returns the MRU key (most recently used), or null if empty.
     */
    suspend fun getMostRecentlyUsedKey(): K? = mutex.withLock {
        cache.keys.lastOrNull()
    }

    /**
     * Returns the LRU key (least recently used), or null if empty.
     */
    suspend fun getLeastRecentlyUsedKey(): K? = mutex.withLock {
        cache.keys.firstOrNull()
    }

    /**
     * Returns the MRU value (most recently used), or null if empty.
     */
    suspend fun getMostRecentlyUsedValue(): V? = mutex.withLock {
        if (cache.isEmpty()) return@withLock null
        cache.entries.lastOrNull()?.value
    }

    /**
     * Returns the LRU value (least recently used), or null if empty.
     */
    suspend fun getLeastRecentlyUsedValue(): V? = mutex.withLock {
        if (cache.isEmpty()) return@withLock null
        cache.entries.firstOrNull()?.value
    }
}