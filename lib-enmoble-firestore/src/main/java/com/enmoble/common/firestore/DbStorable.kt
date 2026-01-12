package com.enmoble.common.firestore

import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * Marker interface for objects that can be stored as Firestore documents.
 *
 * Implementations must provide a stable, non-empty [dbKey] that will be used as the Firestore document id.
 *
 * Note:
 * - [dbKey] is excluded from serialization so it is not persisted as a Firestore field.
 */
@IgnoreExtraProperties
interface DbStorable {
    /**
     * Firestore document id for this item.
     *
     * This value is used to write the item at: `"$collectionPath/$dbKey"`.
     */
    @get:Exclude
    val dbKey: String
}