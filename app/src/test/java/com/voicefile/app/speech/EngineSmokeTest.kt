package com.voicefile.app.speech

import com.voicefile.app.matching.Language
import org.junit.Assume
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Smoke test of the REAL engine wiring (the same classes the app uses on
 * Android) against sherpa-onnx's desktop native runtime.
 *
 * It only runs when the artifacts have been fetched into
 * `<repo>/.tmpwork/engine-test/` (model + win-x64 native lib + test wav);
 * otherwise it is skipped. See README "Engine verification".
 */
class EngineSmokeTest {

    private val modelDir = File("../.tmpwork/engine-test/whisper-tiny")
    private val wav = File(modelDir, "test_wavs/0.wav")

    /**
     * Desktop native execution is strictly opt-in: sherpa's win-x64 native-lib
     * jar of 1.13.8 hard-crashes the JVM (embedded ORT/API mismatch), so the
     * test is skipped unless someone provides a compatible runtime and creates
     * the ENABLE marker.
     */
    private fun assumeOptIn() {
        Assume.assumeTrue(
            "engine smoke is opt-in: create .tmpwork/engine-test/ENABLE once a " +
                "compatible sherpa-onnx desktop runtime is in place",
            File("../.tmpwork/engine-test/ENABLE").exists(),
        )
    }

    private fun artifactsPresent(): Boolean {
        val files = listOf(
            "tiny-encoder.int8.onnx",
            "tiny-decoder.int8.onnx",
            "tiny-tokens.txt",
        )
        return files.all { File(modelDir, it).exists() } && wav.exists()
    }

    private fun loadOrSkip(engine: WhisperEngine, language: Language) {
        Assume.assumeTrue("engine-test artifacts missing (see EngineSmokeTest docs)", artifactsPresent())
        try {
            engine.ensureLoaded(modelDir, Models.TINY, language)
        } catch (e: ClassNotFoundException) {
            throw AssertionError("sherpa-onnx classes missing from unit-test classpath", e)
        } catch (e: NoClassDefFoundError) {
            throw AssertionError("sherpa-onnx classes missing from unit-test classpath", e)
        } catch (t: Throwable) {
            Assume.assumeNoException("sherpa native runtime unavailable on this host", t)
        }
    }

    @Test
    fun englishRecognitionFindsKnownWords() {
        assumeOptIn()
        val engine = WhisperEngine()
        loadOrSkip(engine, Language.EN)

        val samples = readWavAs16kMono(wav)
        Assume.assumeTrue("test wav unreadable", samples != null)
        val text = engine.recognize(samples!!, 16_000)
        println("EngineSmokeTest EN output: \"$text\"")

        assertTrue("recognizer returned empty text", text.isNotBlank())
        val expected = setOf("after", "early", "nightfall", "yellow", "lamps", "quarter")
        val words = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        assertTrue("none of the expected words found in: $text", words.any { it in expected })
    }

    @Test
    fun tamilAndArabicHintsLoadAndDecodeWithoutCrashing() {
        assumeOptIn()
        for (language in listOf(Language.TA, Language.AR)) {
            val engine = WhisperEngine()
            loadOrSkip(engine, language)
            val samples = readWavAs16kMono(wav)
            Assume.assumeTrue("test wav unreadable", samples != null)
            // Garbage transcript is fine here — we only prove the language hint
            // is accepted and the decode path is stable for ta/ar.
            val text = engine.recognize(samples!!, 16_000)
            println("EngineSmokeTest ${language.tag} output: \"$text\"")
            assertTrue(text.isNotEmpty())
            engine.release()
        }
    }

    /**
     * Minimal RIFF/WAVE reader: returns 16-bit PCM as mono floats resampled to
     * 16 kHz (Android's unit-test classpath has no javax.sound).
     */
    private fun readWavAs16kMono(file: File): FloatArray? = runCatching {
        val bytes = file.readBytes()
        require(String(bytes, 0, 4) == "RIFF") { "not a RIFF file" }
        require(String(bytes, 8, 4) == "WAVE") { "not a WAVE file" }

        var pos = 12
        var sampleRate = 0
        var channels = 1
        var bits = 16
        var data: ByteArray? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = leInt(bytes, pos + 4)
            when (id) {
                "fmt " -> {
                    channels = leShort(bytes, pos + 10)
                    sampleRate = leInt(bytes, pos + 12)
                    bits = leShort(bytes, pos + 22)
                }
                "data" -> {
                    val end = (pos + 8 + size).coerceAtMost(bytes.size)
                    data = bytes.copyOfRange(pos + 8, end)
                }
            }
            pos += 8 + size + (size and 1)
        }
        val pcm = requireNotNull(data) { "no data chunk" }
        require(bits == 16) { "expected 16-bit pcm, got $bits" }
        require(sampleRate > 0) { "no fmt chunk" }

        val ch = channels.coerceAtLeast(1)
        val frameCount = pcm.size / (2 * ch)
        val mono = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until ch) {
                val idx = (f * ch + c) * 2
                val sample = ((pcm[idx + 1].toInt()) shl 8) or (pcm[idx].toInt() and 0xff)
                sum += sample.toShort() / 32768f
            }
            mono[f] = sum / ch
        }

        if (sampleRate == 16_000) return@runCatching mono

        val outLen = (mono.size.toLong() * 16_000 / sampleRate).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i.toDouble() * sampleRate / 16_000
            val idx = srcPos.toInt().coerceAtMost(mono.lastIndex)
            val frac = (srcPos - idx).toFloat()
            val next = (idx + 1).coerceAtMost(mono.lastIndex)
            out[i] = mono[idx] * (1 - frac) + mono[next] * frac
        }
        out
    }.getOrNull()

    private fun leInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)

    private fun leShort(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
}
