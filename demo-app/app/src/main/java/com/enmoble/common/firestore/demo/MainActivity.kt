package com.enmoble.common.firestore.demo

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.enmoble.common.firestore.FirestoreManager
import com.enmoble.common.firestore.FirestoreReactive
import com.google.firebase.Firebase
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.toObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Demo app for `lib-enmoble-firestore`.
 *
 * This demo is written to be “real” in the sense that, once you add Firebase configuration and enable Firestore,
 * running it will:
 * 1) Write data
 * 2) Read the same data back (using both normal collection queries and collectionGroup queries)
 *
 * Firestore prerequisites (required for runtime success):
 * - Add a Firebase project
 * - Add an Android app in Firebase console
 * - Place google-services.json under demo-app/app/
 * - Ensure Firestore is enabled in Firebase console
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this).apply {
            text = "lib-enmoble-firestore demo\n\nWaiting…"
        }
        setContentView(tv)

        // Level-1: simplest demo (init)
        FirestoreManager.initFirestore(application)

        lifecycleScope.launch {
            val out = StringBuilder()
            val io = Dispatchers.IO

            out.appendLine("lib-enmoble-firestore demo")
            out.appendLine()

            // =========================================================================
            // Level-1: simplest demos (write then read)
            // =========================================================================
            out.appendLine("Level-1: simplest demos")
            out.appendLine("----------------------")

            val helloPath = "demo/hello"
            val helloPayload = mapOf("message" to "hello", "ts" to System.currentTimeMillis())

            out.appendLine("1) setDocument(path=\"$helloPath\")")
            val wroteHello = withContext(io) {
                FirestoreManager.setDocument(path = helloPath, data = helloPayload)
            }
            out.appendLine("   writeResult=$wroteHello")

            out.appendLine("2) getDocument(path=\"$helloPath\")")
            val helloDoc = withContext(io) { FirestoreManager.getDocument(helloPath, localCacheOnly = false) }
            out.appendLine("   read=${helloDoc?.data}")

            val ancestorPath = "demo/ancestors/doc1/sub/doc2"
            out.appendLine("3) setDocumentAndUpdateAncestors(docPath=\"$ancestorPath\")")
            val ancestorJobs = withContext(io) {
                FirestoreManager.setDocumentAndUpdateAncestors(
                    docPath = ancestorPath,
                    scope = this@launch,
                    document = mapOf("x" to 1, "ts" to System.currentTimeMillis()),
                    maxHeightIn = 3,
                    skipIfKeyExists = false,
                    mergeOnWrite = true,
                )
            }
            out.appendLine("   jobsStarted=${ancestorJobs.size}")

            out.appendLine("4) getDocument(docPath=\"$ancestorPath\")")
            val ancestorDoc = withContext(io) { FirestoreManager.getDocument(ancestorPath, localCacheOnly = false) }
            out.appendLine("   read=${ancestorDoc?.data}")
            out.appendLine()

            // =========================================================================
            // Level-2: realistic use-case demo with Tweet model
            // =========================================================================
            out.appendLine("Level-2: realistic Tweet demo")
            out.appendLine("----------------------------")

            val handle = "demo_user"
            val now = System.currentTimeMillis()
            val tweet1 = Tweet(handle = handle, id = TweetDemoFirestore.tweetId(handle, now - 2_000), timestamp = now - 2_000, text = "hello tweet-1")
            val tweet2 = Tweet(handle = handle, id = TweetDemoFirestore.tweetId(handle, now - 1_000), timestamp = now - 1_000, text = "hello tweet-2")
            val tweet3 = Tweet(handle = handle, id = TweetDemoFirestore.tweetId(handle, now), timestamp = now, text = "hello tweet-3")

            // 2a) writeOrReplaceTweet() -> demonstrates setDocumentAndUpdateAncestors()
            out.appendLine("5) writeOrReplaceTweet(tweet1) (uses setDocumentAndUpdateAncestors)")
            withContext(io) { TweetDemoFirestore.writeOrReplaceTweet(tweet1) }
            out.appendLine("   wrote: ${TweetDemoFirestore.run { tweet1.toShortString() }}")
            out.appendLine()

            // 2b) batchedWriteOrUpdateTweets() -> demonstrates batchedWriteOrUpdateAllDocuments()
            out.appendLine("6) batchedWriteOrUpdateTweets(handle=\"$handle\", tweets=[tweet2,tweet3])")
            withContext(io) {
                TweetDemoFirestore.batchedWriteOrUpdateTweets(
                    handle = handle,
                    tweets = listOf(tweet2, tweet3),
                    useWriteLock = true,
                )
            }
            out.appendLine("   wrote: ${TweetDemoFirestore.run { tweet2.toShortString() }}")
            out.appendLine("   wrote: ${TweetDemoFirestore.run { tweet3.toShortString() }}")
            out.appendLine()

            // 2c) readTweets() -> collection query + timestamp filter + cache/network toggle
            out.appendLine("7) readTweets(handle=\"$handle\", sinceTime=now-10min, cacheOnly=false)")
            val readTweets = withContext(io) {
                TweetDemoFirestore.readTweets(
                    handle = handle,
                    sinceTime = now - 10 * 60 * 1000L,
                    localCacheOnly = false,
                    maxResults = 50,
                )
            }
            out.appendLine("   count=${readTweets.size}")
            readTweets.forEach { out.appendLine("   ${TweetDemoFirestore.run { it.toShortString() }}") }
            out.appendLine()

            // 2d) readOldestTweet/readLatestTweet across all handles -> collectionGroup("tweets")
            out.appendLine("8) readLatestTweetAcrossAllHandles(cacheOnly=false) (collectionGroup(\"tweets\"))")
            val latestAcrossAll = withContext(io) {
                TweetDemoFirestore.readLatestTweetAcrossAllHandles(localCacheOnly = false)
            }
            val latestStr = latestAcrossAll?.let { TweetDemoFirestore.run { it.toShortString() } } ?: "null"
            out.appendLine("   latest=$latestStr")

            out.appendLine("9) readOldestTweetAcrossAllHandles(cacheOnly=false) (collectionGroup(\"tweets\"))")
            val oldestAcrossAll = withContext(io) {
                TweetDemoFirestore.readOldestTweetAcrossAllHandles(localCacheOnly = false)
            }
            val oldestStr = oldestAcrossAll?.let { TweetDemoFirestore.run { it.toShortString() } } ?: "null"
            out.appendLine("   oldest=$oldestStr")
            out.appendLine()

            // 2e) oneShotCollectionQueryFlow() to fetch a stream of tweets previously written
            out.appendLine("10) oneShotCollectionQueryFlow() -> stream tweets under handle=\"$handle\"")
            val tweetsPath = TweetDemoFirestore.tweetsCollectionPath(handle)
            val tweetsQueryKey = FirestoreReactive.queryKey(tweetsPath, "TWEETS_SINCE_${now - 10 * 60 * 1000L}")
            val tweetStreamLines = withContext(io) {
                val q = Firebase.firestore.collection(tweetsPath)
                    .whereGreaterThanOrEqualTo(FirestoreManager.DB_FIELD_TIMESTAMP, now - 10 * 60 * 1000L)
                    .orderBy(FirestoreManager.DB_FIELD_TIMESTAMP, Query.Direction.DESCENDING)

                val lines = mutableListOf<String>()
                FirestoreReactive.oneShotCollectionQueryFlow(
                    query = q,
                    queryPath = tweetsQueryKey,
                    collectionPath = tweetsPath,
                    clazz = Tweet::class.java,
                    converter = { doc, _, _ ->
                        (doc.toObject<Tweet>() ?: Tweet()).copy(handle = handle, id = doc.id) to ""
                    },
                    argToConverter = null,
                    continueOnError = true,
                    localCacheOnly = false,
                ).collect { (tweet, err) ->
                    if (err.isEmpty()) lines += TweetDemoFirestore.run { tweet.toShortString() } else lines += "ERR=$err"
                }
                lines
            }
            tweetStreamLines.forEach { out.appendLine("   $it") }

            tv.text = out.toString()
        }
    }
}