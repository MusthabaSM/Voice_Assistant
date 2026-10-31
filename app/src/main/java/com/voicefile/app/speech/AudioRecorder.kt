package com.voicefile.app.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** 16 kHz mono float PCM in the range [-1, 1], the format Whisper expects. */
data class RecordedSpeech(val samples: FloatArray, val sampleRate: Int)

/**
 * Records from the microphone at 16 kHz mono and stops automatically:
 *  - after [silenceMs] of quiet following detected speech, or
 *  - when the user taps to stop, or
 *  - after [maxMs] total, or
 *  - after [initialTimeoutMs] with no speech at all (returns null).
 *
 * Recordings are kept only in memory for recognition and never written to disk.
 */
class AudioRecorder {

    private val stopRequested = AtomicBoolean(false)

    fun requestStop() = stopRequested.set(true)

    suspend fun record(
        maxMs: Int = 15_000,
        silenceMs: Int = 700,
        initialTimeoutMs: Int = 6_000,
        minSpeechMs: Int = 300,
    ): RecordedSpeech? = withContext(Dispatchers.IO) {
        stopRequested.set(false)

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return@withContext null
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 5 * 2) // at least ~200 ms

        @SuppressLint("MissingPermission")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext null
        }

        val collected = ArrayList<Float>(SAMPLE_RATE * 10)
        val buffer = ShortArray(bufSize / 2)
        var speechStart = -1L
        var lastLoud = -1L

        try {
            recorder.startRecording()
            val startedAt = SystemClock.elapsedRealtime()

            while (true) {
                if (stopRequested.get()) break
                val now = SystemClock.elapsedRealtime()
                if (now - startedAt >= maxMs) break

                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break

                var sumSq = 0.0
                for (i in 0 until read) {
                    val v = buffer[i] / 32768f
                    collected.add(v)
                    sumSq += (v * v).toDouble()
                }
                val rms = sqrt(sumSq / read)
                val end = SystemClock.elapsedRealtime()

                if (rms >= LOUD_RMS) {
                    if (speechStart < 0) speechStart = end
                    lastLoud = end
                }

                if (speechStart >= 0) {
                    if (end - lastLoud >= silenceMs) break // trailing silence
                } else if (end - startedAt >= initialTimeoutMs) {
                    break // nobody said anything
                }
            }
            runCatching { recorder.stop() }
        } finally {
            recorder.release()
        }

        val voiced = speechStart >= 0 && collected.size >= SAMPLE_RATE / 4
        if (!voiced) null else RecordedSpeech(collected.toFloatArray(), SAMPLE_RATE)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val LOUD_RMS = 0.015
    }
}
