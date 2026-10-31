package com.voicefile.app.speech

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.voicefile.app.matching.Language
import java.io.File

/**
 * Thin wrapper around the sherpa-onnx offline recognizer using a Whisper model.
 *
 * All recognition happens on-device: audio in, text out. No network is used.
 * The selected UI language becomes the model's language hint so the same
 * multilingual model recognises English, Tamil or Arabic.
 */
class WhisperEngine {

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null

    /** True once a model has been loaded into memory. */
    val isLoaded: Boolean get() = synchronized(lock) { recognizer != null }

    /**
     * Loads (or reloads, when the language changed) the recognizer for the
     * given installed model. Blocking — call on a background thread.
     */
    fun ensureLoaded(modelDir: File, variant: ModelVariant, language: Language) {
        synchronized(lock) {
            val key = "${variant.id}|${language.tag}|${modelDir.absolutePath}"
            if (key == loadedKey && recognizer != null) return

            release()
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(modelDir, variant.encoderName).absolutePath,
                        decoder = File(modelDir, variant.decoderName).absolutePath,
                        language = language.tag,
                        task = "transcribe",
                        // Small amount of trailing silence helps Whisper finalise short
                        // utterances like spoken filenames.
                        tailPaddings = 16_000,
                    ),
                    tokens = File(modelDir, variant.tokensName).absolutePath,
                    modelType = "whisper",
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    debug = false,
                ),
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(null, config)
            loadedKey = key
        }
    }

    /** Runs offline recognition. Blocking — call on a background thread. */
    fun recognize(samples: FloatArray, sampleRate: Int): String {
        val recognizer = synchronized(lock) { recognizer }
            ?: throw IllegalStateException("Speech model is not loaded")
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() {
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
            loadedKey = null
        }
    }
}
