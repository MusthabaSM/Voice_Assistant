package com.voicefile.app.speech

import android.content.Context
import com.voicefile.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** One model file to download, with its expected size used for verification. */
data class ModelFile(val name: String, val url: String, val expectedBytes: Long)

/**
 * A downloadable offline speech-model variant. All files are the quantized
 * (int8) variants served from the official sherpa-onnx mirrors on Hugging Face.
 */
data class ModelVariant(
    val id: String,
    val title: String,
    val description: String,
    val approxMb: Int,
    val dirName: String,
    val files: List<ModelFile>,
) {
    val encoderName: String get() = files.first { it.name.endsWith("encoder.int8.onnx") }.name
    val decoderName: String get() = files.first { it.name.endsWith("decoder.int8.onnx") }.name
    val tokensName: String get() = files.first { it.name.endsWith("tokens.txt") }.name
}

object Models {
    private fun files(variant: String, encoder: Long, decoder: Long, tokens: Long) = listOf(
        ModelFile(
            "$variant-encoder.int8.onnx",
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$variant/resolve/main/$variant-encoder.int8.onnx",
            encoder,
        ),
        ModelFile(
            "$variant-decoder.int8.onnx",
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$variant/resolve/main/$variant-decoder.int8.onnx",
            decoder,
        ),
        ModelFile(
            "$variant-tokens.txt",
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$variant/resolve/main/$variant-tokens.txt",
            tokens,
        ),
    )

    val TINY = ModelVariant(
        id = "tiny",
        title = "Tiny (~104 MB)",
        description = "Fastest and smallest. Decent for English, weaker for Tamil and Arabic.",
        approxMb = 104,
        dirName = "whisper-tiny",
        files = files("tiny", 12_937_772L, 89_855_401L, 816_730L),
    )

    val SMALL = ModelVariant(
        id = "small",
        title = "Small (~376 MB)",
        description = "Recommended balance: decent Tamil and Arabic in a modest download.",
        approxMb = 376,
        dirName = "whisper-small",
        files = files("small", 112_442_483L, 262_226_114L, 816_730L),
    )

    val MEDIUM = ModelVariant(
        id = "medium",
        title = "Medium (~902 MB)",
        description =
            "Best Tamil/Arabic accuracy of the Whisper family. Largest download; " +
                "noticeably slower recognition on low-end phones.",
        approxMb = 902,
        dirName = "whisper-medium",
        files = files("medium", 374_196_283L, 571_059_257L, 816_730L),
    )

    val ALL = listOf(SMALL, MEDIUM, TINY)

    fun byId(id: String?): ModelVariant? = ALL.firstOrNull { it.id == id }
}

sealed class InstallState {
    data object NotInstalled : InstallState()
    data class Downloading(val fileName: String, val received: Long, val total: Long) : InstallState()
    data class Installed(val variant: ModelVariant) : InstallState()
    data class Failed(val message: String) : InstallState()
}

/**
 * Downloads and installs the offline speech model (one-time setup step).
 * Downloads go to app-private storage; nothing is uploaded.
 */
class ModelManager(context: Context, private val prefs: Prefs) {

    private val root = File(context.filesDir, "models")
    private val cancelled = AtomicBoolean(false)

    fun dirFor(variant: ModelVariant): File = File(root, variant.dirName)

    fun isInstalled(variant: ModelVariant): Boolean {
        val dir = dirFor(variant)
        return variant.files.all { f ->
            val file = File(dir, f.name)
            // Accept files that are complete (allow small mirror size drift).
            file.exists() && file.length() >= (f.expectedBytes * 95) / 100
        }
    }

    fun installedVariant(): ModelVariant? = Models.ALL.firstOrNull { isInstalled(it) }

    fun cancel() = cancelled.set(true)

    /**
     * Installs [variant]. Blocking; call from a background dispatcher.
     * Verifies each file against its expected size and only then reports Installed.
     */
    suspend fun install(
        variant: ModelVariant,
        onProgress: (InstallState) -> Unit,
    ): InstallState = withContext(Dispatchers.IO) {
        cancelled.set(false)
        try {
            val dir = dirFor(variant)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("Cannot create model directory")
            }
            for (f in variant.files) {
                val target = File(dir, f.name)
                if (target.exists() && target.length() >= (f.expectedBytes * 95) / 100) continue

                val part = File(dir, f.name + ".part")
                onProgress(InstallState.Downloading(f.name, 0, f.expectedBytes))
                download(f.url, part) { received ->
                    onProgress(InstallState.Downloading(f.name, received, f.expectedBytes))
                }
                if (part.length() < (f.expectedBytes * 95) / 100) {
                    part.delete()
                    throw IOException("Download of ${f.name} was incomplete (${part.length()} bytes)")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("Could not install ${f.name}")
            }

            // Free space taken by a previously installed different variant.
            Models.ALL.filterNot { it.id == variant.id }.forEach { old ->
                dirFor(old).deleteRecursively()
            }
            prefs.modelVariant = variant.id
            onProgress(InstallState.Installed(variant))
            InstallState.Installed(variant)
        } catch (e: Exception) {
            val state = if (cancelled.get()) {
                InstallState.NotInstalled
            } else {
                InstallState.Failed(e.message ?: e.javaClass.simpleName)
            }
            onProgress(state)
            state
        }
    }

    private inline fun download(url: String, target: File, onBytes: (Long) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Server returned HTTP $code")

            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var received = 0L
                    var lastEmit = 0L
                    while (true) {
                        if (cancelled.get()) throw IOException("cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 150) {
                            lastEmit = now
                            onBytes(received)
                        }
                    }
                    onBytes(received)
                    if (total > 0 && received < total) {
                        throw IOException("Connection closed early ($received of $total bytes)")
                    }
                }
            }
        } finally {
            connection?.disconnect()
        }
    }
}
