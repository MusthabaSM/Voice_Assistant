package com.voicefile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicefile.app.HomePhase
import com.voicefile.app.MainUiState
import com.voicefile.app.MainViewModel
import com.voicefile.app.Screen
import com.voicefile.app.files.FileSource
import com.voicefile.app.matching.Language
import com.voicefile.app.matching.MatchResult
import com.voicefile.app.speech.InstallState

/**
 * Home screen with two modes:
 *  - **Idle**: language selector + large mic button + status/feedback cards.
 *  - **Results**: a full-height results view (compact heard card, big list,
 *    always-visible retry mic bar) so matches are never squeezed into a
 *    small window at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: MainUiState,
    vm: MainViewModel,
    onOpenSetup: () -> Unit,
    onRequestMic: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VoiceFile", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { vm.goTo(Screen.FOLDERS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Folders and settings")
                    }
                },
            )
        },
    ) { padding ->
        val listResult = state.listResult
        if (listResult != null) {
            ResultsView(
                state = state,
                result = listResult,
                vm = vm,
                onRequestMic = onRequestMic,
                modifier = Modifier.padding(padding),
            )
        } else {
            IdleView(
                state = state,
                vm = vm,
                onOpenSetup = onOpenSetup,
                onRequestMic = onRequestMic,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

// ----------------------------------------------------------------- idle mode

@Composable
private fun IdleView(
    state: MainUiState,
    vm: MainViewModel,
    onOpenSetup: () -> Unit,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        verticalGap(4)
        if (state.install is InstallState.NotInstalled) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSetup),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Speech model not installed",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Tap to finish setup — recognition needs a one-time model install.",
                        fontSize = 13.sp,
                    )
                }
            }
        }

        verticalGap(12)
        Text("Language", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { lang ->
                Chip(
                    label = lang.display,
                    selected = state.language == lang,
                    onClick = { vm.selectLanguage(lang) },
                )
            }
        }

        verticalGap(16)
        MicButton(state = state, onTap = {
            handleMicTap(state, vm, onRequestMic)
        })

        verticalGap(8)
        statusLine(state)

        state.recognizedText?.let { heard ->
            verticalGap(8)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Heard",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (heard.isBlank()) "—" else "“$heard”",
                        fontSize = 15.sp,
                        fontStyle = FontStyle.Italic,
                    )
                    if (state.crossChecked.isNotEmpty()) {
                        verticalGap(4)
                        state.crossChecked.forEach { pair ->
                            Text(
                                "✓ $pair",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        state.error?.let { error ->
            verticalGap(8)
            ErrorCard(error = error, state = state, vm = vm, onRequestMic = onRequestMic)
        }

        state.info?.let { info ->
            verticalGap(8)
            InfoCard(info)
        }

        verticalGap(12)
        Text(
            "Speak a file name — VoiceFile finds it and opens it.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

// --------------------------------------------------------------- results mode

@Composable
private fun ResultsView(
    state: MainUiState,
    result: MatchResult.ShowList,
    vm: MainViewModel,
    onRequestMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {

        // Compact "heard" card with the cross-verification mappings.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Heard", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "“${state.recognizedText.orEmpty()}”",
                        fontSize = 15.sp,
                        fontStyle = FontStyle.Italic,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.crossChecked.isNotEmpty()) {
                        Text(
                            state.crossChecked.joinToString(" · ") { "✓ $it" },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { vm.dismissResults() }) {
                    Icon(Icons.Default.Close, contentDescription = "Close results")
                }
            }
        }

        if (result.closest) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        "Nothing matched exactly",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp,
                    )
                    Text(
                        "Showing the closest file names — tap the microphone and try again if these aren't right.",
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // Headline row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                result.headline,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.rebuildIndex() }) { Text("Refresh") }
        }

        // Big, comfortable list taking the rest of the screen.
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(result.matches) { scored ->
                val entry = scored.entry
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.openFile(entry) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(fileEmoji(entry), fontSize = 30.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val path = entry.path
                        Text(
                            when {
                                !path.isNullOrBlank() -> path
                                entry.source == FileSource.MEDIA -> "On this device"
                                else -> "Authorized folder"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        when {
                            entry.mimeType?.startsWith("audio/") == true -> "▶"
                            entry.mimeType?.startsWith("video/") == true -> "▶"
                            else -> "›"
                        },
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

        // Always-visible retry bar with a compact mic.
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactMic(state = state, onTap = { handleMicTap(state, vm, onRequestMic) })
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    when (state.homePhase) {
                        HomePhase.IDLE -> "Tap to speak again"
                        HomePhase.LISTENING -> "Listening… tap to stop"
                        HomePhase.PROCESSING -> "Working…"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Say another file name in ${state.language.display}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ----------------------------------------------------------------- shared bits

private fun handleMicTap(state: MainUiState, vm: MainViewModel, onRequestMic: () -> Unit) {
    val listening = state.homePhase == HomePhase.LISTENING
    if (listening) {
        vm.stopListening()
    } else {
        vm.onMicTapped() // records intent if the permission is missing
        if (!state.micGranted) onRequestMic()
    }
}

@Composable
private fun ErrorCard(
    error: com.voicefile.app.UiError,
    state: MainUiState,
    vm: MainViewModel,
    onRequestMic: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(error.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            error.detail?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, fontSize = 13.sp)
            }
            Row {
                TextButton(onClick = { handleMicTap(state, vm, onRequestMic) }) { Text("Try again") }
                TextButton(onClick = { vm.dismissError() }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun statusLine(state: MainUiState) {
    val modelText = when (val install = state.install) {
        is InstallState.Installed -> "Offline model ready (${install.variant.title})"
        is InstallState.NotInstalled -> "Model missing"
        else -> "Model status changing…"
    }
    val folderText = when (state.folders.size) {
        0 -> "no folders authorized"
        1 -> "1 folder authorized"
        else -> "${state.folders.size} folders authorized"
    }
    val indexText = state.indexSize?.let { "$it files indexed" } ?: "index not built yet"
    Column {
        Text(
            "$modelText · $folderText",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(indexText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MicButton(state: MainUiState, onTap: () -> Unit) {
    val phase = state.homePhase
    val background = when (phase) {
        HomePhase.LISTENING -> MaterialTheme.colorScheme.error
        HomePhase.IDLE -> MaterialTheme.colorScheme.primary
        HomePhase.PROCESSING -> MaterialTheme.colorScheme.secondary
    }
    val enabled = phase != HomePhase.PROCESSING

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = enabled, onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                HomePhase.PROCESSING -> CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                )
                HomePhase.LISTENING -> Text("⏹", fontSize = 40.sp, color = Color.White)
                HomePhase.IDLE -> Text("🎤", fontSize = 44.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (phase) {
                HomePhase.IDLE -> "Tap to speak"
                HomePhase.LISTENING -> "Listening… tap to stop"
                HomePhase.PROCESSING -> "Working…"
            },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Small mic used in the results retry bar. */
@Composable
private fun CompactMic(state: MainUiState, onTap: () -> Unit) {
    val phase = state.homePhase
    val background = when (phase) {
        HomePhase.LISTENING -> MaterialTheme.colorScheme.error
        HomePhase.IDLE -> MaterialTheme.colorScheme.primary
        HomePhase.PROCESSING -> MaterialTheme.colorScheme.secondary
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = phase != HomePhase.PROCESSING, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            HomePhase.PROCESSING -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            HomePhase.LISTENING -> Text("⏹", fontSize = 22.sp, color = Color.White)
            HomePhase.IDLE -> Text("🎤", fontSize = 24.sp)
        }
    }
}
