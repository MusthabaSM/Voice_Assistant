package com.voicefile.app.matching

import com.voicefile.app.files.FileEntry
import com.voicefile.app.files.FolderEntry
import com.voicefile.app.files.Index

data class ScoredEntry(val entry: FileEntry, val score: Double)

sealed interface MatchResult {
    /** Exactly one clear match → open immediately, no confirmation. */
    data class AutoOpen(
        val entry: FileEntry,
        val score: Double,
        /** "heard → resolved" pairs produced by vocabulary cross-verification. */
        val crossChecked: List<String> = emptyList(),
    ) : MatchResult

    /** Several plausible matches (or a whole category) → show them. */
    data class ShowList(
        val headline: String,
        val matches: List<ScoredEntry>,
        val category: Category? = null,
        val folder: FolderEntry? = null,
        /** "heard → resolved" pairs produced by vocabulary cross-verification. */
        val crossChecked: List<String> = emptyList(),
        /** True when nothing matched and these are only the *closest* candidates. */
        val closest: Boolean = false,
    ) : MatchResult

    /** Nothing usable at all (empty query / empty index / unavailable folder). */
    data class NoMatch(val reason: String) : MatchResult
}

/**
 * Ranks on-device files against a spoken query.
 *
 * Design goals (spec §2 + user requirements):
 *  - open immediately only when ONE result is a clear match,
 *  - otherwise show a ranked list,
 *  - **cross-verify** recognized words against the vocabulary of real file names
 *    on the device, so slang/variant/misheard input resolves to the best
 *    matching word (shown to the user as "heard → resolved"),
 *  - when nothing matches, still show the **closest** file names instead of a
 *    dead end,
 *  - never open a random file on an uncertain recognition result.
 */
class FileMatcher {

    companion object {
        const val OPEN_THRESHOLD = 0.72
        const val OPEN_GAP = 0.10
        const val LIST_THRESHOLD = 0.45
        const val MAX_LIST = 30
        const val MAX_CLOSEST = 8

        /**
         * Cross-verification accepts looser similarity than normal matching
         * (that is its purpose: retrieve the plausible word first), but it only
         * substitutes when the winner is clearly dominant over the runner-up.
         */
        const val RESOLVE_MIN_SIM = 0.40
        const val RESOLVE_MIN_GAP = 0.15
        const val RESOLVE_MIN_LEN = 4
    }

    fun match(query: ParsedQuery, lang: Language, index: Index): MatchResult {
        if (query.joined.isEmpty()) {
            return MatchResult.NoMatch("Nothing was recognized. Please try again.")
        }

        if (query.categoryOnly && query.category != null) {
            return matchCategory(query.category, index)
        }

        if (query.tokens.isEmpty()) {
            return MatchResult.NoMatch("Could not extract a file name from what was said.")
        }

        return matchTokens(query, lang, index)
    }

    // ---------------------------------------------------------------- categories

    private fun matchCategory(category: Category, index: Index): MatchResult {
        if (category == Category.DOWNLOADS) {
            val folder = index.folders.firstOrNull { it.isDownloadsFolder() }
            val inFolder = folder?.let { f ->
                index.files.filter { e ->
                    e.path == f.path || e.path?.startsWith(f.path + "/") == true
                }
            }.orEmpty()
            val files = (inFolder.ifEmpty { index.files.filter { it.inDownloads() } })
                .sortedByDescending { it.lastModified }
            return when {
                folder != null && files.isNotEmpty() ->
                    MatchResult.ShowList(
                        headline = "Downloads",
                        matches = files.take(MAX_LIST).map { ScoredEntry(it, 1.0) },
                        category = category,
                        folder = folder,
                    )
                files.isNotEmpty() ->
                    MatchResult.ShowList(
                        headline = "Downloads",
                        matches = files.take(MAX_LIST).map { ScoredEntry(it, 1.0) },
                        category = category,
                    )
                else ->
                    MatchResult.NoMatch(
                        "No Downloads folder is available. Add it in Folders settings.",
                    )
            }
        }

        val files = index.files.filter { matchesCategory(it, category) }
            .sortedByDescending { it.lastModified }
        if (files.isEmpty()) {
            return MatchResult.NoMatch("No matching files were found on this device.")
        }
        if (files.size == 1) {
            return MatchResult.AutoOpen(files.first(), score = 1.0)
        }
        return MatchResult.ShowList(
            headline = category.title,
            matches = files.take(MAX_LIST).map { ScoredEntry(it, 1.0) },
            category = category,
        )
    }

    // ---------------------------------------------------------------- token match

    private fun matchTokens(query: ParsedQuery, lang: Language, index: Index): MatchResult {
        // 1) Vocabulary of everything the user actually has on this device.
        val vocabulary = buildVocabulary(index, lang)

        // 2) Cross-verify each recognized word against that vocabulary. A weak or
        //    missed word (slang, accent, ASR slip) that clearly resembles ONE real
        //    file word is resolved to it and recorded for the UI.
        val crossChecked = LinkedHashSet<String>()
        val queryTokenVariants = query.tokens.map { token ->
            val variants = ArrayList(QueryNormalizer.tokenVariants(token, lang))
            val resolved = resolveToken(token, vocabulary)
            if (resolved != null) {
                variants.add(resolved.second)
                crossChecked.add("$token → ${resolved.first}")
            }
            variants.distinct()
        }
        val contentPhrase = query.tokens.joinToString(" ")

        // 3) Tokenize every file once; both the strict and the fallback pass reuse it.
        val tokenized = index.files.map { it to nameTokens(it.name, lang) }

        // 4) Strict pass: every spoken word must find its place in the file name.
        val strict = rank(tokenized, queryTokenVariants, contentPhrase, query, lenient = false)
        if (strict.isNotEmpty()) {
            val best = strict[0]
            val second = strict.getOrNull(1)
            val clearWinner = best.score >= OPEN_THRESHOLD &&
                (second == null || best.score - second.score >= OPEN_GAP)
            return if (clearWinner) {
                MatchResult.AutoOpen(best.entry, best.score, crossChecked.toList())
            } else {
                MatchResult.ShowList(
                    headline = "${strict.size} file${if (strict.size == 1) "" else "s"} match",
                    matches = strict,
                    crossChecked = crossChecked.toList(),
                )
            }
        }

        // 5) Nothing matched → closest candidates instead of a dead end.
        if (tokenized.isEmpty()) {
            return MatchResult.NoMatch(
                "No files are indexed yet. Add folders in settings, then try again.",
            )
        }
        val closest = rank(tokenized, queryTokenVariants, contentPhrase, query, lenient = true)
            .ifEmpty {
                tokenized
                    .sortedByDescending { it.first.lastModified }
                    .take(MAX_CLOSEST)
                    .map { ScoredEntry(it.first, 0.0) }
            }
        return MatchResult.ShowList(
            headline = "Closest matches — nothing matched exactly",
            matches = closest,
            crossChecked = crossChecked.toList(),
            closest = true,
        )
    }

    /** Scores the whole index and returns the deduplicated top-ranked entries. */
    private fun rank(
        tokenized: List<Pair<FileEntry, List<String>>>,
        queryTokenVariants: List<List<String>>,
        contentPhrase: String,
        query: ParsedQuery,
        lenient: Boolean,
    ): List<ScoredEntry> {
        val scored = ArrayList<ScoredEntry>(tokenized.size)
        for ((entry, nameTokens) in tokenized) {
            val s = scoreEntry(entry, nameTokens, queryTokenVariants, contentPhrase, query, lenient)
            if (s >= (if (lenient) 0.0 else LIST_THRESHOLD)) scored.add(ScoredEntry(entry, s))
        }

        // Deduplicate the same file found through both MediaStore and SAF.
        val deduped = LinkedHashMap<String, ScoredEntry>()
        for (s in scored.sortedByDescending { it.score }) {
            val key = s.entry.name.lowercase() + "|" + s.entry.size
            val existing = deduped[key]
            if (existing == null || existing.score < s.score) deduped[key] = s
        }

        val cap = if (lenient) MAX_CLOSEST else MAX_LIST
        return deduped.values
            .sortedWith(
                compareByDescending<ScoredEntry> { it.score }
                    .thenByDescending { it.entry.lastModified },
            )
            .take(cap)
    }

    private fun scoreEntry(
        entry: FileEntry,
        nameTokens: List<String>,
        queryTokenVariants: List<List<String>>,
        contentPhrase: String,
        query: ParsedQuery,
        lenient: Boolean,
    ): Double {
        if (nameTokens.isEmpty()) return 0.0

        var coverage = 0.0
        for (variants in queryTokenVariants) {
            var bestForToken = 0.0
            for (q in variants) {
                for (n in nameTokens) {
                    bestForToken = maxOf(bestForToken, tokenSimilarity(q, n, lenient))
                }
            }
            if (bestForToken == 0.0) {
                // Strict pass: every spoken word must match something.
                // Lenient pass: keep going so weak resemblance can still rank.
                if (!lenient) return 0.0
            }
            coverage += bestForToken
        }
        coverage /= queryTokenVariants.size
        if (lenient && coverage <= 0.0) return 0.0

        var score = coverage

        val normName = QueryNormalizer.normalize(
            nameTokens.joinToString(" "), query.lang,
        )
        if (contentPhrase.isNotEmpty() &&
            (normName.contains(contentPhrase) || contentPhrase.contains(normName))
        ) {
            score += 0.12
        }

        val category = query.category
        if (category != null) {
            if (matchesCategory(entry, category)) score += 0.15 else score *= 0.55
        }
        if (query.expectsMedia && !isMedia(entry)) {
            score *= 0.6
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun isMedia(entry: FileEntry): Boolean {
        val mime = entry.mimeType ?: ""
        return mime.startsWith("audio/") || mime.startsWith("video/")
    }

    // ---------------------------------------------------------- token similarity

    private fun tokenSimilarity(queryToken: String, nameToken: String, lenient: Boolean): Double = when {
        queryToken == nameToken -> 1.0
        !lenient && minOf(queryToken.length, nameToken.length) >= 3 &&
            (nameToken.contains(queryToken) || queryToken.contains(nameToken)) -> 0.85
        else -> {
            val d = diceCoefficient(queryToken, nameToken)
            when {
                d >= 0.55 -> d
                lenient && d >= 0.30 -> d
                else -> 0.0
            }
        }
    }

    /** Sørensen–Dice coefficient over character bigrams; script-independent. */
    internal fun diceCoefficient(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val bigramsA = HashMap<String, Int>()
        for (i in 0 until a.length - 1) {
            val bg = a.substring(i, i + 2)
            bigramsA[bg] = (bigramsA[bg] ?: 0) + 1
        }
        var intersections = 0
        for (i in 0 until b.length - 1) {
            val bg = b.substring(i, i + 2)
            val count = bigramsA[bg] ?: 0
            if (count > 0) {
                bigramsA[bg] = count - 1
                intersections++
            }
        }
        return 2.0 * intersections / (a.length - 1 + b.length - 1)
    }

    // ------------------------------------------------- vocabulary cross-verification

    /** All distinct normalized words the user's file names contain → their as-written form. */
    private fun buildVocabulary(index: Index, lang: Language): Map<String, String> {
        val vocab = HashMap<String, String>()
        for (entry in index.files) {
            for (raw in rawNameTokens(entry.name)) {
                if (raw.length < RESOLVE_MIN_LEN) continue
                val norm = QueryNormalizer.normalize(raw, lang)
                if (norm.length >= RESOLVE_MIN_LEN) {
                    vocab.putIfAbsent(norm, raw)
                }
            }
        }
        return vocab
    }

    /**
     * Cross-verifies one recognized word against the real vocabulary on the
     * device. Returns (as-written word, normalized word) only when a single
     * candidate clearly dominates — otherwise null (no guessing).
     */
    private fun resolveToken(
        token: String,
        vocabulary: Map<String, String>,
    ): Pair<String, String>? {
        if (token.length < RESOLVE_MIN_LEN) return null

        var bestNorm: String? = null
        var bestScore = 0.0
        var secondScore = 0.0
        for ((norm, _) in vocabulary) {
            if (norm == token) continue // exact vocabulary word needs no correction
            // Cheap length window before the bigram work.
            if (kotlin.math.abs(norm.length - token.length) > token.length / 2 + 2) continue
            val d = diceCoefficient(token, norm)
            if (d > bestScore) {
                secondScore = bestScore
                bestScore = d
                bestNorm = norm
            } else if (d > secondScore) {
                secondScore = d
            }
        }
        if (bestNorm == null || bestScore < RESOLVE_MIN_SIM) return null
        if (bestScore - secondScore < RESOLVE_MIN_GAP) return null
        return vocabulary[bestNorm]?.let { raw -> raw to bestNorm }
    }

    // ---------------------------------------------------------------- file names

    /** Splits a file name into raw tokens: "JavaNotes-2024.pdf" → [Java, Notes, 2024]. */
    fun rawNameTokens(name: String): List<String> {
        val base = name.substringBeforeLast('.', name)
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var prevClass = 0 // 0 = none, 1 = letter(lower), 2 = letter(upper), 3 = digit
        fun flush() {
            if (current.isNotEmpty()) {
                parts.add(current.toString())
                current.setLength(0)
            }
        }
        for (c in base) {
            val cls = when {
                c.isDigit() -> 3
                c.isUpperCase() -> 2
                isWordChar(c) -> 1
                else -> 0
            }
            when {
                cls == 0 -> flush()
                // split letter/digit boundaries: report2024 → report, 2024
                (prevClass == 3) != (cls == 3) && current.isNotEmpty() -> {
                    flush(); current.append(c)
                }
                // split camelCase: JavaNotes → Java, Notes
                cls == 2 && prevClass == 1 && current.isNotEmpty() -> {
                    flush(); current.append(c)
                }
                else -> current.append(c)
            }
            prevClass = cls
        }
        flush()
        return parts
    }

    /** Normalized tokens used for scoring: each raw token is script-normalized. */
    fun nameTokens(name: String, lang: Language): List<String> =
        rawNameTokens(name)
            .flatMap { QueryNormalizer.normalize(it, lang).split(' ') }
            .filter { it.isNotEmpty() }

    /** True for letters and combining marks (e.g. Tamil vowel signs) — never split a word mid-syllable. */
    private fun isWordChar(c: Char): Boolean {
        if (c.isLetter()) return true
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    // ---------------------------------------------------------------- categories

    private fun matchesCategory(entry: FileEntry, category: Category): Boolean {
        val mime = entry.mimeType?.lowercase()
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        return when (category) {
            Category.AUDIO -> mime?.startsWith("audio/") == true ||
                ext in setOf("mp3", "m4a", "wav", "flac", "ogg", "aac", "opus", "wma", "amr")
            Category.VIDEO -> mime?.startsWith("video/") == true ||
                ext in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "mpg", "mpeg", "flv")
            Category.IMAGE -> mime?.startsWith("image/") == true ||
                ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg", "tif", "tiff")
            Category.DOCUMENT -> mime?.startsWith("text/") == true ||
                ext in setOf(
                    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv",
                    "rtf", "odt", "ods", "odp", "epub",
                ) || mime in setOf(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                )
            Category.DOWNLOADS -> entry.inDownloads()
        }
    }

    private fun FileEntry.inDownloads(): Boolean {
        val p = path?.lowercase() ?: return false
        return p == "download" || p == "downloads" ||
            p.startsWith("download/") || p.startsWith("downloads/") ||
            name.equals("download", ignoreCase = true)
    }

    private fun FolderEntry.isDownloadsFolder(): Boolean =
        name.equals("download", ignoreCase = true) || name.equals("downloads", ignoreCase = true)

    private val Category.title: String
        get() = when (this) {
            Category.AUDIO -> "Songs & audio"
            Category.VIDEO -> "Videos"
            Category.IMAGE -> "Images"
            Category.DOCUMENT -> "Documents"
            Category.DOWNLOADS -> "Downloads"
        }
}
