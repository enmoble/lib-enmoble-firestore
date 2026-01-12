# lib-enmoble-firestore

`lib-enmoble-firestore` is a public Kotlin/Android library that provides **reusable building blocks** for Firebase Firestore operations.

The core value-add is **not** “wrapping Firestore calls”, but providing higher-level primitives that solve real issues you hit in production:

- Reliable **batched writes** with:
  - chunking to stay within transaction limits
  - optional **write-locking** to reduce concurrent writers on the same collection
  - retries on transaction aborts / contentions
- A practical solution for Firestore’s **“non-existent ancestor documents”** behavior, by explicitly creating ancestor documents and tracking subcollections.

This repo also includes a demo app that runs a real write → read sequence against your Firestore project once you add Firebase configuration.

## Modules

- `:lib-enmoble-firestore` — the reusable library (package `com.enmoble.common.firestore`)
- `:demo-app:app` — demo Android app (package `com.enmoble.common.firestore.demo`)

## Key APIs (what you’ll actually use)

### Initialization

- [`FirestoreManager.initFirestore()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L141)

### Core CRUD helpers

- Read a document:
  - [`FirestoreManager.getDocument()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L258)
- Write/merge a document:
  - [`FirestoreManager.setDocument()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L210)

Also available:
- Check existence (document or collection path):
  - [`FirestoreManager.fastCheckIfNodeExists()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L305)
- Document existence convenience:
  - [`FirestoreManager.doesDocumentExist()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L397)
- Read a full query result once:
  - [`FirestoreManager.getAllDocuments()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L420)

### Fixing Firestore “non-existent ancestor documents”

Firestore allows writing a deep path even if intermediate ancestors do not exist, but those ancestors may be treated as **non-existent** and can become invisible in certain queries/console navigation unless made explicit.

This library’s solution is:

- [`FirestoreManager.setDocumentAndUpdateAncestors()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L527)

It:
- writes the leaf document (optional), and
- ensures intermediate ancestor documents are made **explicit**, and
- maintains a `subcollections` field (see [`FirestoreManager.DB_FIELD_SUBCOLLECTIONS`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L83)) so your app can enumerate known subcollections.

Related helpers:
- Create all explicit ancestors for an existing document path:
  - [`FirestoreManager.explicitlyCreateDocumentPath()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L623)
- Enumerate known subcollections (based on `subcollections` field):
  - [`FirestoreManager.getAllSubcollectionIds()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L450)

### Batched writes (retries + optional locking)

- [`FirestoreManager.batchedWriteOrUpdateAllDocuments()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L679)

Your model should implement:

- [`DbStorable`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/DbStorable.kt#L12)

Also available:
- Compute the lock document id used for a collection path:
  - [`FirestoreManager.getWriteLockDocId()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L638)
- Batch-write docs *or* batch-update fields via Firestore [WriteBatch]:
  - [`FirestoreManager.batchedWriteDocsOrUpdateFields()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L831)

### One-shot reactive reads (Flows)

For cold “read once then emit stream of items” flows:

- [`FirestoreReactive.oneShotCollectionQueryFlow()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt#L143)
- List-emitting variant (single emission of `List<Pair<T,String>>`):
  - [`FirestoreReactive.oneShotCollectionQueryListFlow()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt#L172)

Query cache keys:
- [`FirestoreReactive.queryKey()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt#L622)

Flow cache lifecycle:
- If you use internal listener flows with `danglingRef = true`, call:
  - [`FirestoreReactive.garbageCollect()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt#L587)
- To clear all cached flows (and refCounts) explicitly:
  - [`FirestoreReactive.close()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt#L605)

### Misc helpers

- System language helpers (useful for locale-aware data partitioning):
  - [`FirestoreManager.getSystemDefaultLanguage()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L172)
  - [`FirestoreManager.getSystemDefaultLanguageTag()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt#L182)

### Utilities included in this library

This module also ships a few generic utilities that are widely useful even outside Firestore:

- Content rules / keyword matching:
  - [`ContentMatcher`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/ContentMatcher.kt#L1)
    - Supports `ANY` / `ALL` / `ALL_N_AND_ANY` / `REGEX` styles via [`ContentMatcher.MATCH_TYPE`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/ContentMatcher.kt#L31)
    - Supports inverse (reject) tokens via [`ContentMatcher.MATCH_TOKEN_INVERSE`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/ContentMatcher.kt#L151)
- Date/time conversion helpers:
  - Epoch millis → UTC string: [`Long.millisToUtc()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt#L166)
  - Epoch millis → local time string: [`Long.millisToLocalTime()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt#L175)
  - Parse local/UTC formatted timestamps: [`String.localTimeToMillis()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt#L187), [`String.utcTimeToMillis()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt#L199)
  - Parse ISO-8601 timestamps: [`String.iso8601TimeToMillis()`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt#L210)
- A coroutine-safe in-memory LRU cache:
  - [`SimpleLRUCache`](lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/datastruct/SimpleLRUCache.kt#L1)

## Demo app (recommended starting point)

The demo app is written to be **real**: once you add `google-services.json` and enable Firestore, it will:

- Level-1: do simplest “write then read” operations
- Level-2: run a realistic tweet-like workflow:
  - single Tweet write (ancestor updates)
  - batched Tweet write (with optional write lock + retry behavior)
  - Tweet reads by timestamp
  - collectionGroup queries (oldest/latest across handles)
  - `oneShotCollectionQueryFlow()` streaming read of the tweets it just wrote

Start here:

- [`demo MainActivity`](demo-app/app/src/main/java/com/enmoble/common/firestore/demo/MainActivity.kt#L1)

Supporting demo code:

- [`Tweet`](demo-app/app/src/main/java/com/enmoble/common/firestore/demo/Tweet.kt#L1)
- [`TweetDemoFirestore`](demo-app/app/src/main/java/com/enmoble/common/firestore/demo/TweetDemoFirestore.kt#L1)

## Build

```bash
./gradlew :lib-enmoble-firestore:test :demo-app:app:assembleDebug
```

## Firebase setup for demo app

To run the demo against your Firestore DB:

1. Create a Firebase project
2. Add an Android app in Firebase Console
3. Download `google-services.json`
4. Place it under `demo-app/app/google-services.json`
5. Enable Firestore in Firebase Console