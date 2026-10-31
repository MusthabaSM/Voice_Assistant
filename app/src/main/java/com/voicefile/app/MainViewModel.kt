package com.voicefile.app

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicefile.app.files.FileEntry
import com.voicefile.app.files.FileIndexer
import com.voicefile.app.files.FolderEntry
import com.voicefile.app.files.Index
import com.voicefile.app.files.OpenOutcome
import com.voicefile.app.files.StorageRepository
import com.voicefile.app.matching.Category
import com.voicefile.app.matching.FileMatcher
import com.voicefile.app.matching.Language
import com.voicefile.app.matching.MatchResult
import com.voicefile.app.matching.QueryNormalizer
import com.voicefile.app.speech.AudioRecorder
import com.voicefile.app.speech.InstallState
import com.voicefile.app.speech.ModelManager
import com.voicefile.app.speech.ModelVariant
import com.voicefile.app.speech.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { SETUP, HOME, FOLDERS }

enum class HomePhase { IDLE, LISTENING, PROCESSING }

data class UiError(val title: String, val detail: String? = null)

data class FolderRow(val name: String, val uri: Uri)

data class MainUiState(
    val screen: Screen = Screen.SETUP,
    val language: Language = Language.EN,
    val install: InstallState = InstallState.NotInstalled,
    val micGranted: Boolean = false,
    val mediaGranted: Boolean = false,
    val folders: List<FolderRow> = emptyList(),
    val homePhase: HomePhase = HomePhase.IDLE,
    val recognizedText: String? = null,
    /** "heard → resolved" pairs from vocabulary cross-verification. */
    val crossChecked: List<String> = emptyList(),
    val listResult: MatchResult.ShowList? = null,
    val error: UiError? = null,
    val info: String? = null,
    val indexing: Boolean = false,
    val indexSize: Int? = null,
)

/** Something the UI layer must open in a real Android app. */
sealed interface OpenRequest {
    data class File(val entry: FileEntry) : OpenRequest
    data class Folder(val folder: FolderEntry) : OpenRequest
}

/**
 * Owns the whole pipeline:
 * speech → text → parse → index files → rank → emit an open request.
 *
 * All speech recognition and file access is local; nothing is sent anywhere.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = Prefs(application)
    private val modelManager = ModelManager(application, prefs)
    private val repo = StorageRepository(application, prefs)
    private val indexer = FileIndexer(application, repo)
    private val engine = WhisperEngine()
    private val recorder = AudioRecorder()
    private val matcher = FileMatcher()

    private var indexCache: Index? = null
    private var pendingMicRequest = false

    private val _state = MutableStateFlow(
        MainUiState(language = prefs.language),
    )
    val state = _state.asStateFlow()

    private val _openRequests = MutableSharedFlow<OpenRequest>(extraBufferCapacity = 8)
    val openRequests: SharedFlow<OpenRequest> = _openRequests.asSharedFlow()

    init {
        refreshPermissions()
        refreshFolders()
        val installed = modelManager.installedVariant()
        _state.update {
            it.copy(
                screen = if (installed != null) Screen.HOME else Screen.SETUP,
                install = if (installed != null) {
                    InstallState.Installed(installed)
                } else {
                    InstallState.NotInstalled
                },
            )
        }
    }

    // ----------------------------------------------------------------- general

    fun goTo(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun selectLanguage(language: Language) {
        prefs.language = language
        _state.update { it.copy(language = language) }
    }

    fun refreshPermissions() {
        val app = getApplication<Application>()
        val mic = granted(app, android.Manifest.permission.RECORD_AUDIO)
        val media = if (Build.VERSION.SDK_INT >= 33) {
            listOf(
                android.Manifest.permission.READ_MEDIA_AUDIO,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_IMAGES,
            ).any { granted(app, it) }
        } else {
            @Suppress("DEPRECATION")
            granted(app, android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        _state.update { it.copy(micGranted = mic, mediaGranted = media) }
    }

    fun refreshFolders() {
        val rows = repo.folders().map { FolderRow(folderName(it), it) }
        _state.update { it.copy(folders = rows) }
    }

    // ----------------------------------------------------------------- setup

    fun installModel(variant: ModelVariant) {
        if (_state.value.install is InstallState.Downloading) return
        _state.update { it.copy(install = InstallState.Downloading("", 0, 1)) }
        viewModelScope.launch {
            modelManager.install(variant) { progress ->
                _state.update { it.copy(install = progress) }
            }
        }
    }

    fun cancelInstall() = modelManager.cancel()

    fun addFolder(uri: Uri) {
        runCatching { repo.addTree(uri) }
        indexCache = null
        refreshFolders()
    }

    fun removeFolder(uri: Uri) {
        repo.removeTree(uri)
        indexCache = null
        refreshFolders()
    }

    fun rebuildIndex() {
        if (_state.value.indexing) return
        _state.update { it.copy(indexing = true) }
        viewModelScope.launch {
            val index = withContext(Dispatchers.IO) { indexer.build() }
            indexCache = index
            _state.update { it.copy(indexing = false, indexSize = index.files.size) }
        }
    }

    // ----------------------------------------------------------------- listening

    fun onMicTapped() {
        if (!_state.value.micGranted) {
            pendingMicRequest = true
            return // the UI requests the permission and reports back
        }
        startListening()
    }

    fun onMicPermissionResult(granted: Boolean) {
        refreshPermissions()
        if (granted && pendingMicRequest) {
            pendingMicRequest = false
            startListening()
        } else {
            pendingMicRequest = false
            if (!granted) {
                _state.update {
                    it.copy(error = UiError("Microphone access is required", "Enable the microphone permission to speak file names."))
                }
            }
        }
    }

    fun onMediaPermissionResult() = refreshPermissions()

    fun stopListening() = recorder.requestStop()

    private fun startListening() {
        val current = _state.value
        if (current.homePhase != HomePhase.IDLE) return

        val variant = modelManager.installedVariant()
        if (variant == null) {
            _state.update {
                it.copy(
                    error = UiError("Speech model not installed", "Install the model in Setup first."),
                    screen = if (it.screen == Screen.HOME) Screen.SETUP else it.screen,
                )
            }
            return
        }

        _state.update {
            it.copy(
                homePhase = HomePhase.LISTENING,
                recognizedText = null,
                crossChecked = emptyList(),
                listResult = null,
                error = null,
                info = null,
            )
        }

        viewModelScope.launch {
            val recorded = recorder.record()
            if (recorded == null) {
                _state.update {
                    it.copy(
                        homePhase = HomePhase.IDLE,
                        error = UiError(
                            "Didn't catch any speech",
                            "Tap the microphone and say a file name, e.g. \"Open Resume\".",
                        ),
                    )
                }
                return@launch
            }

            _state.update { it.copy(homePhase = HomePhase.PROCESSING) }
            val language = _state.value.language

            val text = runCatching {
                withContext(Dispatchers.IO) {
                    engine.ensureLoaded(modelManager.dirFor(variant), variant, language)
                    engine.recognize(recorded.samples, recorded.sampleRate)
                }
            }.getOrElse { e ->
                _state.update {
                    it.copy(
                        homePhase = HomePhase.IDLE,
                        error = UiError("Recognition failed", e.message ?: "Unknown error"),
                    )
                }
                return@launch
            }

            if (text.isBlank()) {
                _state.update {
                    it.copy(
                        homePhase = HomePhase.IDLE,
                        recognizedText = "",
                        error = UiError("Couldn't understand that", "Please try again."),
                    )
                }
                return@launch
            }
            _state.update { it.copy(recognizedText = text) }

            val index = withContext(Dispatchers.IO) {
                indexCache ?: indexer.build().also { indexCache = it }
            }
            _state.update { it.copy(indexSize = index.files.size) }

            if (index.files.isEmpty() && index.folders.isEmpty()) {
                _state.update {
                    it.copy(
                        homePhase = HomePhase.IDLE,
                        error = UiError(
                            "No folders authorized yet",
                            "Open Folders and add the locations VoiceFile should search.",
                        ),
                    )
                }
                return@launch
            }

            val parsed = QueryNormalizer.parse(text, language)
            val result = withContext(Dispatchers.Default) {
                matcher.match(parsed, language, index)
            }
            when (result) {
                is MatchResult.AutoOpen -> {
                    // One clear match → open right away, no confirmation.
                    _state.update { it.copy(crossChecked = result.crossChecked) }
                    _openRequests.tryEmit(OpenRequest.File(result.entry))
                }
                is MatchResult.ShowList -> {
                    _state.update {
                        it.copy(
                            homePhase = HomePhase.IDLE,
                            listResult = result,
                            crossChecked = result.crossChecked,
                        )
                    }
                    if (result.folder != null) {
                        _openRequests.tryEmit(OpenRequest.Folder(result.folder))
                    }
                }
                is MatchResult.NoMatch -> {
                    _state.update {
                        it.copy(
                            homePhase = HomePhase.IDLE,
                            error = UiError("No file matched", result.reason),
                        )
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------- results

    fun openFile(entry: FileEntry) {
        _openRequests.tryEmit(OpenRequest.File(entry))
    }

    /** Leaves the results view and returns to the idle home layout. */
    fun dismissResults() {
        _state.update { it.copy(listResult = null, crossChecked = emptyList()) }
    }

    fun onOpenOutcome(request: OpenRequest, outcome: OpenOutcome) {
        when (request) {
            is OpenRequest.File -> when (outcome) {
                OpenOutcome.Opened -> _state.update {
                    it.copy(homePhase = HomePhase.IDLE, info = "Opened ${request.entry.name}")
                }
                OpenOutcome.NoApp -> _state.update {
                    it.copy(
                        homePhase = HomePhase.IDLE,
                        error = UiError(
                            "No app can open this file",
                            "${request.entry.name} (${request.entry.mimeType ?: "unknown type"}) — install an app that supports it.",
                        ),
                    )
                }
                is OpenOutcome.Failed -> _state.update {
                    it.copy(homePhase = HomePhase.IDLE, error = UiError("Couldn't open the file", outcome.reason))
                }
            }
            is OpenRequest.Folder -> when (outcome) {
                OpenOutcome.Opened -> _state.update {
                    it.copy(homePhase = HomePhase.IDLE, info = "Opened ${request.folder.name}", listResult = null)
                }
                OpenOutcome.NoApp -> _state.update {
                    it.copy(
                        homePhase = HomePhase.IDLE,
                        info = "Couldn't open the folder directly — choose a file from the list.",
                    )
                }
                is OpenOutcome.Failed -> _state.update {
                    it.copy(homePhase = HomePhase.IDLE, info = "Couldn't open the folder: ${outcome.reason}")
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun categoryTitle(category: Category): String = when (category) {
        Category.AUDIO -> "Songs & audio"
        Category.VIDEO -> "Videos"
        Category.IMAGE -> "Images"
        Category.DOCUMENT -> "Documents"
        Category.DOWNLOADS -> "Downloads"
    }

    // ----------------------------------------------------------------- helpers

    private fun granted(app: Application, permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private fun folderName(uri: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
            .substringAfterLast(':')
            .substringAfterLast('/')
            .ifEmpty { "Storage folder" }
    }.getOrDefault("Storage folder")

    override fun onCleared() {
        engine.release()
    }
}
