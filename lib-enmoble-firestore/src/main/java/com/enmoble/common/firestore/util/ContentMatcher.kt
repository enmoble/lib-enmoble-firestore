package com.enmoble.common.firestore.util

/**
 * Utility for matching free-form text against a set of keyword "rules".
 *
 * This is intentionally **not Firestore-specific**; it just lives in this library because it’s useful for higher-level
 * workflows in the surrounding ecosystem.
 *
 * ## Core ideas
 *
 * 1) You provide a list of [keywords] (strings) and an input [text].
 *
 * 2) You choose a [MATCH_TYPE] that defines how keywords are evaluated:
 * - "ANY": match if **any** keyword matches
 * - "ALL": match if **all** keywords match
 * - "ALL_N_AND_ANY": match if **all of the first N** match, and **at least one additional** keyword matches
 * - "REGEX": each keyword is treated as a regex and must match the input
 *
 * 3) You can mark a keyword as **inverse** using [MATCH_TOKEN_INVERSE].
 *    Example: `<!NOT>spoiler` means "the input must NOT contain `spoiler`".
 *
 * ## Whole-word vs part-word
 * - If [MATCH_TYPE.wholeWord] is true, we treat each keyword as a whole-word match using word boundaries (`\\b...\\b`).
 * - If false, we use substring matching (`String.contains`).
 *
 * ## Case-sensitivity
 * - If [MATCH_TYPE.caseSensitive] is false, matching is performed on lowercased forms of both text and keyword.
 */
open class ContentMatcher {

    /**
     * Supported matching strategies.
     *
     * Note: [ANY] and [ALL] are modeled as data classes so callers can dynamically choose case/wholeWord behavior.
     * The convenience singleton objects (`ANY_*`, `ALL_*`) cover common combinations.
     */
    sealed class MATCH_TYPE(
        open val id: String = "MATCH_TYPE",
        open val caseSensitive: Boolean = true,
        open val wholeWord: Boolean = true,
    ) {
        /** Match succeeds if ANY (non-inverse) keyword matches and no inverse keyword matches. */
        data class ANY(
            override val caseSensitive: Boolean = true,
            override val wholeWord: Boolean = true,
        ) : MATCH_TYPE("ANY", caseSensitive, wholeWord)

        /** Match succeeds if ALL (non-inverse) keywords match and no inverse keyword matches. */
        data class ALL(
            override val caseSensitive: Boolean = true,
            override val wholeWord: Boolean = true,
        ) : MATCH_TYPE("ALL", caseSensitive, wholeWord)

        /** Match any whole-word token, case-insensitive. */
        data object ANY_WHOLE_WORDS_NO_CASE :
            MATCH_TYPE("ANY_WHOLE_WORDS_NO_CASE", caseSensitive = false, wholeWord = true)

        /** Match any substring, case-insensitive. */
        data object ANY_PART_WORDS_NO_CASE :
            MATCH_TYPE("ANY_PART_WORDS_NO_CASE", caseSensitive = false, wholeWord = false)

        /** Match any whole-word token, case-sensitive. */
        data object ANY_WHOLE_WORDS_WITH_CASE :
            MATCH_TYPE("ANY_WHOLE_WORDS_WITH_CASE", caseSensitive = true, wholeWord = true)

        /** Match any substring, case-sensitive. */
        data object ANY_PART_WORDS_WITH_CASE :
            MATCH_TYPE("ANY_PART_WORDS_WITH_CASE", caseSensitive = true, wholeWord = false)

        /** Match all whole-word tokens, case-insensitive. */
        data object ALL_WHOLE_WORDS_NO_CASE :
            MATCH_TYPE("ALL_WHOLE_WORDS_NO_CASE", caseSensitive = false, wholeWord = true)

        /** Match all substrings, case-insensitive. */
        data object ALL_PART_WORDS_NO_CASE :
            MATCH_TYPE("ALL_PART_WORDS_NO_CASE", caseSensitive = false, wholeWord = false)

        /** Match all whole-word tokens, case-sensitive. */
        data object ALL_WHOLE_WORDS_WITH_CASE :
            MATCH_TYPE("ALL_WHOLE_WORDS_WITH_CASE", caseSensitive = true, wholeWord = true)

        /** Match all substrings, case-sensitive. */
        data object ALL_PART_WORDS_WITH_CASE :
            MATCH_TYPE("ALL_PART_WORDS_WITH_CASE", caseSensitive = true, wholeWord = false)

        /**
         * Treat each keyword as a regex; match succeeds only if ALL regex patterns match.
         *
         * Note: inverse token handling is not applied for [REGEX] currently; use negative lookaheads in regex
         * patterns if needed.
         */
        data object REGEX : MATCH_TYPE("REGEX")

        /**
         * "ALL_N_AND_ANY" strategy:
         * - First [matchConditionalCount] keywords are mandatory (ALL must match)
         * - Remaining keywords are optional, but at least one must match
         *
         * Inverse tokens are still respected (i.e., they can force failure if found in the text).
         */
        data class ALL_N_AND_ANY(
            val matchConditionalCount: Int,
            override val caseSensitive: Boolean = true,
            override val wholeWord: Boolean = true,
        ) : MATCH_TYPE("ALL_N_AND_ANY", caseSensitive, wholeWord)

        companion object {
            /**
             * Parses a match type from a string identifier.
             *
             * This is useful when match rules are serialized (e.g., stored in JSON / DB).
             *
             * @param str match type string, e.g. `"ANY"`, `"ALL_PART_WORDS_NO_CASE"`, `"REGEX"`, `"ALL_N_AND_ANY"`.
             * @param caseSensitive used only for `"ANY"` and `"ALL"` and `"ALL_N_AND_ANY"` (objects already encode it).
             * @param wholeWord used only for `"ANY"` and `"ALL"` and `"ALL_N_AND_ANY"`.
             * @param conditionalCount required for `"ALL_N_AND_ANY"` (must be `>= 0`).
             */
            fun fromString(
                str: String,
                caseSensitive: Boolean,
                wholeWord: Boolean,
                conditionalCount: Int = -1,
            ): MATCH_TYPE = when (str) {
                "ANY" -> ANY(caseSensitive, wholeWord)
                "ALL" -> ALL(caseSensitive, wholeWord)

                "ANY_WHOLE_WORDS_NO_CASE" -> ANY_WHOLE_WORDS_NO_CASE
                "ANY_PART_WORDS_NO_CASE" -> ANY_PART_WORDS_NO_CASE
                "ANY_WHOLE_WORDS_WITH_CASE" -> ANY_WHOLE_WORDS_WITH_CASE
                "ANY_PART_WORDS_WITH_CASE" -> ANY_PART_WORDS_WITH_CASE

                "ALL_WHOLE_WORDS_NO_CASE" -> ALL_WHOLE_WORDS_NO_CASE
                "ALL_PART_WORDS_NO_CASE" -> ALL_PART_WORDS_NO_CASE
                "ALL_WHOLE_WORDS_WITH_CASE" -> ALL_WHOLE_WORDS_WITH_CASE
                "ALL_PART_WORDS_WITH_CASE" -> ALL_PART_WORDS_WITH_CASE

                "REGEX" -> REGEX

                "ALL_N_AND_ANY" ->
                    if (conditionalCount >= 0) ALL_N_AND_ANY(conditionalCount, caseSensitive, wholeWord)
                    else throw IllegalArgumentException("conditionalCount param is mandatory for ALL_N_AND_ANY")

                else -> throw IllegalArgumentException("Invalid MATCH_TYPE: $str")
            }
        }
    }

    companion object {
        /**
         * Prefix that marks a keyword as an inverse ("must NOT match") constraint.
         *
         * Example: `listOf("kotlin", "<!NOT>java")` means:
         * - "kotlin" should match (based on match type)
         * - "java" must NOT be present; if present, match fails
         */
        const val MATCH_TOKEN_INVERSE: String = "<!NOT>"

        /**
         * Returns true if [text] matches [keywords] using [matchMethod].
         *
         * Rules:
         * - If [keywords] is empty, this returns true (i.e., "match all content").
         * - Inverse tokens:
         *   - Any inverse keyword found in the input forces failure.
         * - For [MATCH_TYPE.REGEX]:
         *   - Each keyword is treated as a regex and must match (`Regex.containsMatchIn`).
         * - For ALL/ANY/ALL_N_AND_ANY:
         *   - Matching respects [MATCH_TYPE.caseSensitive] and [MATCH_TYPE.wholeWord].
         *
         * @param keywords list of keywords (some may be inverse tokens).
         * @param text input to match against.
         * @param matchMethod matching strategy.
         * @param caseSensitive overrides the default from [matchMethod].
         * @param wholeWord overrides the default from [matchMethod].
         */
        fun matchesWith(
            keywords: List<String>,
            text: String,
            matchMethod: MATCH_TYPE,
            caseSensitive: Boolean = matchMethod.caseSensitive,
            wholeWord: Boolean = matchMethod.wholeWord,
        ): Boolean {
            if (keywords.isEmpty()) return true

            val input = if (caseSensitive) text else text.lowercase()
            var matchCnt = 0

            fun normalize(word: String): String = if (caseSensitive) word else word.lowercase()

            for (keyword in keywords) {
                val inverse = keyword.startsWith(MATCH_TOKEN_INVERSE)
                val rawWord = if (inverse) keyword.removePrefix(MATCH_TOKEN_INVERSE).trim() else keyword
                val wordToMatch = normalize(rawWord)

                when (matchMethod) {
                    is MATCH_TYPE.ANY,
                    MATCH_TYPE.ANY_WHOLE_WORDS_NO_CASE,
                    MATCH_TYPE.ANY_PART_WORDS_NO_CASE,
                    MATCH_TYPE.ANY_WHOLE_WORDS_WITH_CASE,
                    MATCH_TYPE.ANY_PART_WORDS_WITH_CASE,
                    -> {
                        val (res, done) = matchAny(input, wordToMatch, inverse, caseSensitive, wholeWord)
                        if (done) return res
                    }

                    is MATCH_TYPE.ALL,
                    MATCH_TYPE.ALL_WHOLE_WORDS_NO_CASE,
                    MATCH_TYPE.ALL_PART_WORDS_NO_CASE,
                    MATCH_TYPE.ALL_WHOLE_WORDS_WITH_CASE,
                    MATCH_TYPE.ALL_PART_WORDS_WITH_CASE,
                    -> {
                        val (res, done) = matchAll(input, wordToMatch, inverse, matchCnt, keywords.size, caseSensitive, wholeWord)
                        if (done) return res
                        matchCnt++
                    }

                    MATCH_TYPE.REGEX -> {
                        val regex = Regex(wordToMatch)
                        if (!regex.containsMatchIn(input)) return false
                        matchCnt++
                    }

                    is MATCH_TYPE.ALL_N_AND_ANY -> {
                        val (res, done) =
                            if (matchCnt < matchMethod.matchConditionalCount) {
                                matchAll(input, wordToMatch, inverse, matchCnt, keywords.size, caseSensitive, wholeWord)
                            } else {
                                matchAny(input, wordToMatch, inverse, caseSensitive, wholeWord)
                            }

                        if (done) return res
                        matchCnt += if (res) 1 else 0
                    }
                }
            }

            // REGEX and ALL require all N keywords to "pass". For ALL_N_AND_ANY, falling through means failure.
            return matchCnt == keywords.size
        }

        /**
         * "ANY" matching for a single keyword.
         *
         * @return Pair(matchResult, finishedMatching)
         * - If keyword is found:
         *   - result = !inverse
         *   - finished = true
         * - If keyword is not found:
         *   - result = false (no decision yet)
         *   - finished = false
         */
        fun matchAny(
            inp: String,
            searchWrd: String,
            inverse: Boolean,
            matchCase: Boolean,
            wholeWord: Boolean,
        ): Pair<Boolean, Boolean> {
            val searchWord = if (matchCase) searchWrd else searchWrd.lowercase()
            val input = if (matchCase) inp else inp.lowercase()

            val found = if (wholeWord) {
                val pattern = "\\b${Regex.escape(searchWord)}\\b"
                Regex(pattern).containsMatchIn(input)
            } else {
                input.contains(searchWord)
            }

            return if (found) (!inverse to true) else (false to false)
        }

        /**
         * "ALL" matching for a single keyword.
         *
         * This function also applies inverse-token semantics:
         * - If inverse==true and the word IS found → immediate failure
         * - If inverse==false and the word is NOT found → immediate failure
         *
         * @return Pair(matchResult, finishedMatching)
         * - matchResult indicates whether the rule is still satisfied so far
         * - finishedMatching indicates whether the overall matching can terminate early
         */
        fun matchAll(
            inp: String,
            searchWrd: String,
            inverse: Boolean,
            matchCntSoFar: Int,
            expectedMatchCnt: Int,
            matchCase: Boolean,
            wholeWord: Boolean,
        ): Pair<Boolean, Boolean> {
            val searchWord = if (matchCase) searchWrd else searchWrd.lowercase()
            val input = if (matchCase) inp else inp.lowercase()

            val found = if (wholeWord) {
                val pattern = "\\b${Regex.escape(searchWord)}\\b"
                Regex(pattern).containsMatchIn(input)
            } else {
                input.contains(searchWord)
            }

            return if (inverse && found || !inverse && !found) {
                false to true
            } else if (matchCntSoFar + 1 == expectedMatchCnt) {
                true to true
            } else {
                true to false
            }
        }

        /**
         * Searches through a list of "text container" objects, looking for occurrences of any keyword, and returns the
         * matching objects **plus context** (pre/post neighbors).
         *
         * This is useful for UI "highlight search hits with surrounding context" use-cases.
         *
         * Inverse keywords (prefixed with [MATCH_TOKEN_INVERSE]) are treated as reject tokens:
         * any container containing a reject token is excluded from both matching and context windows.
         *
         * @param input list of container objects
         * @param getContents function that extracts text from a container
         * @param searchWords list of keywords (may include inverse tokens)
         * @param preCount number of items before a match to include
         * @param postCount number of items after a match to include
         * @param inverse_token token prefix (defaults to [MATCH_TOKEN_INVERSE])
         * @param caseSensitive controls string comparisons in this helper (independent from [MATCH_TYPE])
         */
        fun <T> matchingContextInTextContents(
            input: List<T>,
            getContents: (T) -> String,
            searchWords: List<String>,
            preCount: Int,
            postCount: Int,
            inverse_token: String = MATCH_TOKEN_INVERSE,
            caseSensitive: Boolean = false,
        ): List<T> {
            if (searchWords.isEmpty()) return input

            val out = LinkedHashSet<T>()

            val rejectWords = searchWords
                .filter { it.startsWith(inverse_token) }
                .map { it.removePrefix(inverse_token) }
                .map { if (caseSensitive) it else it.lowercase() }

            val searchFor = searchWords
                .filter { !it.startsWith(inverse_token) }
                .map { if (caseSensitive) it else it.lowercase() }

            val inverseExcluded = omitRejectWords(input, getContents, rejectWords, caseSensitive)

            for ((i, obj) in inverseExcluded.withIndex()) {
                val contents = if (caseSensitive) getContents(obj) else getContents(obj).lowercase()
                if (searchFor.any { contents.contains(it) }) {
                    val start = (i - preCount).coerceAtLeast(0)
                    val end = (i + postCount).coerceAtMost(inverseExcluded.size - 1)

                    for (j in start..end) {
                        val kept = omitRejectWords(listOf(inverseExcluded[j]), getContents, rejectWords, caseSensitive)
                        if (kept.isNotEmpty()) out.add(kept[0])

                        if (out.size == input.size) break
                    }
                }
            }

            return out.toList()
        }

        /**
         * Returns [input] excluding any items whose extracted content contains any of [omitWords].
         *
         * @param caseSensitive if false, comparisons are performed on lowercased strings.
         */
        fun <T> omitRejectWords(
            input: List<T>,
            getContents: (T) -> String,
            omitWords: List<String>,
            caseSensitive: Boolean = false,
        ): List<T> {
            return input.filter { inCue ->
                !omitWords.any { omit ->
                    val hay = if (caseSensitive) getContents(inCue) else getContents(inCue).lowercase()
                    val needle = if (caseSensitive) omit else omit.lowercase()
                    hay.contains(needle)
                }
            }
        }

        /**
         * Convenience: removes containers that match any inverse-token keyword within [searchWords].
         */
        fun <T> omitInverseTokens(
            input: List<T>,
            getContents: (T) -> String,
            searchWords: List<String>,
            caseSensitive: Boolean = false,
            inverse_token: String = MATCH_TOKEN_INVERSE,
        ): List<T> {
            val omitWords = searchWords
                .filter { it.startsWith(inverse_token) }
                .map { it.removePrefix(inverse_token) }

            return omitRejectWords(input, getContents, omitWords, caseSensitive)
        }
    }
}