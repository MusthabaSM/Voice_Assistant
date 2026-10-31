package com.voicefile.app.matching

import android.net.Uri
import com.voicefile.app.files.FileEntry
import com.voicefile.app.files.FileSource
import com.voicefile.app.files.FolderEntry
import com.voicefile.app.files.Index
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class FileMatcherTest {

    private val matcher = FileMatcher()

    // The matcher never touches a Uri; a mock keeps these tests pure JVM.
    private fun uri(): Uri = Mockito.mock(Uri::class.java)

    private fun file(
        name: String,
        mime: String? = null,
        size: Long = 1_000L,
        modified: Long = 0L,
        path: String? = null,
        source: FileSource = FileSource.SAF,
    ) = FileEntry(
        name = name,
        uri = uri(),
        mimeType = mime,
        size = size,
        lastModified = modified,
        source = source,
        path = path,
    )

    private fun folder(name: String, path: String = name) =
        FolderEntry(name = name, path = path, treeUri = uri(), documentUri = uri())

    private fun parse(text: String, lang: Language) = QueryNormalizer.parse(text, lang)

    // ------------------------------------------------------------------ English

    @Test
    fun singleExactMatchOpensImmediately() {
        val index = Index(
            files = listOf(
                file("resume.pdf", "application/pdf"),
                file("song.mp3", "audio/mpeg"),
            ),
            folders = emptyList(),
        )
        val result = matcher.match(parse("Open Resume", Language.EN), Language.EN, index)

        assertTrue("expected AutoOpen but was $result", result is MatchResult.AutoOpen)
        result as MatchResult.AutoOpen
        assertEquals("resume.pdf", result.entry.name)
        assertTrue(result.score >= FileMatcher.OPEN_THRESHOLD)
    }

    @Test
    fun ambiguousMatchesShowAList() {
        val index = Index(
            files = listOf(
                file("Java Notes.pdf", "application/pdf"),
                file("Java Tutorial.pdf", "application/pdf"),
                file("song.mp3", "audio/mpeg"),
            ),
            folders = emptyList(),
        )
        val result = matcher.match(parse("Open Java", Language.EN), Language.EN, index)

        assertTrue("expected ShowList but was $result", result is MatchResult.ShowList)
        result as MatchResult.ShowList
        assertEquals(2, result.matches.size)
        assertEquals("Java Notes.pdf", result.matches.first().entry.name) // newer/more similar first
    }

    @Test
    fun nothingMatchesFallsBackToClosestMatches() {
        val index = Index(
            files = listOf(file("resume.pdf", "application/pdf")),
            folders = emptyList(),
        )
        val result = matcher.match(
            parse("open quantum tutorial", Language.EN),
            Language.EN,
            index,
        )
        // User requirement: never a dead end — show the closest file names.
        assertTrue("expected ShowList but was $result", result is MatchResult.ShowList)
        result as MatchResult.ShowList
        assertTrue("expected the closest flag", result.closest)
        assertTrue(result.matches.any { it.entry.name == "resume.pdf" })
    }

    @Test
    fun emptyIndexStillReportsNoMatch() {
        val result = matcher.match(
            parse("open resume", Language.EN),
            Language.EN,
            Index(files = emptyList(), folders = emptyList()),
        )
        assertTrue(result is MatchResult.NoMatch)
    }

    @Test
    fun crossVerificationResolvesMisheardWordToRealFileName() {
        // ASR heard a word that only weakly resembles the real file word
        // (slang/mispronunciation) — cross-verification must resolve it.
        val index = Index(
            files = listOf(
                file("computer.pdf", "application/pdf"),
                file("song.mp3", "audio/mpeg"),
            ),
            folders = emptyList(),
        )
        val result = matcher.match(parse("open kompuytar", Language.EN), Language.EN, index)

        assertTrue("expected AutoOpen but was $result", result is MatchResult.AutoOpen)
        result as MatchResult.AutoOpen
        assertEquals("computer.pdf", result.entry.name)
        assertTrue(
            "expected a cross-checked mapping, got ${result.crossChecked}",
            result.crossChecked.any { it.contains("kompuytar") && it.contains("computer") },
        )
    }

    @Test
    fun crossVerificationDoesNotGuessWhenCompetitorsAreClose() {
        // Two nearly-equal candidates → no substitution (conservative rule).
        val index = Index(
            files = listOf(
                file("compute.pdf", "application/pdf"),
                file("computer.pdf", "application/pdf"),
            ),
            folders = emptyList(),
        )
        val result = matcher.match(parse("open kompuytar", Language.EN), Language.EN, index)
        // It may still match strictly or list — but if a mapping appears, it must
        // not be attached to an AutoOpen of an arbitrary file.
        if (result is MatchResult.AutoOpen) {
            assertTrue(result.crossChecked.isEmpty())
        }
    }

    @Test
    fun playPrefersMediaOverDocument() {
        val index = Index(
            files = listOf(
                file("report.pdf", "application/pdf"),
                file("report.mp4", "video/mp4"),
            ),
            folders = emptyList(),
        )
        val result = matcher.match(parse("play report", Language.EN), Language.EN, index)

        assertTrue("expected AutoOpen but was $result", result is MatchResult.AutoOpen)
        assertEquals("report.mp4", (result as MatchResult.AutoOpen).entry.name)
    }

    @Test
    fun downloadsReturnsAuthorizedFolder() {
        val index = Index(
            files = listOf(file("invoice.pdf", "application/pdf", path = "Download")),
            folders = listOf(folder("Downloads")),
        )
        val result = matcher.match(parse("Open Downloads", Language.EN), Language.EN, index)

        assertTrue("expected ShowList but was $result", result is MatchResult.ShowList)
        result as MatchResult.ShowList
        assertEquals(Category.DOWNLOADS, result.category)
        assertTrue(result.folder != null && result.folder.name == "Downloads")
        assertEquals(1, result.matches.size)
    }

    @Test
    fun emptyCategoryFailsGracefully() {
        val index = Index(
            files = listOf(file("resume.pdf", "application/pdf")),
            folders = emptyList(),
        )
        val result = matcher.match(parse("open videos", Language.EN), Language.EN, index)
        assertTrue("expected NoMatch but was $result", result is MatchResult.NoMatch)
    }

    @Test
    fun multipleCategoryFilesAreListed() {
        val index = Index(
            files = listOf(
                file("a.mp3", "audio/mpeg", modified = 300),
                file("b.mp3", "audio/mpeg", modified = 200),
                file("c.mp4", "video/mp4", modified = 100),
            ),
            folders = emptyList(),
        )
        val result = matcher.match(parse("play my songs", Language.EN), Language.EN, index)

        assertTrue(result is MatchResult.ShowList)
        result as MatchResult.ShowList
        assertEquals(listOf("a.mp3", "b.mp3"), result.matches.map { it.entry.name })
    }

    // -------------------------------------------------------------------- Tamil

    @Test
    fun tamilPhraseOpensMatchingSong() {
        val index = Index(
            files = listOf(
                file("தமிழ் பாடல்.mp3", "audio/mpeg"),
                file("notes.pdf", "application/pdf"),
            ),
            folders = emptyList(),
        )
        val result = matcher.match(
            parse("தமிழ் பாடலை இயக்கு", Language.TA),
            Language.TA,
            index,
        )
        assertTrue("expected AutoOpen but was $result", result is MatchResult.AutoOpen)
        assertEquals("தமிழ் பாடல்.mp3", (result as MatchResult.AutoOpen).entry.name)
    }

    @Test
    fun tamilStemAndAccusativeFormsMatchEachOther() {
        val index = Index(
            files = listOf(file("ரெஸ்யூம்.pdf", "application/pdf")),
            folders = emptyList(),
        )
        val result = matcher.match(
            parse("ரெஸ்யூமை திற", Language.TA),
            Language.TA,
            index,
        )
        assertTrue("expected AutoOpen but was $result", result is MatchResult.AutoOpen)
        assertEquals("ரெஸ்யூம்.pdf", (result as MatchResult.AutoOpen).entry.name)
    }

    // ------------------------------------------------------------------- Arabic

    @Test
    fun arabicExactPhraseOpensReport() {
        val index = Index(
            files = listOf(
                file("تقرير المشروع.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                file("صورة.png", "image/png"),
            ),
            folders = emptyList(),
        )
        val result = matcher.match(
            parse("افتح تقرير المشروع", Language.AR),
            Language.AR,
            index,
        )
        assertTrue("expected AutoOpen but was $result", result is MatchResult.AutoOpen)
        assertEquals("تقرير المشروع.docx", (result as MatchResult.AutoOpen).entry.name)
    }

    @Test
    fun arabicDiacriticsInSpeechMatchPlainFileName() {
        val index = Index(
            files = listOf(file("السيرة الذاتية.pdf", "application/pdf")),
            folders = emptyList(),
        )
        val result = matcher.match(
            parse("افتح السِيرة الذاتِية", Language.AR),
            Language.AR,
            index,
        )
        assertTrue("expected AutoOpen but was $result", result is MatchResult.AutoOpen)
    }

    @Test
    fun arabicPlaySongUsesCategory() {
        val index = Index(
            files = listOf(
                file("أغنية الصيف.mp3", "audio/mpeg"),
                file("file.mp4", "video/mp4"),
            ),
            folders = emptyList(),
        )
        val result = matcher.match(parse("شغل الأغنية", Language.AR), Language.AR, index)
        // Category-only phrase: the single audio file opens right away.
        assertTrue("expected AutoOpen but was $result", result is MatchResult.AutoOpen)
        assertEquals("أغنية الصيف.mp3", (result as MatchResult.AutoOpen).entry.name)
    }

    // ------------------------------------------------------------ token helpers

    @Test
    fun camelCaseAndDigitsAreSplitIntoTokens() {
        assertEquals(
            listOf("java", "notes", "2024"),
            matcher.nameTokens("JavaNotes-2024.pdf", Language.EN),
        )
        assertEquals(listOf("report", "2"), matcher.nameTokens("report2.pdf", Language.EN))
    }

    @Test
    fun tamilWordIsNotSplitAtCombiningMarks() {
        assertEquals(
            listOf(QueryNormalizer.normalize("தமிழ்", Language.TA)),
            matcher.nameTokens("தமிழ்.pdf", Language.TA),
        )
    }

    @Test
    fun diceCoefficientIsHighForCloseWords() {
        assertTrue(matcher.diceCoefficient("notes", "note") >= 0.8)
        assertTrue(matcher.diceCoefficient("abc", "xyz") < 0.1)
    }
}
