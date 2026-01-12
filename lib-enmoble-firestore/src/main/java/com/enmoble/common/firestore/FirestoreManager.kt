package com.enmoble.common.firestore

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Firestore helper utilities focused on generic CRUD operations.
 *
 * This library’s goal is **not** to wrap Firestore calls, but to provide reusable higher-level primitives that
 * solve real problems you hit in production (batching, lock-based contention avoidance, and explicit ancestor nodes).
 *
 * ## Notes on Firestore DB (context)
 * Firestore has **Collections** (referenced by a `collectionId`) containing **Documents** (referenced by a `documentId`).
 * A Firestore path always alternates between collection and document segments:
 *
 * - The first segment is always a **collection**
 * - Next is a **document id**
 * - Then a **subcollection id**, then **document id**, and so on
 *
 * Example deep path: `root-collection/first-doc/sub-collection-1/doc-1/sub-collection-2/doc-2`
 *
 * Firestore also supports **collection groups**: a query over all collections with the same collection id across the DB.
 *
 * Indexes are of 2 types:
 * - **Simple index**: per-field indexes auto-created for fields in documents.
 * - **Composite index**: multiple-field indexes for more complex query patterns / efficiency.
 *
 * ## Non-existent ancestor documents (why explicit ancestors matter)
 * Firestore allows writing a deep path even if intermediate ancestor documents do not exist. However, those missing
 * ancestors may behave like **non-existent documents** and can become invisible in certain queries / console navigation.
 *
 * This library addresses that by explicitly creating ancestor documents and maintaining a `subcollections` field that
 * tracks known child subcollection ids for each explicit document.
 *
 * See:
 * - Firebase docs: https://firebase.google.com/docs/firestore/using-console#non-existent_ancestor_documents
 * - [`setDocumentAndUpdateAncestors`](#) (KDoc on that function contains full reasoning and examples)
 */
object FirestoreManager {
    private const val LOGTAG = "#FirestoreManager"

    /**
     * Firestore DB field name used by this library to record child subcollection ids under an explicit document.
     *
     * Why this exists:
     * - Firestore has **no supported API** to list the ids of subcollections contained within a document using a normal
     *   document read.
     * - A document read returns only its **fields**; subcollections are not returned as part of those fields.
     * - Even listing of a document’s field keys will not include subcollection ids.
     *
     * Example:
     * If `a/b/c/d` contains fields `arr` and `str`, and also contains a subcollection `subcol`, then:
     * `firebase.document("a/b/c/d").get().keys` returns `{ "arr", "str" }` but not `subcol`.
     *
     * Therefore, to enumerate subcollections and to support explicit-ancestor creation, the library maintains a field
     * named `subcollections` on explicit documents.
     */
    const val DB_FIELD_SUBCOLLECTIONS: String = "subcollections"

    /**
     * Common timestamp field name used by convenience APIs in this library.
     *
     * This is also used by write-lock documents created by this library (the lock timestamp is written under this field).
     */
    const val DB_FIELD_TIMESTAMP: String = "timestamp"

    /**
     * Default max transaction operations used for sizing transactional batch writes.
     *
     * Firestore’s documented limit is 500 (combined reads+writes) in a single transaction. This library uses 480 as a
     * conservative ceiling to provide headroom.
     */
    const val FIREBASE_TRANSACTION_MAX_SIZE: Int = 480

    /**
     * Write-lock expiry window to avoid deadlocks caused by crashed clients / abandoned locks.
     *
     * A write-lock is represented as a Firestore document whose id encodes the target collection path, and whose value
     * contains a timestamp field. Locks older than this interval are treated as stale and deleted.
     */
    const val DB_WRITE_LOCK_EXPIRY_INTERVAL_MS: Long = 120_000L

    /**
     * Minimum delay before retrying when a write lock is encountered (used when retry-if-locked is enabled).
     */
    const val DB_WRITE_LOCKED_RETRY_MIN_TIME_MS: Long = DB_WRITE_LOCK_EXPIRY_INTERVAL_MS

    private const val DEFAULT_WRITE_LOCKS_COLLECTION: String = "write-locks"

    @Volatile
    internal lateinit var appContext: Context
        private set

    /**
     * Initializes Firebase and configures Firestore local persistence / cache sizing.
     *
     * **Requirement:** [context] must be an [Application] instance (not an Activity context).
     *
     * What this does:
     * - Initializes Firebase via [FirebaseApp.initializeApp]. (Firebase treats this as idempotent; calling it multiple
     *   times is safe.)
     * - Enables offline persistence.
     * - Sets a tuned cache size based on the per-app heap class reported by [ActivityManager].
     *
     * Cache sizing rationale:
     * - Firestore persistence helps reduce latency and network usage.
     * - Cache size should be conservative on low-memory devices and larger on higher-end devices.
     *
     * Notes:
     * - Historically, some apps disabled verbose Firestore SDK logging. In newer Firebase SDKs,
     *   `FirebaseFirestore.setLoggingEnabled(false)` is deprecated/removed; this library does not force logging behavior.
     *
     * @param context Application context used to initialize Firebase/Firestore.
     * @throws IllegalArgumentException if [context] is not an [Application].
     */
    fun initFirestore(context: Context) {
        require(context is Application) {
            "initFirestore(context) requires an Application context"
        }
        appContext = context

        Log.d(LOGTAG, "initFirestore() - Initializing Firebase/Firestore")

        val am = context.getSystemService(ActivityManager::class.java)
        val heapMb = am?.memoryClass ?: 128  // per-app heap limit (MB)

        val cacheMb = when {
            heapMb >= 512 -> 80   // high-end phones/tablets
            heapMb >= 256 -> 40   // most midrange devices
            else -> 20            // low-end / constrained
        }

        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)              // Enable offline persistence
            .setCacheSizeBytes(cacheMb * 1024L * 1024L)
            .build()

        Firebase.firestore.firestoreSettings = settings
        FirebaseApp.initializeApp(context)
    }

    /**
     * Gets the system's default language (ISO 639-1).
     *
     * @return The system language code (for example `"en"`).
     */
    fun getSystemDefaultLanguage(): String = java.util.Locale.getDefault().language

    /**
     * Gets the system default language tag.
     *
     * On API 21+ this returns a BCP-47 language tag like `"en-US"`. On older APIs it returns a best-effort string
     * representation of the locale.
     *
     * @return Language tag string for the device locale.
     */
    fun getSystemDefaultLanguageTag(): String {
        val defaultLocale = java.util.Locale.getDefault()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            defaultLocale.toLanguageTag()
        } else {
            defaultLocale.toString()
        }
    }

    /**
     * Sets the given [document] into Firestore at [docRef].
     *
     * Data consistency notes:
     * - This is a simple write of a single document.
     * - If [mergeOnWrite] is `true`, this uses [SetOptions.merge] which helps maintain consistency when multiple
     *   writers concurrently update different fields on the same document (Firestore will merge field updates).
     *
     * Behavior:
     * - If [skipIfKeyExists] is `true` and the document already exists, this skips the write and returns `false`.
     * - Otherwise it writes the document using either merge semantics ([mergeOnWrite] = `true`) or replace semantics
     *   ([mergeOnWrite] = `false`).
     *
     * @param docRef Firestore document reference.
     * @param document Data to write.
     * @param skipIfKeyExists If true, do not overwrite an existing document.
     * @param mergeOnWrite If true, write with [SetOptions.merge]; otherwise replace the document.
     * @return `true` if a write was performed successfully, `false` if skipped or failed.
     */
    suspend fun <T : Any> setDocument(
        docRef: DocumentReference,
        document: T,
        skipIfKeyExists: Boolean = true,
        mergeOnWrite: Boolean = true,
    ): Boolean {
        val logPrefix = if (skipIfKeyExists) "(skipIfExists==true)" else "(skipIfExists==false)"

        if (skipIfKeyExists) {
            if (doesDocumentExist(docRef)) {
                Log.d(LOGTAG, "setDocument(): $logPrefix SKIP write (doc exists) at [${docRef.path}]")
                return false
            }
        }

        return try {
            if (mergeOnWrite) {
                docRef.set(document, SetOptions.merge()).await()
            } else {
                docRef.set(document).await()
            }
            Log.d(LOGTAG, "setDocument(): Write SUCCESS at [${docRef.path}]")
            true
        } catch (e: Exception) {
            Log.e(LOGTAG, "setDocument(): Write FAILED at [${docRef.path}] - ${e.message}", e)
            false
        }
    }

    /**
     * Convenience overload to write [data] to a Firestore document path.
     *
     * This uses merge semantics (equivalent to [SetOptions.merge]) and does not skip if the document already exists.
     *
     * @param path Firestore document path.
     * @param data Data to write.
     * @return `true` if write succeeded; `false` on failure.
     */
    suspend fun setDocument(path: String, data: Any): Boolean =
        setDocument(Firebase.firestore.document(path), data, skipIfKeyExists = false, mergeOnWrite = true)

    /**
     * Fetches the Firestore document snapshot at [dbPath].
     *
     * @param dbPath Firestore document path.
     * @param localCacheOnly If `true`, read only from local cache (no network).
     * @return The [DocumentSnapshot] if fetch succeeded, or `null` on error.
     */
    suspend fun getDocument(dbPath: String, localCacheOnly: Boolean = false): DocumentSnapshot? =
        getDocument(Firebase.firestore.document(dbPath), localCacheOnly)

    /**
     * Fetches the Firestore document snapshot at [docRef].
     *
     * @param docRef Firestore document reference.
     * @param localCacheOnly If `true`, read only from local cache (no network).
     * @return The [DocumentSnapshot] if fetch succeeded, or `null` on error.
     */
    suspend fun getDocument(docRef: DocumentReference, localCacheOnly: Boolean = false): DocumentSnapshot? =
        withContext(Dispatchers.IO) { getDocumentCommon(docRef, forceUseLocalCache = localCacheOnly) }

    internal suspend fun getDocumentCommon(
        docRef: DocumentReference,
        forceUseLocalCache: Boolean = false,
    ): DocumentSnapshot? {
        return try {
            docRef.get(if (forceUseLocalCache) Source.CACHE else Source.DEFAULT).await()
        } catch (e: Exception) {
            Log.e(LOGTAG, "getDocumentCommon(): FAILED for [${docRef.path}] - ${e.message}", e)
            null
        }
    }

    /**
     * Checks whether the leaf document / collection specified by [path] exists.
     *
     * Path interpretation:
     * - Even number of path segments => document path
     * - Odd number of path segments  => collection path
     *
     * Default behavior is optimized to reduce network calls:
     * - If [checkLocalCacheFirst] is `true`, this checks the local cache first.
     * - Only if missing (or not cached) does it hit the server.
     *
     * **Eventual consistency note:** this may return stale results if a node was deleted on the server but is still
     * present in cache. In many common app scenarios, creations are more frequent than deletions, so cache-first checks
     * are a pragmatic performance optimization.
     *
     * @param path Firestore path (document or collection).
     * @param checkLocalCacheFirst If `true`, check cache before server.
     * @param falseOnError If `true`, return `false` on error; otherwise rethrow.
     * @return `true` if the node exists, else `false`.
     * @throws FirebaseFirestoreException if [falseOnError] is `false` and Firestore throws.
     */
    @Throws(FirebaseFirestoreException::class)
    suspend fun fastCheckIfNodeExists(
        path: String,
        checkLocalCacheFirst: Boolean = true,
        falseOnError: Boolean = true,
    ): Boolean {
        val segments = path.split("/").filter { it.isNotBlank() }
        val ref: Any =
            if (segments.size % 2 == 0) Firebase.firestore.document(path)
            else Firebase.firestore.collection(path)

        return fastCheckIfNodeExists(ref, checkLocalCacheFirst, falseOnError)
    }

    /**
     * Checks whether the leaf document / collection specified by [firebaseRef] exists.
     *
     * Supported types:
     * - [DocumentReference]
     * - [CollectionReference]
     *
     * See also: [`fastCheckIfNodeExists(path, ...)`](#) for the path-based overload.
     *
     * @param firebaseRef Firestore ref to check.
     * @param checkLocalCacheFirst If `true`, check cache before server.
     * @param falseOnError If `true`, return `false` on error; otherwise rethrow.
     * @return `true` if the node exists, else `false`.
     * @throws FirebaseFirestoreException if [falseOnError] is `false` and Firestore throws.
     */
    @Throws(FirebaseFirestoreException::class)
    suspend fun fastCheckIfNodeExists(
        firebaseRef: Any,
        checkLocalCacheFirst: Boolean = true,
        falseOnError: Boolean = true,
    ): Boolean {
        return when (firebaseRef) {
            is DocumentReference -> {
                val docRef = firebaseRef
                if (checkLocalCacheFirst) {
                    try {
                        val cachedSnapshot = docRef.get(Source.CACHE).await()
                        if (cachedSnapshot.exists()) return true
                    } catch (e: FirebaseFirestoreException) {
                        if (e.code != FirebaseFirestoreException.Code.UNAVAILABLE) {
                            if (falseOnError) return false else throw e
                        }
                    } catch (e: Exception) {
                        if (falseOnError) return false else throw e
                    }
                }

                try {
                    val serverSnapshot = docRef.get(Source.DEFAULT).await()
                    serverSnapshot.exists()
                } catch (e: Exception) {
                    if (falseOnError) false else throw e
                }
            }

            is CollectionReference -> {
                val colRef = firebaseRef
                if (checkLocalCacheFirst) {
                    try {
                        val cachedSnap = colRef.limit(1).get(Source.CACHE).await()
                        if (!cachedSnap.isEmpty) return true
                    } catch (e: FirebaseFirestoreException) {
                        if (e.code != FirebaseFirestoreException.Code.UNAVAILABLE) {
                            if (falseOnError) return false else throw e
                        }
                    } catch (e: Exception) {
                        if (falseOnError) return false else throw e
                    }
                }

                try {
                    val serverSnap = colRef.limit(1).get(Source.DEFAULT).await()
                    !serverSnap.isEmpty
                } catch (e: Exception) {
                    if (falseOnError) false else throw e
                }
            }

            else -> false
        }
    }

    /**
     * Checks whether a document exists at [dbPath].
     *
     * @param dbPath Firestore document path.
     * @return `true` if the document exists, else `false`.
     * @throws FirebaseFirestoreException if Firestore throws (see underlying implementation).
     */
    suspend fun doesDocumentExist(dbPath: String): Boolean = doesDocumentExist(Firebase.firestore.document(dbPath))

    /**
     * Checks whether a document exists for [docRef].
     *
     * @param docRef Document reference to check.
     * @return `true` if the document exists, else `false`.
     * @throws FirebaseFirestoreException if Firestore throws (see underlying implementation).
     */
    suspend fun doesDocumentExist(docRef: DocumentReference): Boolean = fastCheckIfNodeExists(docRef)

    /**
     * Returns a list of documents contained in [query].
     *
     * Notes:
     * - Document snapshots are cheap until fields are accessed; however, callers should still be mindful of large query
     *   results and memory usage.
     *
     * @param query Firestore query.
     * @param queryString Optional log label for the query (useful for debugging).
     * @param localCacheOnly If `true`, reads only from cache.
     * @return List of [DocumentSnapshot] or `null` on error.
     */
    suspend fun getAllDocuments(
        query: Query,
        queryString: String? = null,
        localCacheOnly: Boolean = false,
    ): List<DocumentSnapshot>? {
        return try {
            val snapshot = query.get(if (localCacheOnly) Source.CACHE else Source.DEFAULT).await()
            if (snapshot.isEmpty) {
                Log.w(LOGTAG, "getAllDocuments(): EMPTY for query=[${queryString ?: query}]")
            }
            snapshot.documents
        } catch (e: Exception) {
            Log.e(LOGTAG, "getAllDocuments(): FAILED for query=[${queryString ?: query}] - ${e.message}", e)
            null
        }
    }

    /**
     * Returns known subcollection ids for the document at [documentPath].
     *
     * This reads the field [DB_FIELD_SUBCOLLECTIONS]. If that field is not present (or document missing), returns an
     * empty list.
     *
     * Important: Firestore has no supported API to list subcollections of a document via standard document reads; this
     * method relies on the library-maintained tracking field.
     *
     * @param documentPath Firestore document path.
     * @param localCacheOnly If `true`, reads only from cache.
     * @return List of subcollection ids, or empty list if unavailable.
     */
    suspend fun getAllSubcollectionIds(documentPath: String, localCacheOnly: Boolean = false): List<String> {
        val docRef = Firebase.firestore.document(documentPath)
        return try {
            val snapshot = docRef.get(if (localCacheOnly) Source.CACHE else Source.DEFAULT).await()
            @Suppress("UNCHECKED_CAST")
            (snapshot.get(DB_FIELD_SUBCOLLECTIONS) as List<String>?) ?: emptyList()
        } catch (e: Exception) {
            Log.e(LOGTAG, "getAllSubcollectionIds(): FAILED for [$documentPath] - ${e.message}", e)
            emptyList()
        }
    }

    /**
     * - Sets the given document to Firestore DB
     * - For any given document path (where the leaf of the path indicates the document id - eg in "a/b/c/d" the docId is "d"),
     * this function will create explicit nodes for ALL the intermediate sub-collections & documents if they don't already exist,
     * AND update each document's "subcollections" field (unless params specify otherwise) for any new subcollection added into
     * that document
     *
     * What this means & why this is required :
     * - a) A document path will always be of the form that starts with a collectionId & alternates between collectionId & documentId
     *   Eg. "collectionId/parentDocId/subCollectionId/docId...."
     *   Eg. of deeper depth: "collectionId/parentDocId/subCollectionId/pDocId2/subC2/docId...."
     *
     * - b) Although Firestore automatically creates implicit nodes for a given path (ie. if "one/two" doesn't exist, Firestore will
     * still allow creating node "one/two/three" and will make "one/two" as implicit nodes), we should NOT directly create child nodes
     * under implicit parents bcos then those implicit parent nodes will not show up in any firebase queries.
     * HENCE if the parent / intermediate node doesn't exist then it should be explcitly created. This function does that.
     * To create an explicit node, the document simply needs to be set with any data
     * @see https://firebase.google.com/docs/firestore/using-console?hl=en&authuser=0&_gl=1*11ou31u*_ga*MjI5NjYzNjU3LjE3MTYxMzU2ODc.*_ga_CW55HF8NVT*MTcxNzE4MzcxOC4yNC4xLjE3MTcxODM3MTguNjAuMC4w#non-existent_ancestor_documents
     *
     * - c) For some crazy reason, Firebase has NO way to list the ids of any subcollections contained within a document. Even doing
     * a query on the document will not return any subcollections contained in it. Similarly, there's no way to query any node for
     * its children and even listing of a document's fields will also not return any subcollection ids (it will return all other keys
     * as expected).
     *
     * Eg. if "a/b/c/d" is a document path with data as follows:
     *
     *          key = "d"
     *          value = {
     *              "arr" : Array<Int> = { 1, 2, 3 },       // Field-1
     *              "str" : String = ""                     // Field-2
     *              "subcol": SubCollection -> {...}        // Field-3 (Firebase doesn't consider this to be an actual field)
     *          }
     *          Then, calling firebase.document("a/b/c/d").get().keys will return { "arr", "str" } but not "subcol"
     *
     * - d) Therefore to handle both the above issues, we set a field called "subcollections" into every parent document (which will also make
     * the document an explicit node) and maintain the list of all subcollections added/removed from that document in this field
     *
     * **NOTE** To fix existing entries in the DB (ie. documents that were created implcitly and are not yet explicit), we can
     * manually add the 'subcollections' field into each Document in the Firebase console. This'll also make the documents explicit
     *
     * **NOTE** This function should only be called on a document & since Firebase paths are alternating document & subcollections,
     *          it implies that the input path should have an even number of segments
     *
     * **NOTE** **This will internally start multiple Coroutines to handle ancestor node updates**
     *
     * **To SKIP ancestor updates** (and simply set the given document & do nothing else), **pass maxHeight = 0**
     *
     * **To ONLY update ancestors** for a tentative document path, **pass document = null**
     * * Pass a SuperviserScope to prevent cancellation of the entire scope if any individual threads fail with exception
     * * Use a global scope/job to allow database operations to complete regardless of app lifecycle
     *
     * **Throws exceptions**
     * @param maxHeight Max tree height to process starting from (and including) leaf node & going up
     *                  (leaf node height = 1)
     *                  Eg. for "a/b/c/d/e/f" & maxHeight=3, we process upto node "d"
     *                  Anything less than the minimum value 3 will SKIP ancestor updates
     *
     * @param docPath Firestore document path.
     * @param scope Coroutine scope used for background tasks.
     * @param document Optional leaf document data to write.
     * @param maxHeightIn Max height of ancestor processing (leaf height = 1).
     * @param skipIfKeyExists Controls leaf document write behavior.
     * @param mergeOnWrite Controls leaf document write behavior.
     * @return list of launched jobs (leaf write + ancestor updates).
     */
    suspend fun <T> setDocumentAndUpdateAncestors(
        docPath: String,
        scope: CoroutineScope,
        document: T? = null,
        maxHeightIn: Int = 3,
        skipIfKeyExists: Boolean = true,
        mergeOnWrite: Boolean = true,
    ): List<Job> {
        val jobs = ArrayList<Job>()
        val nodeIds = (if (docPath.startsWith('/')) docPath.substring(1) else docPath).split("/")
        val size = nodeIds.size

        if (size == 0 || size < 2 || size % 2 == 1) {
            Log.e(LOGTAG, "setDocumentAndUpdateAncestors(): INVALID docPath=[$docPath] (must be even segments, >=2)")
            return jobs
        }

        var maxHeight = maxHeightIn
        if (maxHeightIn > size - 1) {
            maxHeight = size - 1
            Log.w(LOGTAG, "setDocumentAndUpdateAncestors(): maxHeightIn=$maxHeightIn corrected to $maxHeight")
        }

        // Leaf write
        if (document != null) {
            val documentRef = Firebase.firestore.document(docPath)
            jobs += scope.async(Dispatchers.IO) {
                setDocument(documentRef, document as Any, skipIfKeyExists, mergeOnWrite)
            }
        }

        if (maxHeight < 3) {
            Log.d(LOGTAG, "setDocumentAndUpdateAncestors(): SKIP ancestor updates (maxHeightIn=$maxHeightIn < 3)")
            return jobs
        }

        val stopDepth = size - maxHeight

        for (i in nodeIds.lastIndex downTo stopDepth step 2) {
            val docId = nodeIds[i]
            val subCollectionId = nodeIds[i - 1]
            val parentDocPath = nodeIds.take(i - 1).joinToString("/")
            val parentDocId = parentDocPath.substringAfterLast("/")

            jobs += scope.async(Dispatchers.IO + CoroutineName(parentDocId)) {
                try {
                    Firebase.firestore.runTransaction { transaction ->
                        val parentDocRef = Firebase.firestore.document(parentDocPath)
                        val parentSnapshot = transaction.get(parentDocRef)

                        if (!parentSnapshot.exists()) {
                            transaction.set(
                                parentDocRef,
                                mapOf(DB_FIELD_SUBCOLLECTIONS to listOf(subCollectionId)),
                                SetOptions.merge(),
                            )
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            val current = parentSnapshot.get(DB_FIELD_SUBCOLLECTIONS) as List<String>?

                            if (current?.contains(subCollectionId) == true) {
                                // No-op
                            } else {
                                val updated = (current ?: emptyList()) + subCollectionId
                                transaction.set(
                                    parentDocRef,
                                    mapOf(DB_FIELD_SUBCOLLECTIONS to updated.sorted()),
                                    SetOptions.merge(),
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(
                        LOGTAG,
                        "setDocumentAndUpdateAncestors(): FAILED updating parent=[$parentDocPath] add subCollection=[$subCollectionId] leaf=[$docId] - ${e.message}",
                        e,
                    )
                    throw e
                }
            }
        }

        return jobs
    }

    /**
     * For a given leaf document path [docPath], this creates all required explicit parent document nodes and updates their
     * `subcollections` fields appropriately.
     *
     * No “real” leaf document is written; internally this calls [setDocumentAndUpdateAncestors] on a dummy leaf path.
     *
     * @param docPath A document path (not a collection path).
     * @param scope Coroutine scope used for background tasks.
     * @return List of jobs created to perform ancestor updates.
     */
    suspend fun explicitlyCreateDocumentPath(docPath: String, scope: CoroutineScope): List<Job> {
        val dummyPath = "$docPath/dummy"
        val maxHeight = (if (dummyPath.startsWith('/')) dummyPath.substring(1) else dummyPath).split("/").size - 1
        Log.d(LOGTAG, "explicitlyCreateDocumentPath(): Creating explicit ancestors for dummy leaf [$dummyPath]")
        return setDocumentAndUpdateAncestors(dummyPath, scope, document = null, maxHeightIn = maxHeight, skipIfKeyExists = false, mergeOnWrite = false)
    }

    /**
     * Computes the lock document id used for a collection path.
     *
     * The implementation converts a collection path into a valid Firestore document id by replacing `/` with `_`.
     *
     * @param collectionPathToWrite Collection path that will be write-locked.
     * @return Lock document id derived from [collectionPathToWrite].
     */
    fun getWriteLockDocId(collectionPathToWrite: String): String =
        collectionPathToWrite.trim('/').replace('/', '_')

    /**
     * Does multiple transactional batch writes to write all documents under [collectionPath].
     *
     * Why batching:
     * - Individual document writes can be slower and cause more coroutine/memory churn when writing 100s of items.
     * - Firestore limits transaction size to 500 combined reads+writes; this library uses [FIREBASE_TRANSACTION_MAX_SIZE]
     *   and sizes the batch conservatively (default ~240 items) to leave headroom.
     *
     * Write-locking behavior:
     * - When [useWriteLock] is enabled, this creates a lock document for the whole write operation.
     * - If a lock is present, subsequent writers either abort immediately or retry with backoff (based on [retryIfLocked]).
     * - Locks older than [DB_WRITE_LOCK_EXPIRY_INTERVAL_MS] are treated as stale and deleted to avoid deadlocks.
     *
     * Risk/behavior notes (important):
     * - When writing to a collection path with expected contention (e.g. “common/shared” data written by many clients),
     *   consider whether you should be doing batched transactional writes at all.
     * - When contention exists, it is often safer to abort and let the caller retry later using a random exponential
     *   backoff with a higher initial delay (often minutes), rather than spinning on retries.
     *
     * Ancestor updates:
     * - If [updateAncestors] is enabled, this triggers a single ancestor update (via a dummy leaf) before the actual
     *   writes so that intermediate ancestor documents are explicit and their `subcollections` tracking field is updated.
     *
     * @param collectionPath Collection path to write under.
     * @param itemsToWrite Items that implement [DbStorable]; their [DbStorable.dbKey] is used as document id.
     * @param batchSizeMax Max number of items per batch (items, not ops). The library enforces Firestore limits.
     * @param skipIfExists If `true`, existing docs are not overwritten.
     * @param mergeOnWrite If `true`, uses merge semantics when writing items.
     * @param updateAncestors If `true`, updates ancestor nodes once before writing.
     * @param useWriteLock If `true`, uses a lock document stored under [writeLockCollectionPath].
     * @param writeLockCollectionPath Collection path for lock documents. If null/blank, locking is disabled.
     * @param retryIfLocked If `true`, retries when the path is locked.
     * @param lockedRetryMaxSecs Random extra delay window (seconds) added on retry.
     * @param maxLockRetries Max retry attempts if locked and [retryIfLocked] is enabled.
     * @return List of jobs created for ancestor updates (the transactional writes themselves complete before return).
     * @throws FirebaseFirestoreException if transactions abort beyond retry limits or lock contention cannot be resolved.
     * @throws Exception for other Firestore/serialization failures.
     */
    suspend fun batchedWriteOrUpdateAllDocuments(
        collectionPath: String,
        itemsToWrite: List<DbStorable>,
        batchSizeMax: Int = FIREBASE_TRANSACTION_MAX_SIZE / 2,
        skipIfExists: Boolean,
        mergeOnWrite: Boolean = false,
        updateAncestors: Boolean = true,
        useWriteLock: Boolean = true,
        writeLockCollectionPath: String? = DEFAULT_WRITE_LOCKS_COLLECTION,
        retryIfLocked: Boolean = false,
        lockedRetryMaxSecs: Int = 180,
        maxLockRetries: Int = 2,
    ): List<Job> = withContext(Dispatchers.IO) {
        val jobs = ArrayList<Job>()

        val batchSize = if (batchSizeMax * 2 > FIREBASE_TRANSACTION_MAX_SIZE) FIREBASE_TRANSACTION_MAX_SIZE / 2 else batchSizeMax

        val checkForWriteLock = useWriteLock && !writeLockCollectionPath.isNullOrBlank()
        val lockRef = if (checkForWriteLock) {
            val lockId = getWriteLockDocId(collectionPath)
            Firebase.firestore.collection(writeLockCollectionPath!!).document(lockId)
        } else null

        if (checkForWriteLock) {
            var retries = 0
            while (true) {
                var lockedException: Exception? = null

                Firebase.firestore.runTransaction { lockTrans ->
                    val document = lockTrans.get(lockRef!!)
                    val lastLocked = if (document.exists()) document.getTimestamp(DB_FIELD_TIMESTAMP)?.toDate()?.time else null

                    if (lastLocked != null) {
                        val isStale = System.currentTimeMillis() - lastLocked > DB_WRITE_LOCK_EXPIRY_INTERVAL_MS
                        if (isStale) {
                            lockTrans.delete(lockRef)
                            lockedException = FirebaseFirestoreException(
                                "Write lock stale for [$collectionPath] (deleted stale lock)",
                                FirebaseFirestoreException.Code.ABORTED,
                            )
                        } else {
                            lockedException = FirebaseFirestoreException(
                                "Write path locked for [$collectionPath]",
                                FirebaseFirestoreException.Code.ABORTED,
                            )
                        }
                    } else {
                        lockTrans.set(lockRef, mapOf(DB_FIELD_TIMESTAMP to FieldValue.serverTimestamp()))
                    }
                }.await()

                if (lockedException != null) {
                    if (!retryIfLocked) throw lockedException!!
                    if (retries >= maxLockRetries) {
                        throw FirebaseFirestoreException(
                            "Write path locked for [$collectionPath] and no retries remain (retries=$retries, max=$maxLockRetries)",
                            FirebaseFirestoreException.Code.ABORTED,
                        )
                    }
                    retries++
                    delay(DB_WRITE_LOCKED_RETRY_MIN_TIME_MS + Random.nextInt(lockedRetryMaxSecs) * 1000L)
                    continue
                }

                break
            }
        }

        if (updateAncestors) {
            jobs += setDocumentAndUpdateAncestors("$collectionPath/dummy", this, document = null)
        }

        var startIndex = 0
        var remaining = itemsToWrite.size
        var batchNum = 1

        while (remaining > 0) {
            val currSize = if (remaining > batchSize) batchSize else remaining
            val items = itemsToWrite.subList(startIndex, startIndex + currSize)

            val maxRetries = 3
            var attempt = 0
            var expDelayMs = 0L

            while (attempt < maxRetries) {
                try {
                    Firebase.firestore.runTransaction { transaction ->
                        val toWrite = if (skipIfExists) {
                            items.filter { item ->
                                val docRef = Firebase.firestore.document("$collectionPath/${item.dbKey}")
                                !transaction.get(docRef).exists()
                            }
                        } else items

                        for (item in toWrite) {
                            val docRef = Firebase.firestore.document("$collectionPath/${item.dbKey}")
                            if (mergeOnWrite) transaction.set(docRef, item, SetOptions.merge())
                            else transaction.set(docRef, item)
                        }
                    }.await()
                    break
                } catch (e: Exception) {
                    if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) {
                        attempt++
                        if (attempt >= maxRetries) throw e
                        expDelayMs = (expDelayMs * attempt) + Random.nextInt(3000).toLong()
                        delay(expDelayMs)
                    } else {
                        if (checkForWriteLock) {
                            lockRef?.delete()?.await()
                        }
                        throw e
                    }
                }
            }

            remaining -= currSize
            startIndex += currSize
            batchNum++

            Log.d(LOGTAG, "batchedWriteOrUpdateAllDocuments(): Finished batch#${batchNum - 1} for [$collectionPath]")
        }

        if (checkForWriteLock) {
            lockRef?.delete()
        }

        jobs
    }

    /**
     * Performs Firestore [WriteBatch] writes for [itemsToWrite] under [collectionPath].
     *
     * Behavior:
     * - If [fieldToUpdate1]/[fieldToUpdate2] are `null`, this **writes objects** using each item's [DbStorable.dbKey] as
     *   the document id.
     * - If field parameters are provided, this **updates fields only** (documents must already exist for updates).
     *
     * Notes:
     * - Firestore batch size is capped at 500 operations.
     * - This API does not “skip duplicates” automatically; it either writes or updates based on the passed parameters.
     *
     * @param collectionPath Collection path to write under.
     * @param itemsToWrite Items to write/update.
     * @param maxBatchSize Maximum operations per batch (Firestorm limit is 500).
     * @param fieldToUpdate1 Optional field name for update mode.
     * @param fieldValues1 Optional field values aligned with [itemsToWrite] for update mode.
     * @param fieldToUpdate2 Optional second field name for update mode.
     * @param fieldValues2 Optional second field values aligned with [itemsToWrite] for update mode.
     * @param mergeOnWrite If `true`, uses merge semantics when writing objects.
     * @throws IllegalArgumentException if inputs are inconsistent with Firestore batch limits or value list sizes.
     */
    suspend fun <T1 : Any, T2 : Any> batchedWriteDocsOrUpdateFields(
        collectionPath: String,
        itemsToWrite: List<DbStorable>,
        maxBatchSize: Int = FIREBASE_TRANSACTION_MAX_SIZE,
        fieldToUpdate1: String? = null,
        fieldValues1: List<T1>? = null,
        fieldToUpdate2: String? = null,
        fieldValues2: List<T2>? = null,
        mergeOnWrite: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        var startIndex = 0
        var remaining = itemsToWrite.size

        while (remaining > 0) {
            val currSize = if (remaining > maxBatchSize) maxBatchSize else remaining
            val items = itemsToWrite.subList(startIndex, startIndex + currSize)

            val fv1 = fieldValues1?.subList(startIndex, startIndex + currSize)
            val fv2 = fieldValues2?.subList(startIndex, startIndex + currSize)

            batchWriteDocsOrUpdateFields(
                collectionPath = collectionPath,
                itemsToWrite = items,
                fieldToUpdate1 = fieldToUpdate1,
                fieldValues1 = fv1,
                fieldToUpdate2 = fieldToUpdate2,
                fieldValues2 = fv2,
                mergeOnWrite = mergeOnWrite,
            )

            remaining -= currSize
            startIndex += currSize
        }
    }

    private suspend fun <T1, T2> batchWriteDocsOrUpdateFields(
        collectionPath: String,
        itemsToWrite: List<DbStorable>,
        fieldToUpdate1: String? = null,
        fieldValues1: List<T1>? = null,
        fieldToUpdate2: String? = null,
        fieldValues2: List<T2>? = null,
        mergeOnWrite: Boolean,
    ) = withContext(Dispatchers.IO) {
        val batch: WriteBatch = Firebase.firestore.batch()
        val writeOnly = (fieldToUpdate1 == null && fieldToUpdate2 == null)

        if (itemsToWrite.size > FIREBASE_TRANSACTION_MAX_SIZE) {
            throw IllegalArgumentException("Input list size max is $FIREBASE_TRANSACTION_MAX_SIZE (Firestore batch limit)")
        }

        if (!writeOnly) {
            val expected = itemsToWrite.size
            if ((fieldValues1?.size ?: expected) != expected || (fieldValues2?.size ?: expected) != expected) {
                throw IllegalArgumentException("itemsToWrite and fieldValues lists must be same size")
            }
        }

        for (i in itemsToWrite.indices) {
            val item = itemsToWrite[i]
            val documentRef = Firebase.firestore.document("$collectionPath/${item.dbKey}")

            if (writeOnly) {
                if (mergeOnWrite) batch.set(documentRef, item, SetOptions.merge())
                else batch.set(documentRef, item)
            } else {
                if (fieldToUpdate2 != null) {
                    batch.update(
                        documentRef,
                        fieldToUpdate1!!,
                        fieldValues1?.get(i),
                        fieldToUpdate2,
                        fieldValues2?.get(i),
                    )
                } else {
                    batch.update(documentRef, fieldToUpdate1!!, fieldValues1?.get(i))
                }
            }
        }

        try {
            batch.commit().await()
        } catch (e: Exception) {
            Log.e(LOGTAG, "batchWriteDocsOrUpdateFields(): FAILED for [$collectionPath] - ${e.message}", e)
            throw e
        }
    }
}