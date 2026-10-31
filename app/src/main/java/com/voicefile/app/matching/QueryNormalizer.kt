package com.voicefile.app.matching

/** Broad category of a spoken request such as "play my songs". */
enum class Category { AUDIO, VIDEO, IMAGE, DOCUMENT, DOWNLOADS }

/** Result of parsing a recognized phrase into a searchable query. */
data class ParsedQuery(
    /** The language the phrase was recognized in. */
    val lang: Language,
    /** Content tokens to match against file names (command words removed). */
    val tokens: List<String>,
    /** Category implied by the phrase ("songs" → AUDIO), if any. */
    val category: Category?,
    /** True when the phrase only named a category, e.g. "play my songs". */
    val categoryOnly: Boolean,
    /** True when the user said a play-style verb (prefer media files). */
    val expectsMedia: Boolean,
    /** The normalized phrase as a whole (for substring matching). */
    val joined: String,
)

/**
 * Text processing for the three supported languages: normalization, removal of
 * spoken command words, Tamil case-suffix handling and category detection.
 */
object QueryNormalizer {

    private val WHITESPACE = Regex("\\s+")

    // Arabic: strip diacritics/tatweel, fold alef variants, ya, ta marbuta, hamza carriers.
    private val AR_DIACRITICS = Regex("[\\u064B-\\u065F\\u0670\\u0640\\u06D6-\\u06ED\\u0610-\\u061A\\u0640]")
    private val AR_ALEF = Regex("[\\u0623\\u0625\\u0622\\u0671]")

    private val EN_COMMANDS = setOf(
        "open", "play", "start", "launch", "run", "find", "search", "show",
        "please", "my", "the", "a", "an", "file", "files", "up", "now", "app",
        "can", "you", "could",
    )

    private val TA_COMMANDS = setOf(
        "திற", "திறக்க", "திறக்கவும்", "இயக்க", "இயக்கு", "இயக்கவும்",
        "தேடு", "தேடவும்", "காட்டு", "காட்டவும்", "என்", "கோப்பு", "கோப்பை",
        "ஒரு", "ப்ளீஸ்", "தயவுசெய்து",
    )

    private val AR_COMMANDS = setOf(
        "افتح", "افتحا", "شغل", "اشغل", "قم", "بتشغيل", "الرجاء", "فضلا",
        "ملف", "الملف", "الملفات", "الخاص", "بي", "اعرض", "ابحث", "والتي",
    )

    private val PLAY_VERBS = setOf(
        "play", "start", "இயக்கு", "இயக்க", "இயக்கவும்", "شغل", "اشغل", "بتشغيل",
    )

    /**
     * Tamil case suffixes, written in their NORMALIZED form (all Tamil combining
     * marks are removed by [normalize], so "கள்" → "கள" etc.).
     */
    private val TA_SUFFIXES = listOf("கக", "கள", "உடன", "இன", "இல", "ஐ", "ஆக")

    private val CATEGORY_WORDS: Map<Category, Set<String>> = mapOf(
        Category.AUDIO to setOf(
            "songs", "song", "music", "track", "tracks", "audio", "tune",
            "பாடல்", "பாடல்கள்", "பாடலை", "பாடல்களை", "இசை",
            "اغنيه", "اغاني", "اغنية", "موسيقى", "اغنيةها",
        ),
        Category.VIDEO to setOf(
            "videos", "video", "clip", "clips", "movie", "movies",
            "வீடியோ", "வீடியோக்கள்", "காணொளி", "காணொளிகள்", "திரைப்படம்",
            "فيديو", "فيديوهات", "مقطع", "مقاطع",
        ),
        Category.IMAGE to setOf(
            "photos", "photo", "pictures", "picture", "images", "image", "pics",
            "படம்", "படங்கள்", "படங்களை", "புகைப்படம்", "புகைப்படங்கள்",
            "صور", "صوره", "الصور", "صورها", "صورة",
        ),
        Category.DOCUMENT to setOf(
            "documents", "document", "docs",
            "ஆவணம்", "ஆவணங்கள்", "ஆவணங்களை",
            "مستندات", "مستند", "المستندات", "وثيقة", "وثائق",
        ),
        Category.DOWNLOADS to setOf(
            "downloads", "download",
            "பதிவிறக்கம்", "பதிவிறக்கங்கள்",
            "تحميلات", "تنزيلات", "تحميل", "تنزيل",
        ),
    )

    /** Lowercases, strips punctuation and folds script-specific variants. */
    fun normalize(text: String, lang: Language): String {
        var s = text
        if (lang == Language.AR) {
            s = AR_DIACRITICS.replace(s, "")
            s = AR_ALEF.replace(s, "ا")
            s = s
                .replace('ى', 'ي')
                .replace('ة', 'ه')
                .replace('ؤ', 'و')
                .replace('ئ', 'ي')
        }
        s = s.lowercase()
        val cleaned = buildString(s.length) {
            for (c in s) {
                val type = Character.getType(c)
                val isMark = type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt()
                if (isMark) {
                    // Tamil vowel signs / virama etc. are deleted, NOT replaced with
                    // a space: "பாடலை" → "படல", keeping the word in one piece.
                    continue
                }
                append(if (c.isLetterOrDigit()) c else ' ')
            }
        }
        return cleaned.replace(WHITESPACE, " ").trim()
    }

    /** All token variants worth matching for a content token (Tamil suffix forms included). */
    fun tokenVariants(token: String, lang: Language): List<String> {
        if (lang != Language.TA) return listOf(token)
        val variants = LinkedHashSet<String>()
        variants.add(token)
        for (suffix in TA_SUFFIXES) {
            if (token.length > suffix.length + 2 && token.endsWith(suffix)) {
                variants.add(token.removeSuffix(suffix))
            }
        }
        return variants.toList()
    }

    private fun commandWords(lang: Language): Set<String> = when (lang) {
        Language.EN -> EN_COMMANDS
        Language.TA -> TA_COMMANDS
        Language.AR -> AR_COMMANDS
    }

    /** Command and play-verb vocabulary, pre-normalized per language. */
    private val NORMALIZED_COMMANDS: Map<Language, Set<String>> =
        Language.entries.associateWith { lang ->
            commandWords(lang).mapTo(LinkedHashSet()) { normalize(it, lang) }
        }

    private val NORMALIZED_PLAY_VERBS: Map<Language, Set<String>> =
        Language.entries.associateWith { lang ->
            PLAY_VERBS.mapTo(LinkedHashSet()) { normalize(it, lang) }
        }

    /**
     * Category vocabulary pre-normalized per language, so that lookup compares
     * like with like: normalize("الأغنية", AR) == normalize("الاغنية", AR) etc.
     */
    private val NORMALIZED_CATEGORY_WORDS: Map<Language, Map<Category, Set<String>>> =
        Language.entries.associateWith { lang ->
            Category.entries.associateWith { cat ->
                CATEGORY_WORDS[cat].orEmpty()
                    .mapTo(LinkedHashSet()) { normalize(it, lang) }
                    .filterTo(LinkedHashSet()) { it.isNotEmpty() }
            }
        }

    private fun categoryOf(token: String, lang: Language): Category? {
        val table = NORMALIZED_CATEGORY_WORDS[lang].orEmpty()
        // Arabic definite article: "الأغنية" also matches the bare "اغنية".
        val candidates = if (token.startsWith("ال") && token.length > 3) {
            sequenceOf(token, token.removePrefix("ال"))
        } else {
            sequenceOf(token)
        }
        return table.entries
            .firstOrNull { entry -> candidates.any { it in entry.value } }
            ?.key
    }

    /** Parses a recognized phrase into content tokens plus category/media hints. */
    fun parse(text: String, lang: Language): ParsedQuery {
        val joined = normalize(text, lang)
        if (joined.isEmpty()) {
            return ParsedQuery(lang, emptyList(), null, false, false, "")
        }
        val rawTokens = joined.split(' ').filter { it.isNotEmpty() }

        val commands = NORMALIZED_COMMANDS[lang].orEmpty()
        val playVerbs = NORMALIZED_PLAY_VERBS[lang].orEmpty()
        val expectsMedia = rawTokens.any { it in playVerbs }

        var category: Category? = null
        val content = ArrayList<String>()
        for (t in rawTokens) {
            if (t in commands) continue
            val cat = categoryOf(t, lang)
            if (cat != null && category == null) {
                category = cat
            } else if (cat == null) {
                content.add(t)
            }
        }

        // Everything was a command word: fall back to the raw tokens so the user
        // still gets feedback instead of an empty search.
        val tokens = content.ifEmpty { rawTokens.filter { it !in commands }.ifEmpty { rawTokens } }

        return ParsedQuery(
            lang = lang,
            tokens = tokens,
            category = category,
            categoryOnly = content.isEmpty() && category != null,
            expectsMedia = expectsMedia,
            joined = joined,
        )
    }
}
