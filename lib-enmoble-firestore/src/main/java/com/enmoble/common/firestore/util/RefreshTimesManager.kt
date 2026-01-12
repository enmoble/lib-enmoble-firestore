package com.enmoble.common.firestore.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedHashMap

/**
 * Lightweight persistence helper for storing and retrieving a `LinkedHashMap<String, Long>` in
 * AndroidX DataStore (Preferences).
 *
 * This is used by polling-based Firestore listeners to remember the last "server refresh" time per query key.
 *
 * Thread-safety:
 * - All read/modify/write operations are guarded by a global [mutex].
 */
class RefreshTimesManager(private val context: Context) {

    private val Context.dataPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

    private companion object {
        private val gson = Gson()
        private val mutex = Mutex()
    }

    /**
     * Stores a map of refresh times under [prefsKey].
     *
     * The map is sorted by timestamp (descending) before persisting.
     *
     * @param prefsKey DataStore preference key name.
     * @param data Map where values are timestamps in epoch milliseconds.
     */
    suspend fun setLastRefreshTimes(prefsKey: String, data: Map<String, Long>) {
        val key = stringPreferencesKey(prefsKey)

        val sortedMap = data.toList()
            .sortedByDescending { it.second }
            .toMap()
            .let { LinkedHashMap(it) }

        val jsonString = gson.toJson(sortedMap)

        context.dataPreferencesStore.edit { prefs ->
            prefs[key] = jsonString
        }
    }

    /**
     * Reads the stored refresh-time map for [prefsKey].
     *
     * @param prefsKey DataStore preference key name.
     * @return LinkedHashMap sorted by timestamp (descending). Empty if missing/corrupt.
     */
    suspend fun getLastRefreshTimes(prefsKey: String): LinkedHashMap<String, Long> {
        val key = stringPreferencesKey(prefsKey)

        return try {
            val prefs = context.dataPreferencesStore.data.first()
            val jsonString = prefs[key]

            if (jsonString.isNullOrEmpty()) return LinkedHashMap()

            val type = object : TypeToken<LinkedHashMap<String, Long>>() {}.type
            val deserialized: LinkedHashMap<String, Long> = gson.fromJson(jsonString, type)

            deserialized.toList()
                .sortedByDescending { it.second }
                .toMap()
                .let { LinkedHashMap(it) }
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    /**
     * Gets the last refresh time for a specific [itemKey] inside the group [prefsKey].
     *
     * @return timestamp in epoch millis, or null if not found.
     */
    suspend fun getLastRefreshTime(prefsKey: String, itemKey: String): Long? = mutex.withLock {
        getLastRefreshTimes(prefsKey)[itemKey]
    }

    /**
     * Updates (or adds) the refresh time for [itemKey] inside group [prefsKey].
     *
     * @param timestamp epoch millis.
     */
    suspend fun updateRefreshTime(prefsKey: String, itemKey: String, timestamp: Long) = mutex.withLock {
        val currentMap = getLastRefreshTimes(prefsKey).toMutableMap()
        currentMap[itemKey] = timestamp
        setLastRefreshTimes(prefsKey, currentMap)
    }

    /**
     * Removes [itemKey] from group [prefsKey].
     */
    suspend fun removeRefreshTime(prefsKey: String, itemKey: String) = mutex.withLock {
        val currentMap = getLastRefreshTimes(prefsKey).toMutableMap()
        currentMap.remove(itemKey)
        setLastRefreshTimes(prefsKey, currentMap)
    }

    /**
     * Clears the entire group [prefsKey].
     */
    suspend fun clearRefreshTimes(prefsKey: String) = mutex.withLock {
        val key = stringPreferencesKey(prefsKey)
        context.dataPreferencesStore.edit { prefs ->
            prefs.remove(key)
        }
    }
}