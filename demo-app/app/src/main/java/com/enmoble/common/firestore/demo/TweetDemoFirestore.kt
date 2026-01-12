package com.enmoble.common.firestore.demo

import android.util.Log
import com.enmoble.common.firestore.FirestoreManager
import com.google.firebase.Firebase
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.toObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Demo-only helper that provides a realistic “tweet-like” Firestore schema and operations using the public library APIs.
 *
 * Schema used by this demo:
 *
 * - Tweets for a given handle:
 *   `/demo/twitter-data/{handle}/tweets/{tweetId}`
 *
 * Why this shape?
 * - Keeps tweets grouped by user handle.
 * - Uses a stable subcollection name `tweets`, enabling `collectionGroup("tweets")` queries
 *   (for “oldest/latest tweet across all handles” demos).
 */
object TweetDemoFirestore {
    private const val LOGTAG = "TweetDemoFirestore"
    private const val ROOT = "demo"
    private const val TWITTER_DATA = "twitter-data"
    private const val TWEETS = "tweets"

    /**
     * Returns the Firestore collection path containing tweets for [handle].
     */
    fun tweetsCollectionPath(handle: String): String =
        "$ROOT/$TWITTER_DATA/${handle.lowercase()}/$TWEETS"

    /**
     * Writes/replaces a single [tweet] using [`FirestoreManager.setDocumentAndUpdateAncestors()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:391).
     *
     * This demonstrates:
     * - leaf document write
     * - explicit-ancestor creation & subcollection tracking.
     */
    suspend fun writeOrReplaceTweet(tweet: Tweet) = withContext(Dispatchers.IO) {
        require(tweet.handle.isNotBlank()) { "Tweet.handle cannot be blank" }
        require(tweet.id.isNotBlank()) { "Tweet.id cannot be blank" }

        val docPath = "${tweetsCollectionPath(tweet.handle)}/${tweet.id}"
        FirestoreManager.setDocumentAndUpdateAncestors(
            docPath = docPath,
            scope = this,
            document = tweet,
            maxHeightIn = 3,
            skipIfKeyExists = false,
            mergeOnWrite = true,
        )
    }

    /**
     * Batch writes tweets under a handle using [`FirestoreManager.batchedWriteOrUpdateAllDocuments()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:431).
     *
     * This demonstrates:
     * - chunked transaction writes
     * - optional write-locking
     * - optional ancestor updates.
     *
     * Note: for the demo we allow toggling [useWriteLock] to show the API; for most single-client demos it can be false.
     */
    suspend fun batchedWriteOrUpdateTweets(
        handle: String,
        tweets: List<Tweet>,
        useWriteLock: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val path = tweetsCollectionPath(handle)
        FirestoreManager.batchedWriteOrUpdateAllDocuments(
            collectionPath = path,
            itemsToWrite = tweets,
            skipIfExists = false,
            mergeOnWrite = true,
            updateAncestors = true,
            useWriteLock = useWriteLock,
            // store locks under the default lock collection in this demo project
            writeLockCollectionPath = "write-locks",
            retryIfLocked = true,
            lockedRetryMaxSecs = 10,
            maxLockRetries = 2,
        )
    }

    /**
     * Reads tweets for a handle since [sinceTime] (inclusive), ordered by timestamp DESC.
     *
     * This mirrors the intent of internal `readTweets(...)` but uses this demo schema.
     *
     * @param localCacheOnly If true, reads from Firestore cache only.
     * @param maxResults Max number of tweets returned.
     */
    suspend fun readTweets(
        handle: String,
        sinceTime: Long,
        localCacheOnly: Boolean = false,
        maxResults: Int = Int.MAX_VALUE,
    ): List<Tweet> = withContext(Dispatchers.IO) {
        val colPath = tweetsCollectionPath(handle)

        val query = Firebase.firestore
            .collection(colPath)
            .whereGreaterThanOrEqualTo(FirestoreManager.DB_FIELD_TIMESTAMP, sinceTime)
            .orderBy(FirestoreManager.DB_FIELD_TIMESTAMP, Query.Direction.DESCENDING)
            .limit(maxResults.toLong())

        val docs = FirestoreManager.getAllDocuments(query, queryString = "readTweets($handle,since=$sinceTime)", localCacheOnly = localCacheOnly)
            ?: return@withContext emptyList()

        docs.mapNotNull { it.toObject<Tweet>()?.copy() }
    }

    /**
     * Reads the oldest or latest tweet across *any* handle using a `collectionGroup("tweets")` query.
     *
     * This demonstrates:
     * - `collectionGroup` queries
     * - toggling local-cache-only vs server/default read behavior.
     *
     * @param isOldest If true, returns oldest; otherwise returns latest.
     * @param localCacheOnly If true, reads from cache only.
     */
    suspend fun readOldestOrLatestTweetAcrossAllHandles(
        isOldest: Boolean,
        localCacheOnly: Boolean = false,
    ): Tweet? = withContext(Dispatchers.IO) {
        val query = Firebase.firestore
            .collectionGroup(TWEETS)
            .orderBy(
                FirestoreManager.DB_FIELD_TIMESTAMP,
                if (isOldest) Query.Direction.ASCENDING else Query.Direction.DESCENDING,
            )
            .limit(1)

        val snap = query.get(if (localCacheOnly) Source.CACHE else Source.DEFAULT).await()
        val doc: DocumentSnapshot = snap.documents.firstOrNull() ?: return@withContext null

        val tweet = doc.toObject<Tweet>() ?: return@withContext null

        // The handle is part of the path: /demo/twitter-data/{handle}/tweets/{id}
        val handle = doc.reference.parent.parent?.id ?: ""
        tweet.copy(handle = handle)
    }

    /**
     * Convenience: reads the oldest tweet across all handles.
     */
    suspend fun readOldestTweetAcrossAllHandles(localCacheOnly: Boolean = false): Tweet? =
        readOldestOrLatestTweetAcrossAllHandles(isOldest = true, localCacheOnly = localCacheOnly)

    /**
     * Convenience: reads the latest tweet across all handles.
     */
    suspend fun readLatestTweetAcrossAllHandles(localCacheOnly: Boolean = false): Tweet? =
        readOldestOrLatestTweetAcrossAllHandles(isOldest = false, localCacheOnly = localCacheOnly)

    /**
     * Utility used by the demo to generate a deterministic tweet id.
     */
    fun tweetId(handle: String, timestamp: Long): String {
        val safeHandle = handle.lowercase().replace("[^a-z0-9_]".toRegex(), "_")
        return "${safeHandle}_$timestamp"
    }

    /**
     * Debug helper for logs.
     */
    fun Tweet.toShortString(): String =
        "Tweet(handle=$handle, id=$id, ts=$timestamp, text=${text.take(40)})"

    /**
     * Simple logging helper used by the demo.
     */
    fun log(msg: String) {
        Log.d(LOGTAG, msg)
    }
}