package com.enmoble.common.firestore.demo

import com.enmoble.common.firestore.DbStorable

/**
 * Minimal demo model representing a tweet-like Firestore document.
 *
 * This is intentionally a simplified, non-proprietary version of the internal TweetDb concept:
 * - [handle] represents the author identifier
 * - [id] is the Firestore document id (used as [DbStorable.dbKey])
 * - [timestamp] is used for range queries and ordering
 * - [text] is the “tweet body”
 */
data class Tweet(
    val handle: String = "",
    val id: String = "",
    val timestamp: Long = 0L,
    val text: String = "",
) : DbStorable {

    override val dbKey: String
        get() = id
}