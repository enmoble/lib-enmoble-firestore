package com.enmoble.common.firestore.util

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestoreException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Default local/UTC datetime format used by conversion helpers. */
const val DEFAULT_DATETIME_FORMAT: String = "yyyy-MM-dd HH:mm:ss:SSS"

/**
 * Removes all special characters from this string, except those provided in [exceptThese].
 *
 * Example:
 * - `"hello@world".stripSpecialChars()` -> `"helloworld"`
 * - `"hello@world".stripSpecialChars("@")` -> `"hello@world"`
 *
 * @param exceptThese Special characters to keep.
 * @return sanitized string.
 */
fun String.stripSpecialChars(exceptThese: String = ""): String {
    var regex = "^a-zA-Z0-9"
    for (ch in exceptThese.toCharArray()) {
        regex += if (!ch.isLetterOrDigit() && ch != ' ') "\\$ch" else "$ch"
    }
    regex = "[$regex]"
    return replace(Regex(regex), "")
}

/**
 * Converts this string to camelCase by splitting on non-alphanumeric characters.
 *
 * Examples:
 * - `"-- this is Team Elation's server ))".toCamelCase()` -> `"thisIsTeamElationsServer"`
 * - `"how's this for a $name??! yeah".toCamelCase()` -> `"howsThisForANameYeah"`
 *
 * @param capitalizeFirst If true, capitalizes the first character (PascalCase).
 */
fun String.toCamelCase(capitalizeFirst: Boolean = false): String {
    val words = split(Regex("[^a-zA-Z0-9]+"))
    val builder = StringBuilder()

    for ((index, word) in words.withIndex()) {
        if (word.isNotEmpty()) {
            builder.append(if (!capitalizeFirst && index == 0) word[0] else word[0].uppercaseChar())
            builder.append(word.substring(1))
        }
    }
    return builder.toString()
}

/** Convenience for PascalCase conversion. */
fun String.toPascalCase(): String = toCamelCase(true)

/**
 * Computes a stable SHA-256 hash for this string.
 *
 * @return lowercase hex-encoded SHA-256 digest.
 */
fun String.sha256Hash(): String {
    val bytes = toByteArray()
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.fold("") { str, b -> str + "%02x".format(b) }
}

/**
 * Truncates a string by preserving start and end and inserting [delim] in the middle.
 *
 * Example:
 * - `"0xabcdefgh123456789".trunc()` -> `"0xabcd...789"`
 *
 * @param maxLen Maximum returned length (including delimiter).
 * @param delim Delimiter inserted in the middle.
 */
fun String.trunc(maxLen: Int = 12, delim: String = "..."): String {
    val truncLen = maxLen - delim.length
    val left = truncLen - (truncLen / 2)
    val right = truncLen / 2
    return if (length < maxLen) this else take(left) + delim + takeLast(right)
}

/**
 * Truncates only the last segment of a slash-separated path.
 */
fun String.truncPath(maxLen: Int = 12): String =
    substringBeforeLast("/") + "/" + substringAfterLast("/").trunc(maxLen)

/**
 * Truncates this string such that when combined with [other], the total fits in [maxLen].
 */
fun String.truncToFitOther(other: String, maxLen: Int = 35, delim: String = ".."): String =
    trunc(maxLen - other.length, delim)

/**
 * Returns a single-line version of this string (removes newlines and tabs) and caps length.
 */
fun String.oneLine(maxLen: Int = 80): String {
    val out = if (length < maxLen) this else take(maxLen)
    return out.replace(Regex("[\n\t]"), "")
}

/**
 * Generic "is empty" helper for nullable values.
 *
 * Note: this is intentionally **not** `inline` because Kotlin forbids recursive `inline` functions, and unqualified
 * calls like `isEmpty()` inside a smart-cast branch can be resolved back to this extension, creating recursion.
 */
fun Any?.isEmpty(): Boolean = when (this) {
    null -> true

    // Avoid calling `isEmpty()` here because that can be resolved back to this extension (recursion) depending on imports.
    is String -> this.length == 0
    is CharSequence -> this.length == 0

    is Collection<*> -> this.size == 0
    is Map<*, *> -> this.size == 0
    is Array<*> -> this.size == 0

    is IntArray -> this.size == 0
    is ByteArray -> this.size == 0
    is CharArray -> this.size == 0
    is ShortArray -> this.size == 0
    is LongArray -> this.size == 0
    is FloatArray -> this.size == 0
    is DoubleArray -> this.size == 0
    is BooleanArray -> this.size == 0

    else -> false
}

/** Negation of [isEmpty]. */
fun Any?.isNotEmpty(): Boolean = !isEmpty()

/** Nullable string emptiness helper. */
fun isEmpty(s: String?): Boolean = (s == null || s.isEmpty())

/** Nullable collection emptiness helper. */
fun isEmpty(c: Collection<Any>?): Boolean = (c == null || c.isEmpty())

/**
 * Returns the first non-empty string found in [strings], or null if none found.
 */
fun firstNonEmptyOrNull(vararg strings: String?): String? =
    strings.firstOrNull { !it.isNullOrBlank() }

/**
 * Returns the first non-empty string found in [strings], or empty string if none found.
 */
fun firstNonEmpty(vararg strings: String?): String =
    firstNonEmptyOrNull(*strings) ?: ""

/**
 * Returns the first non-null object in [objs], or null if all are null.
 */
fun <T> firstNonNull(vararg objs: T): T? =
    objs.firstOrNull { it != null }

/**
 * Converts epoch milliseconds to a UTC formatted time string.
 */
fun Long.millisToUtc(formatPattern: String = DEFAULT_DATETIME_FORMAT): String {
    val instant = Instant.ofEpochMilli(this)
    val formatter = DateTimeFormatter.ofPattern(formatPattern).withZone(ZoneId.of("UTC"))
    return formatter.format(instant)
}

/**
 * Converts epoch milliseconds to a local formatted time string.
 */
fun Long.millisToLocalTime(
    zoneId: ZoneId = ZoneId.systemDefault(),
    formatPattern: String = DEFAULT_DATETIME_FORMAT,
): String {
    val instant = Instant.ofEpochMilli(this)
    val formatter = DateTimeFormatter.ofPattern(formatPattern).withZone(zoneId)
    return formatter.format(instant)
}

/**
 * Parses this local time (formatted with [formatPattern]) into epoch milliseconds.
 */
fun String.localTimeToMillis(
    zoneId: ZoneId = ZoneId.systemDefault(),
    formatPattern: String = DEFAULT_DATETIME_FORMAT,
): Long {
    val formatter = DateTimeFormatter.ofPattern(formatPattern)
    val localDateTime = LocalDateTime.parse(this, formatter)
    return localDateTime.atZone(zoneId).toInstant().toEpochMilli()
}

/**
 * Parses this UTC time (formatted with [formatPattern]) into epoch milliseconds.
 */
fun String.utcTimeToMillis(formatPattern: String = DEFAULT_DATETIME_FORMAT): Long {
    val formatter = DateTimeFormatter.ofPattern(formatPattern)
    val localDateTime = LocalDateTime.parse(this, formatter)
    return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
}

/**
 * Parses ISO-8601 timestamp string (e.g. "2025-01-01T00:00:00Z") into epoch milliseconds.
 *
 * @return milliseconds, or null if parsing fails.
 */
fun String.iso8601TimeToMillis(): Long? =
    try {
        Instant.parse(this).toEpochMilli()
    } catch (e: Exception) {
        logException("StringUtils", e, "iso8601TimeToMillis()")
        null
    }

/**
 * Returns whether the two dates (formatted with [DEFAULT_DATETIME_FORMAT]) represent the same day.
 */
fun areSameDate(date1_InDefaultFormat: String, date2_InDefaultFormat: String): Boolean {
    if (date1_InDefaultFormat.length != date2_InDefaultFormat.length ||
        date1_InDefaultFormat.length < DEFAULT_DATETIME_FORMAT.length
    ) {
        return false
    }
    return date1_InDefaultFormat.substring(0, 10) == date2_InDefaultFormat.substring(0, 10)
}

/**
 * Formats an exception into a stable error string for logs.
 */
fun errStr(e: Throwable, prefix: String = ""): String =
    "$prefix -> EXCEPTION: ${e.message} / STACK: ${e.stackTraceToString()}"

/**
 * Logs an exception with an optional Firestore error code prefix.
 *
 * @param logtag Log tag.
 * @param e Throwable to log.
 * @param funName Calling function name.
 * @param prefix Extra context appended to the message.
 */
fun logException(logtag: String, e: Throwable, funName: String, prefix: String = "") {
    val code = if (e is FirebaseFirestoreException) "FirestoreException.Code=[${e.code}]: " else " "
    // `android.util.Log` can throw in pure JVM unit test environments if not running under Robolectric.
    // Logging should never fail the caller.
    try {
        Log.e(logtag, "$funName: $code${errStr(e, prefix)}")
    } catch (_: Throwable) {
        // no-op
    }
}

/**
 * Returns a formatted diff between two sets.
 *
 * @return Pair of (human readable diff string, list of differing fields).
 */
fun fieldsDiff(left: Set<String>?, right: Set<String>?): Pair<String, List<String>> {
    if (left == null && right == null) return "" to emptyList()
    if (left == null) return "[-->]$right" to right!!.toList()
    if (right == null) return "[<--]$left" to left.toList()

    val out = ArrayList<String>()
    val outStr = StringBuilder("{ ")
    for (it in left.sorted()) if (!right.contains(it)) {
        outStr.append("[<-]$it, ")
        out.add(it)
    }
    for (it in right.sorted()) if (!left.contains(it)) {
        outStr.append("[->]$it, ")
        out.add(it)
    }
    outStr.append(" }")
    return outStr.toString() to out
}