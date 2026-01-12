package com.enmoble.common.firestore.util

import android.util.Log

/**
 * Generic collection utilities (non-Firestore specific).
 *
 */
object CollectionUtils {
    private const val LOGTAG = "#CollectionUtils"

    /**
     * Updates [itemsMap] and [refCntMap] for the given [itemKey] by applying a ref-count [change].
     *
     * Typical usage: caching objects (e.g., listener flows) where multiple callers share the same instance and you want to
     * retain the cached instance until the ref-count reaches zero.
     *
     * Behavior:
     * - If `currCnt + change <= 0`:
     *   - If [dontRemoveOnZeroRefCnt] is false => remove entry from both maps (returns true).
     *   - If [dontRemoveOnZeroRefCnt] is true  => keep entry, but set refCnt to 0 (returns false).
     * - If `currCnt + change == 1` => add [newValue] to [itemsMap] and set refCnt to 1.
     * - Else => update refCnt to `currCnt + change`.
     *
     * @param itemKey Key to update.
     * @param refCntMap Map of key -> ref count.
     * @param change Delta to apply (usually +1 or -1).
     * @param itemsMap Map of key -> cached value.
     * @param newValue Value to add when creating a new entry (required when change takes refCnt from 0 -> 1).
     * @param dontRemoveOnZeroRefCnt If true, do not remove cached entry when refCnt becomes 0.
     * @return true if the entry was removed, false otherwise.
     */
    fun <V> updateItemsMapAndRefCount(
        itemKey: String,
        refCntMap: MutableMap<String, Int>,
        change: Int,
        itemsMap: MutableMap<String, V>,
        newValue: V?,
        dontRemoveOnZeroRefCnt: Boolean,
    ): Boolean {
        val currCnt = refCntMap[itemKey] ?: 0
        val nextCnt = currCnt + change

        return if (nextCnt <= 0) {
            Log.d(
                LOGTAG,
                "updateItemsMapAndRefCount(): itemKey=[$itemKey], currCnt=[$currCnt], change=[$change], " +
                    "dontRemoveOnZeroRefCnt=[$dontRemoveOnZeroRefCnt] -> " +
                    if (dontRemoveOnZeroRefCnt) "KEEP cached (refCnt=0)" else "REMOVE cached entry",
            )

            if (!dontRemoveOnZeroRefCnt) {
                itemsMap.remove(itemKey)
                refCntMap.remove(itemKey)
                true
            } else {
                refCntMap[itemKey] = 0
                false
            }
        } else if (nextCnt == 1) {
            Log.d(
                LOGTAG,
                "updateItemsMapAndRefCount(): itemKey=[$itemKey], currCnt=[$currCnt], change=[$change] -> ADD new entry",
            )
            requireNotNull(newValue) { "newValue must be non-null when adding a new entry (refCnt 0 -> 1)" }
            itemsMap[itemKey] = newValue
            refCntMap[itemKey] = 1
            false
        } else {
            Log.d(
                LOGTAG,
                "updateItemsMapAndRefCount(): itemKey=[$itemKey], currCnt=[$currCnt], change=[$change] -> UPDATE refCnt",
            )
            refCntMap[itemKey] = nextCnt
            false
        }
    }

    /**
     * Increments ref-count for [itemKey], adding [newValue] if this is the first reference.
     *
     * @return the updated ref-count.
     */
    fun <V> incrementRefCountOrAddItem(
        itemKey: String,
        newValue: V,
        itemsMap: MutableMap<String, V>,
        refCntMap: MutableMap<String, Int>,
    ): Int {
        updateItemsMapAndRefCount(
            itemKey = itemKey,
            refCntMap = refCntMap,
            change = 1,
            itemsMap = itemsMap,
            newValue = newValue,
            dontRemoveOnZeroRefCnt = false,
        )
        return refCntMap[itemKey] ?: 0
    }

    /**
     * Decrements ref-count for [itemKey], optionally keeping the entry cached even at refCnt==0.
     *
     * @param dontRemoveOnZeroRefCnt If true, entry remains cached at refCnt==0 and must be garbage-collected later.
     * @return true if the entry was removed, false otherwise.
     */
    fun <V> decrementRefCountOrRemoveItem(
        itemKey: String,
        itemsMap: MutableMap<String, V>,
        refCntMap: MutableMap<String, Int>,
        dontRemoveOnZeroRefCnt: Boolean,
    ): Boolean = updateItemsMapAndRefCount(
        itemKey = itemKey,
        refCntMap = refCntMap,
        change = -1,
        itemsMap = itemsMap,
        newValue = null,
        dontRemoveOnZeroRefCnt = dontRemoveOnZeroRefCnt,
    )

    /**
     * Returns a sub-list of size [count] starting at index [from].
     *
     * Performance note: this allocates a new list (shallow copy of references).
     */
    fun <T> List<T>.take(from: Int, count: Int): List<T> =
        drop(from).take(count)
}