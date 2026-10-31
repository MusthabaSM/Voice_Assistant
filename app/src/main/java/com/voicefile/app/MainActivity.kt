package com.voicefile.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicefile.app.files.FileOpener
import com.voicefile.app.ui.FoldersScreen
import com.voicefile.app.ui.HomeScreen
import com.voicefile.app.ui.SetupScreen
import com.voicefile.app.ui.VoiceFileTheme

/**
 * Single activity. Everything is Compose; file access goes through SAF/
 * MediaStore and file opening through ACTION_VIEW with content-URI grants.
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.onMicPermissionResult(granted)
        }

    private val mediaLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.onMediaPermissionResult()
        }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) vm.addFolder(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VoiceFileTheme {
                val state by vm.state.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    vm.openRequests.collect { request -> handleOpenRequest(request) }
                }

                when (state.screen) {
                    Screen.SETUP -> SetupScreen(
                        state = state,
                        vm = vm,
                        onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onRequestMedia = { mediaLauncher.launch(mediaPermissions()) },
                        onAddFolder = { folderLauncher.launch(null) },
                        onDone = { vm.goTo(Screen.HOME) },
                    )
                    Screen.HOME -> HomeScreen(
                        state = state,
                        vm = vm,
                        onOpenSetup = { vm.goTo(Screen.SETUP) },
                        onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    )
                    Screen.FOLDERS -> FoldersScreen(
                        state = state,
                        vm = vm,
                        onBack = { vm.goTo(Screen.HOME) },
                        onAddFolder = { folderLauncher.launch(null) },
                        onRequestMedia = { mediaLauncher.launch(mediaPermissions()) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
        vm.refreshFolders()
    }

    private fun handleOpenRequest(request: OpenRequest) {
        if (isFinishing || isDestroyed) return
        val outcome = when (request) {
            is OpenRequest.File -> FileOpener.open(this, request.entry)
            is OpenRequest.Folder -> FileOpener.openFolder(this, request.folder)
        }
        vm.onOpenOutcome(request, outcome)
    }

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
}
