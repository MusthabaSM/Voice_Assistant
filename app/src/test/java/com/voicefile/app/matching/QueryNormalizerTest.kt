package com.voicefile.app.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryNormalizerTest {

    // ------------------------------------------------------------------ English

    @Test
    fun englishStripsCommandWords() {
        val q = QueryNormalizer.parse("Open Resume", Language.EN)
        assertEquals(listOf("resume"), q.tokens)
        assertNull(q.category)
        assertFalse(q.categoryOnly)
    }

    @Test
    fun englishMultiWordFileNameKeptTogether() {
        val q = QueryNormalizer.parse("Open Java Notes", Language.EN)
        assertEquals(listOf("java", "notes"), q.tokens)
    }

    @Test
    fun englishPlaySongsIsCategoryOnly() {
        val q = QueryNormalizer.parse("Play my songs", Language.EN)
        assertEquals(Category.AUDIO, q.category)
        assertTrue(q.categoryOnly)
        assertTrue(q.expectsMedia)
    }

    @Test
    fun englishNormalizeRemovesPunctuationAndCase() {
        assertEquals(
            "java notes 2024",
            QueryNormalizer.normalize("Java-Notes_2024!", Language.EN),
        )
    }

    @Test
    fun englishCategoryWordMixedWithContent() {
        val q = QueryNormalizer.parse("open report document", Language.EN)
        assertEquals(Category.DOCUMENT, q.category)
        assertFalse(q.categoryOnly)
        assertEquals(listOf("report"), q.tokens)
    }

    // ------------------------------------------------------------------- Tamil

    @Test
    fun tamilCommandIsStripped() {
        val q = QueryNormalizer.parse("ரெஸ்யூமை திற", Language.TA)
        // "திற" is the command; the accusative ending disappears in normalization,
        // so the query token equals the normalization of the bare file stem.
        assertEquals(
            QueryNormalizer.normalize("ரெஸ்யூம்", Language.TA),
            q.tokens.single(),
        )
        assertFalse(q.categoryOnly)
    }

    @Test
    fun tamilSongPhraseIsAudioCategory() {
        val q = QueryNormalizer.parse("பாடலை இயக்கு", Language.TA)
        assertEquals(Category.AUDIO, q.category)
        assertTrue(q.categoryOnly)
        assertTrue(q.expectsMedia)
    }

    @Test
    fun tamilAccusativeAndStemNormalizeIdentically() {
        assertEquals(
            QueryNormalizer.normalize("பாடலை", Language.TA),
            QueryNormalizer.normalize("பாடல்", Language.TA),
        )
    }

    // ------------------------------------------------------------------- Arabic

    @Test
    fun arabicStripsOpenAndKeepsFileName() {
        val q = QueryNormalizer.parse("افتح السيرة الذاتية", Language.AR)
        assertEquals(
            listOf(
                QueryNormalizer.normalize("السيرة", Language.AR),
                QueryNormalizer.normalize("الذاتية", Language.AR),
            ),
            q.tokens,
        )
        assertNull(q.category)
    }

    @Test
    fun arabicSongPhraseIsAudioCategory() {
        val q = QueryNormalizer.parse("شغل الأغنية", Language.AR)
        assertEquals(Category.AUDIO, q.category)
        assertTrue(q.categoryOnly)
        assertTrue(q.expectsMedia)
    }

    @Test
    fun arabicDefiniteArticleIsFolded() {
        // أ→ا, ة→ه folding must make the spoken and written forms identical.
        assertEquals(
            QueryNormalizer.normalize("الأغنية", Language.AR),
            QueryNormalizer.normalize("اغنية", Language.AR).let { "ال$it" },
        )
    }

    @Test
    fun arabicDiacriticsAreRemoved() {
        assertEquals(
            QueryNormalizer.normalize("السيرة الذاتية", Language.AR),
            QueryNormalizer.normalize("السِيرَةُ الذاتِيَّة", Language.AR),
        )
    }
}
