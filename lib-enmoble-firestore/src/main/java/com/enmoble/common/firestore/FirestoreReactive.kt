package com.enmoble.common.firestore

import android.util.Log
import com.enmoble.common.firestore.util.CollectionUtils.decrementRefCountOrRemoveItem
import com.enmoble.common.firestore.util.CollectionUtils.incrementRefCountOrAddItem
import com.enmoble.common.firestore.util.RefreshTimesManager
import com.enmoble.common.firestore.util.logException
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * Generic reactive utilities for Firebase Firestore reads.
 *
 * This provides:
 * - **One-shot** (cold) flows for reading a snapshot of a collection/query once.
 * - **Listener-based** flows for receiving updates via Firestore snapshot listeners.
 * - **Polling-based** flows that periodically poll the local cache and only occasionally refresh from the server to
 *   reduce Firestore read quota usage.
 *
 * Design notes:
 * - The API emits `Pair<T, String>` where the second value is an error label string:
 *   - `""` means success.
 *   - Non-empty means deserialization or listener error. When [continueOnError] is true, a default instance of `T`
 *     is emitted alongside the error string.
 * - Flow instances are cached by a computed “query key” so multiple subscribers can share the same underlying listener.
 *
 * Concurrency notes:
 * - Subscriber is responsible for switching to the main thread for UI work.
 * - Snapshot listener callbacks must do minimal work; this code only deserializes and emits.
 */
object FirestoreReactive : AutoCloseable {

    /**
     * Local cache polling interval (ms) used by polling flows.
     */
    const val POLLING_FLOWS_CACHE_REFRESH_INTERVAL_MS: Long = 10_000L

    /**
     * Server refresh interval (ms) used by polling flows.
     *
     * Set to `0` to disable server refresh completely.
     */
    const val POLLING_FLOWS_SERVER_REFRESH_INTERVAL_MS: Long = 2 * 60 * 60 * 1000L // 2 hours

    /** Suffix used for default (non-query) listeners on a path. */
    const val QUERY_DEFAULT: String = "DEFAULT"

    /** Label prefix used for deleted document emissions (only when enabled). */
    const val LABEL_DELETED_DOCID: String = "DELETED_DOC_ID"

    /**
     * Replay cache size used by polling/streaming flows.
     *
     * This caps in-memory cached items kept for re-subscriptions.
     */
    const val REPLAY_CACHE_SIZE: Int = 1024

    private const val LOGTAG = "FirestoreReactive"
    private const val DEFAULT_BUFFERED_ITEMS_CNT: Int = 128
    private const val KEY_QUERY_LISTENER_POLLTIMES = "QUERY_LISTENER_POLLTIMES"

    /**
     * Optional preference-backed store used to track last server refresh time per queryPath.
     *
     * Requires [`FirestoreManager.initFirestore()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:71)
     * to have been called first so [FirestoreManager.appContext] is initialized.
     */
    private val pollTimeDataPrefs by lazy {
        RefreshTimesManager(FirestoreManager.appContext)
    }

    /**
     * Cache of active listener flows for “single item” emissions: `queryKey -> Flow<Pair<Any, String>>`.
     */
    private val activeQueryPathFlows: LinkedHashMap<String, Flow<Pair<Any, String>>> = linkedMapOf()

    /**
     * Reference counts for [activeQueryPathFlows].
     */
    private val activeQueryPathFlowsRefCount: LinkedHashMap<String, Int> = linkedMapOf()

    /**
     * Cache of active listener flows for “list of items” emissions: `queryKey -> Flow<List<Pair<Any, String>>>`.
     */
    private val activeQueryPathListFlows: LinkedHashMap<String, Flow<List<Pair<Any, String>>>> = linkedMapOf()

    /**
     * Reference counts for [activeQueryPathListFlows].
     */
    private val activeQueryPathListFlowsRefCount: LinkedHashMap<String, Int> = linkedMapOf()

    /**
     * Lock guarding access to the flow caches / refCounts.
     *
     * NOTE: A JVM lock is sufficient because these APIs are not expected to be called at extremely high frequency and
     * we intentionally keep the implementation simple.
     */
    private val queryMapMutex = ReentrantLock()

    /**
     * Returns a **cold** flow that emits all documents from a single Firestore snapshot (query or collection read).
     *
     * This is effectively:
     * - read snapshot once
     * - deserialize into `T`
     * - emit items and complete
     *
     * @param query Firestore query to execute. If null, [collectionPath] is read directly.
     * @param queryPath A stable identifier for caching/logging (use [queryKey]).
     * @param collectionPath Collection path used when [query] is null.
     * @param clazz Target class to deserialize into. Must have a public no-arg constructor if [continueOnError] is true.
     * @param converter Optional custom converter for deserialization.
     * @param argToConverter Optional argument forwarded to [converter].
     * @param continueOnError If true, emit default `T()` with an error label instead of throwing.
     * @param localCacheOnly If true, read only from local cache.
     */
    fun <T : Any> oneShotCollectionQueryFlow(
        query: Query?,
        queryPath: String,
        collectionPath: String,
        clazz: Class<T>,
        converter: ((DocumentSnapshot, String, Any?) -> Pair<T, String>)? = null,
        argToConverter: Any? = null,
        continueOnError: Boolean = true,
        localCacheOnly: Boolean = true,
    ): Flow<Pair<T, String>> {
        return oneShotCollectionQueryListFlow(
            query = query,
            queryPath = queryPath,
            collectionPath = collectionPath,
            clazz = clazz,
            converter = converter,
            argToConverter = argToConverter,
            continueOnError = continueOnError,
            localCacheOnly = localCacheOnly,
        )
            .flatMapConcat { it.asFlow() }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Returns a **cold** flow that emits a single list containing all documents from one snapshot.
     *
     * See also: [`oneShotCollectionQueryFlow`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt:103).
     */
    fun <T : Any> oneShotCollectionQueryListFlow(
        query: Query?,
        queryPath: String,
        collectionPath: String,
        clazz: Class<T>,
        converter: ((DocumentSnapshot, String, Any?) -> Pair<T, String>)? = null,
        argToConverter: Any? = null,
        continueOnError: Boolean = true,
        localCacheOnly: Boolean = true,
    ): Flow<List<Pair<T, String>>> = flow {
        val emitList: MutableList<Pair<T, String>> = mutableListOf()

        try {
            val snapshot: QuerySnapshot = if (query != null) {
                Log.d(LOGTAG, "oneShotCollectionQueryListFlow(): queryPath=[$queryPath] -> running QUERY [cacheOnly=$localCacheOnly]")
                query.get(if (localCacheOnly) Source.CACHE else Source.DEFAULT).await()
            } else {
                Log.d(LOGTAG, "oneShotCollectionQueryListFlow(): queryPath=[$queryPath] -> reading COLLECTION [cacheOnly=$localCacheOnly]")
                Firebase.firestore.collection(collectionPath).get(if (localCacheOnly) Source.CACHE else Source.DEFAULT).await()
            }

            if (snapshot.isEmpty) {
                Log.w(LOGTAG, "oneShotCollectionQueryListFlow(): queryPath=[$queryPath] -> EMPTY snapshot")
                return@flow
            }

            for (doc in snapshot.documents) {
                try {
                    val emitItem = if (converter != null) converter(doc, collectionPath, argToConverter)
                    else Pair(doc.toObject(clazz)!!, "")
                    emitList.add(emitItem)
                } catch (e: Exception) {
                    logException(LOGTAG, e, "oneShotCollectionQueryListFlow()", "Deserialization failed")
                    if (continueOnError) {
                        emitList.add(Pair(clazz.getDeclaredConstructor().newInstance(), e.toString()))
                    } else {
                        throw e
                    }
                }
            }
        } catch (e: Exception) {
            logException(LOGTAG, e, "oneShotCollectionQueryListFlow()", "Snapshot read failed for queryPath=[$queryPath]")
            if (!continueOnError) throw e
        }

        emit(emitList)
    }.flowOn(Dispatchers.IO)

    /**
     * Returns a cached Flow that listens for changes on [query].
     *
     * The returned flow is cached by [queryPath]. Subsequent callers with the same [queryPath] share the same underlying
     * listener and ref-counting determines when resources are released.
     *
     * @param query Firestore query to listen to.
     * @param queryPath Stable query key (recommended: [queryKey]).
     * @param nodePath Underlying node path (for logging only).
     * @param clazz Target class to deserialize into. Must have a public no-arg constructor if [continueOnError] is true.
     * @param converter Optional custom converter.
     * @param argToConverter Optional argument forwarded to [converter].
     * @param usePolling If true, uses polling-based implementation (cache poll + occasional server refresh).
     * @param localPollInterval Cache poll interval in ms.
     * @param serverPollInterval Server refresh interval in ms (0 disables server refresh).
     * @param emitDeletes If true, emits default item with label `DELETED_DOC_ID:<id>` for deletes (polling list flow).
     * @param danglingRef If true, keeps flow cached even when refCnt becomes 0 (must call [garbageCollect]).
     * @param continueOnError If true, emit default instances on errors; otherwise throw.
     * @param bufferItemsCnt Replay/buffer size for listener flows.
     */
    internal fun <T : Any> collectionQueryListenerFlow(
        query: Query,
        queryPath: String,
        nodePath: String,
        clazz: Class<T>,
        converter: ((DocumentSnapshot, String, Any?) -> Pair<T, String>)? = null,
        argToConverter: Any? = null,
        usePolling: Boolean = true,
        localPollInterval: Long = POLLING_FLOWS_CACHE_REFRESH_INTERVAL_MS,
        serverPollInterval: Long = POLLING_FLOWS_SERVER_REFRESH_INTERVAL_MS,
        emitDeletes: Boolean = false,
        danglingRef: Boolean = false,
        continueOnError: Boolean = true,
        bufferItemsCnt: Int = DEFAULT_BUFFERED_ITEMS_CNT,
    ): Flow<Pair<T, String>> {
        return synchronized(queryMapMutex) {
            @Suppress("UNCHECKED_CAST")
            val flow: Flow<Pair<T, String>> =
                if (activeQueryPathFlows.containsKey(queryPath)) {
                    Log.d(LOGTAG, "collectionQueryListenerFlow(): queryPath=[$queryPath] -> returning CACHED flow")
                    activeQueryPathFlows[queryPath] as Flow<Pair<T, String>>
                } else {
                    Log.d(LOGTAG, "collectionQueryListenerFlow(): queryPath=[$queryPath] -> creating NEW flow (polling=$usePolling)")
                    if (usePolling) {
                        _pollingCollectionQueryListenerFlow(
                            query = query,
                            queryPath = queryPath,
                            collectionPath = nodePath,
                            clazz = clazz,
                            converter = converter,
                            argToConverter = argToConverter,
                            localPollInterval = localPollInterval,
                            serverPollInterval = serverPollInterval,
                            danglingRef = danglingRef,
                            emitDeletes = emitDeletes,
                            continueOnError = continueOnError,
                            replayCacheSize = bufferItemsCnt,
                        )
                    } else {
                        _collectionQueryListenerFlow(
                            query = query,
                            queryPath = queryPath,
                            collectionPath = nodePath,
                            clazz = clazz,
                            converter = converter,
                            argToConverter = argToConverter,
                            danglingRef = danglingRef,
                            continueOnError = continueOnError,
                            bufferItemsCnt = bufferItemsCnt,
                        )
                    }
                }

            activeQueryPathFlows[queryPath] = flow as Flow<Pair<Any, String>>
            flow
        }
    }

    /**
     * Live snapshot-listener flow implementation.
     *
     * Warning: This uses Firestore snapshot listeners, which can consume read quota depending on usage.
     */
    internal fun <T : Any> _collectionQueryListenerFlow(
        query: Query,
        queryPath: String,
        collectionPath: String,
        clazz: Class<T>,
        converter: ((DocumentSnapshot, String, Any?) -> Pair<T, String>)?,
        argToConverter: Any?,
        danglingRef: Boolean,
        continueOnError: Boolean = true,
        bufferItemsCnt: Int = DEFAULT_BUFFERED_ITEMS_CNT,
    ): Flow<Pair<T, String>> {
        val replayFlow = MutableSharedFlow<Pair<T, String>>(
            replay = bufferItemsCnt,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val registration = query.addSnapshotListener { querySnapshot, exception ->
            try {
                if (exception != null) throw exception
                querySnapshot?.let { snapshot ->
                    if (snapshot.isEmpty) {
                        throw IllegalStateException(
                            "_collectionQueryListenerFlow(): listener attached to empty/non-existing collectionPath=[$collectionPath]",
                        )
                    }

                    for (doc in snapshot.documents) {
                        try {
                            val emitItem = if (converter != null) converter(doc, collectionPath, argToConverter)
                            else Pair(doc.toObject(clazz)!!, "")
                            replayFlow.tryEmit(emitItem)
                        } catch (e: Exception) {
                            logException(LOGTAG, e, "_collectionQueryListenerFlow()", "Deserialization error")
                            if (continueOnError) {
                                replayFlow.tryEmit(Pair(clazz.getDeclaredConstructor().newInstance(), e.toString()))
                            } else {
                                throw e
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logException(LOGTAG, e, "_collectionQueryListenerFlow()", "Listener callback failed for queryPath=[$queryPath]")
                if (!continueOnError) {
                    // Cancel downstream collection if desired.
                    scope.cancel("Snapshot listener failed", e)
                }
            }
        }

        return replayFlow
            .onSubscription {
                synchronized(queryMapMutex) {
                    incrementRefCountOrAddItem(queryPath, replayFlow, activeQueryPathFlows, activeQueryPathFlowsRefCount)
                }
                Log.d(LOGTAG, "_collectionQueryListenerFlow(): queryPath=[$queryPath] -> subscriber added")
            }
            .onCompletion {
                val finished = synchronized(queryMapMutex) {
                    decrementRefCountOrRemoveItem(queryPath, activeQueryPathFlows, activeQueryPathFlowsRefCount, danglingRef)
                }
                if (finished) {
                    registration.remove()
                    replayFlow.resetReplayCache()
                    Log.d(LOGTAG, "_collectionQueryListenerFlow(): queryPath=[$queryPath] -> cleaned up listener")
                } else {
                    Log.d(LOGTAG, "_collectionQueryListenerFlow(): queryPath=[$queryPath] -> dangling reference retained")
                }
            }
            .buffer(bufferItemsCnt)
            .flowOn(Dispatchers.IO)
    }

    /**
     * Polling-based flow that converts list-emitting polling flow into item-emitting flow with replay.
     */
    internal fun <T : Any> _pollingCollectionQueryListenerFlow(
        query: Query,
        queryPath: String,
        collectionPath: String,
        clazz: Class<T>,
        converter: ((DocumentSnapshot, String, Any?) -> Pair<T, String>)?,
        argToConverter: Any?,
        localPollInterval: Long,
        serverPollInterval: Long,
        danglingRef: Boolean = false,
        emitDeletes: Boolean = false,
        continueOnError: Boolean = true,
        replayCacheSize: Int = REPLAY_CACHE_SIZE,
    ): Flow<Pair<T, String>> {
        val replayFlow = MutableSharedFlow<Pair<T, String>>(replay = replayCacheSize)
        val collectJobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val collectJobRef = AtomicReference<Job?>(null)

        val itemsListFlow = pollingCollectionQueryListenerListFlow(
            query = query,
            queryPath = queryPath,
            collectionPath = collectionPath,
            clazz = clazz,
            converter = converter,
            argToConverter = argToConverter,
            localPollInterval = localPollInterval,
            serverPollInterval = serverPollInterval,
            danglingRef = danglingRef,
            emitDeletes = emitDeletes,
            continueOnError = continueOnError,
        )

        fun startCollectingFromListFlow() {
            val job = collectJobScope.launch {
                itemsListFlow.collect { itemsList ->
                    for (item in itemsList) replayFlow.tryEmit(item)
                }
            }
            collectJobRef.set(job)
        }

        return replayFlow
            .onSubscription {
                var logSuffix = ""
                synchronized(queryMapMutex) {
                    val refCnt = incrementRefCountOrAddItem(queryPath, replayFlow, activeQueryPathFlows, activeQueryPathFlowsRefCount)
                    if (refCnt == 1) {
                        startCollectingFromListFlow()
                        logSuffix = " (collector started)"
                    }
                }
                Log.d(LOGTAG, "_pollingCollectionQueryListenerFlow(): queryPath=[$queryPath] -> subscriber added$logSuffix")
            }
            .onCompletion {
                synchronized(queryMapMutex) {
                    val finished = decrementRefCountOrRemoveItem(queryPath, activeQueryPathFlows, activeQueryPathFlowsRefCount, danglingRef)
                    if (finished) {
                        replayFlow.resetReplayCache()
                        collectJobScope.cancel()
                        Log.d(LOGTAG, "_pollingCollectionQueryListenerFlow(): queryPath=[$queryPath] -> cleanup (scope cancelled)")
                    } else {
                        collectJobRef.getAndSet(null)?.cancel()
                        Log.d(LOGTAG, "_pollingCollectionQueryListenerFlow(): queryPath=[$queryPath] -> dangling flow retained (collector cancelled)")
                    }
                }
            }
    }

    /**
     * Polling-based flow that emits a **list of items** representing the current snapshot, or only diffs depending on
     * [emitDiffListOnly].
     *
     * Strategy:
     * - Poll local cache periodically.
     * - Optionally force refresh from server on a slower cadence (quota optimization).
     * - Emit either the diff list or full list.
     *
     * @param emitDiffListOnly If true, after the initial snapshot, only changed items are emitted.
     */
    internal fun <T : Any> pollingCollectionQueryListenerListFlow(
        query: Query,
        queryPath: String,
        collectionPath: String,
        clazz: Class<T>,
        converter: ((DocumentSnapshot, String, Any?) -> Pair<T, String>)? = null,
        argToConverter: Any? = null,
        localPollInterval: Long = POLLING_FLOWS_CACHE_REFRESH_INTERVAL_MS,
        serverPollInterval: Long = POLLING_FLOWS_SERVER_REFRESH_INTERVAL_MS,
        emitDiffListOnly: Boolean = true,
        danglingRef: Boolean = false,
        emitDeletes: Boolean = false,
        continueOnError: Boolean = true,
    ): Flow<List<Pair<T, String>>> {
        val fullEmitListRef = AtomicReference<List<Pair<T, String>>>(emptyList())
        val lastMapRef = AtomicReference<Map<String, Pair<T, String>>>(emptyMap())
        val sharedFlow = MutableSharedFlow<List<Pair<T, String>>>()

        fun deserialize(doc: DocumentSnapshot): Pair<T, String> {
            return try {
                if (converter != null) converter(doc, collectionPath, argToConverter)
                else Pair(doc.toObject(clazz)!!, "")
            } catch (e: Exception) {
                logException(LOGTAG, e, "pollingCollectionQueryListenerListFlow()", "Deserialization error")
                if (continueOnError) Pair(clazz.getDeclaredConstructor().newInstance(), e.toString()) else throw e
            }
        }

        suspend fun pollCacheAndEmitDiff() {
            val snap = query.get(Source.CACHE).await()
            if (snap.isEmpty) {
                Log.w(LOGTAG, "pollingCollectionQueryListenerListFlow(): EMPTY cache snapshot for queryPath=[$queryPath]")
                return
            }

            val lastMap = lastMapRef.get()
            val currMap = mutableMapOf<String, Pair<T, String>>()
            val emitList = ArrayList<Pair<T, String>>()

            for (doc in snap.documents) {
                val pair = deserialize(doc)
                val id = doc.id
                currMap[id] = pair

                val prevItem = lastMap[id]?.first
                if (!emitDiffListOnly || (prevItem == null || prevItem != pair.first)) {
                    emitList += pair
                }
            }

            if (emitDeletes) {
                for (removedId in lastMap.keys - currMap.keys) {
                    emitList += Pair(clazz.getDeclaredConstructor().newInstance(), "$LABEL_DELETED_DOCID:$removedId")
                }
            }

            lastMapRef.set(currMap.toMap())

            if (emitList.isNotEmpty()) {
                if (emitDiffListOnly) {
                    fullEmitListRef.compareAndSet(emptyList(), emitList)
                } else {
                    fullEmitListRef.set(emitList)
                }
                sharedFlow.emit(emitList)
            }
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val localJob = scope.launch {
            while (isActive) {
                try {
                    pollCacheAndEmitDiff()
                } catch (e: Exception) {
                    logException(LOGTAG, e, "pollingCollectionQueryListenerListFlow()", "Cache poll failed for queryPath=[$queryPath]")
                    if (!continueOnError) throw e
                }
                delay(localPollInterval)
            }
        }

        val netJob = if (serverPollInterval > 0) {
            scope.launch {
                while (isActive) {
                    val lastPoll = pollTimeDataPrefs.getLastRefreshTime(KEY_QUERY_LISTENER_POLLTIMES, queryPath) ?: 0L
                    val now = System.currentTimeMillis()
                    if (now - lastPoll >= serverPollInterval) {
                        try {
                            // Force a server fetch to update cache
                            query.get(Source.SERVER).await()
                            pollTimeDataPrefs.updateRefreshTime(KEY_QUERY_LISTENER_POLLTIMES, queryPath, now)
                        } catch (e: Exception) {
                            logException(LOGTAG, e, "pollingCollectionQueryListenerListFlow()", "Server refresh failed for queryPath=[$queryPath]")
                            if (!continueOnError) throw e
                        }
                    }
                    delay(serverPollInterval)
                }
            }
        } else null

        return sharedFlow
            .onStart {
                synchronized(queryMapMutex) {
                    incrementRefCountOrAddItem(queryPath, sharedFlow, activeQueryPathListFlows, activeQueryPathListFlowsRefCount)
                }
                val snapshot = fullEmitListRef.get()
                if (snapshot.isNotEmpty()) emit(snapshot)
            }
            .onCompletion {
                val finished = synchronized(queryMapMutex) {
                    decrementRefCountOrRemoveItem(queryPath, activeQueryPathListFlows, activeQueryPathListFlowsRefCount, danglingRef)
                }
                if (finished) {
                    localJob.cancel()
                    netJob?.cancel()
                    Log.d(LOGTAG, "pollingCollectionQueryListenerListFlow(): queryPath=[$queryPath] -> cancelled poll jobs")
                }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Removes cached flows whose reference count is zero.
     *
     * This is only required when callers used [danglingRef] = true.
     */
    fun garbageCollect() {
        for ((query, cnt) in activeQueryPathFlowsRefCount.toMap()) {
            if (cnt == 0) {
                activeQueryPathFlows.remove(query)
                activeQueryPathFlowsRefCount.remove(query)
            }
        }
        for ((query, cnt) in activeQueryPathListFlowsRefCount.toMap()) {
            if (cnt == 0) {
                activeQueryPathListFlows.remove(query)
                activeQueryPathListFlowsRefCount.remove(query)
            }
        }
    }

    /**
     * Clears all internal caches (does not affect Firestore itself).
     */
    override fun close() {
        Log.d(LOGTAG, "close(): clearing cached flows")
        activeQueryPathFlows.clear()
        activeQueryPathFlowsRefCount.clear()
        activeQueryPathListFlows.clear()
        activeQueryPathListFlowsRefCount.clear()
    }

    /**
     * Computes a stable cache key for a listener.
     *
     * For a default (non-query) listener on a node path, pass `queryName = null` and the key becomes:
     * - `"$nodePath/DEFAULT"`
     *
     * @param nodePath Firestore path being listened to.
     * @param queryName Optional unique name for a specific query on the same path.
     */
    fun queryKey(nodePath: String, queryName: String?): String =
        if (queryName == null) "$nodePath/$QUERY_DEFAULT" else "$nodePath/$queryName"
}