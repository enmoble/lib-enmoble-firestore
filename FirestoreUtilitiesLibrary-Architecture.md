# lib-enmoble-firestore — Architecture

This document explains the architecture and “how to use” flow for [`lib-enmoble-firestore`](README.md:1)

---

## Goals

The library focuses on providing **reusable Firestore primitives** that enable “application-level” Firestore workflows:

- Provide stable, documented CRUD helpers
- Provide safe higher-level operations:
  - **batched writes** with chunking, retries, and optional write locking
  - **ancestor document explicit creation** to avoid Firestore non-existent ancestor limitations (which makes nodes invisible to queries)
- Provide a small reactive layer for **one-shot** query reads and listener/polling infrastructure
- Other utilities like rules based searching / matching of strings, date-time-timestamp-UTC-Local conversions etc 

---

## Code structure

### Library module

All core library code lives under:

- `:lib-enmoble-firestore`
- package: `com.enmoble.common.firestore`

Entry points:

- [`FirestoreManager`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:1)
- [`FirestoreReactive`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt:1)

Utility packages:

- [`com.enmoble.common.firestore.util`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt:1)
  - String + time utilities:
    - [`StringUtils.kt`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt:1)
  - Rules-based content matching:
    - [`ContentMatcher`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/ContentMatcher.kt:1)
  - Polling support persistence:
    - [`RefreshTimesManager`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/RefreshTimesManager.kt:1)
  - Generic collection/ref-count helpers:
    - [`CollectionUtils`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/CollectionUtils.kt:1)

- [`com.enmoble.common.firestore.util.datastruct`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/datastruct/SimpleLRUCache.kt:1)
  - [`SimpleLRUCache`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/datastruct/SimpleLRUCache.kt:1)

### Demo module

The demo app exists to show “real” sequences that will work when connected to Firestore:

- `:demo-app:app`

Main entry:

- [`MainActivity`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/MainActivity.kt:1)

Demo model / helpers:

- [`Tweet`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/Tweet.kt:1)
- [`TweetDemoFirestore`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/TweetDemoFirestore.kt:1)

---

## Core concepts

### 1) Initialization

Before any library calls, you must initialize Firestore:

- [`FirestoreManager.initFirestore()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:71)

This configures offline persistence and cache sizing.

**Demo reference:** [`MainActivity.onCreate()`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/MainActivity.kt:33)

---

### 2) Basic CRUD

For simplest document operations:

- Write: [`FirestoreManager.setDocument()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:214)
- Read:  [`FirestoreManager.getDocument()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:240)

**Demo reference:** Level-1 section in [`MainActivity`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/MainActivity.kt:51)

---

### 3) Fixing “non-existent ancestor documents” (explicit ancestors)

Firestore permits writing to deep paths without explicitly creating intermediate documents, but those “ancestor” docs can be treated as non-existent and may not appear/behave as expected in queries and console navigation.

The library addresses this by:

- writing a `subcollections` field on each parent document
- making missing parent docs explicit
- optionally writing the leaf document

This is done via:

- [`FirestoreManager.setDocumentAndUpdateAncestors()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:391)

**Demo reference:** Level-1 step “setDocumentAndUpdateAncestors” in [`MainActivity`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/MainActivity.kt:70)

---

### 4) Batched writes: chunking + optional lock + retries

Firestore has transaction limits and concurrency considerations. The library provides:

- chunked write transactions
- optional write-lock document per collection-path
- retry on lock and transaction aborts

API:

- [`FirestoreManager.batchedWriteOrUpdateAllDocuments()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:431)

Model requirement:

- Implement [`DbStorable`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/DbStorable.kt:12)

**Demo reference:** In tweet demo batch write step:
- [`TweetDemoFirestore.batchedWriteOrUpdateTweets()`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/TweetDemoFirestore.kt:67)
- called from Level-2 step “batchedWriteOrUpdateTweets” in [`MainActivity`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/MainActivity.kt:107)

---

### 5) One-shot query flows (read → stream items)

The library includes a small reactive helper for “read once and emit items as a Flow”:

- [`FirestoreReactive.oneShotCollectionQueryFlow()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt:143)

This is useful for “query and stream” style composition without requiring snapshot listeners.

Related:
- List-emitting variant (single emission of `List<Pair<T,String>>`):
  - [`FirestoreReactive.oneShotCollectionQueryListFlow()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt:172)
- Stable caching key helper:
  - [`FirestoreReactive.queryKey()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt:622)

Lifecycle note (listener flow caching):
- The library has internal cached listener flows (polling or snapshot-listener based) that can be retained with `danglingRef=true`.
- When you retain dangling refs, you must periodically call:
  - [`FirestoreReactive.garbageCollect()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt:587)
- You can always clear all cached flows explicitly via:
  - [`FirestoreReactive.close()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt:605)

**Demo reference:** Level-2 step “oneShotCollectionQueryFlow → stream tweets” in:
- [`MainActivity`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/MainActivity.kt:144)

---

## Generic utilities shipped with this library

This repo intentionally includes a small set of generic, reusable utilities that are commonly needed alongside Firestore workflows.

### Content rules / keyword matching

- [`ContentMatcher`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/ContentMatcher.kt:1)

Conceptually, a “rule” is just a list of keywords + a match strategy:
- Strategies are represented by [`ContentMatcher.MATCH_TYPE`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/ContentMatcher.kt:31) (`ANY`, `ALL`, `ALL_N_AND_ANY`, `REGEX`)
- Inverse (reject) tokens are supported by prefixing a keyword with [`ContentMatcher.MATCH_TOKEN_INVERSE`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/ContentMatcher.kt:162)
  - Example: `listOf("kotlin", "<!NOT>java")`

### Time / timestamp helpers

These are Kotlin extension utilities useful for logging, time-bucket partitioning, and converting between local and UTC time strings:

- Epoch millis → UTC string: [`Long.millisToUtc()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt:166)
- Epoch millis → local time string: [`Long.millisToLocalTime()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt:175)
- Parse local/UTC formatted timestamps: [`String.localTimeToMillis()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt:187), [`String.utcTimeToMillis()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt:199)
- Parse ISO-8601 timestamps: [`String.iso8601TimeToMillis()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/StringUtils.kt:210)

### In-memory cache utility

- [`SimpleLRUCache`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/util/datastruct/SimpleLRUCache.kt:1)

A coroutine-safe LRU cache that’s useful for caching computed data, parsed documents, or expensive transformations.

---

## Recommended integration sequence (what a dev should do)

1. Initialize Firestore once at app startup
   → [`FirestoreManager.initFirestore()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:88)

2. Start with basic set/get for a known doc path
   → [`FirestoreManager.setDocument()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:145)
   → [`FirestoreManager.getDocument()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:190)

3. If you write deep trees, adopt ancestor-explicit writes
   → [`FirestoreManager.setDocumentAndUpdateAncestors()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:373)

4. If you write many docs, use batched writes
   → [`FirestoreManager.batchedWriteOrUpdateAllDocuments()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreManager.kt:501)

5. For “query once then stream items”, use one-shot flows
   → [`FirestoreReactive.oneShotCollectionQueryFlow()`](lib-enmoble-firestore/lib-enmoble-firestore/src/main/java/com/enmoble/common/firestore/FirestoreReactive.kt:143)

---

## Demo architecture overview (Tweet example)

Demo schema:

- tweets are stored at:
  - `demo/twitter-data/{handle}/tweets/{tweetId}`

This enables:

- per-handle reads using normal collection queries
- global oldest/latest using `collectionGroup("tweets")`

Implementation:

- [`TweetDemoFirestore`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/TweetDemoFirestore.kt:1)

Execution order:

- write tweet1 using ancestor-update write
- batch write tweet2/tweet3 using batched writer
- read by timestamp
- read oldest/latest via `collectionGroup`
- read again via one-shot Flow

See Level-2 steps in [`MainActivity`](lib-enmoble-firestore/demo-app/app/src/main/java/com/enmoble/common/firestore/demo/MainActivity.kt:89)
