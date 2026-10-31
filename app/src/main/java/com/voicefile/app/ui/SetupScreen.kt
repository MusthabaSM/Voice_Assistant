package com.voicefile.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicefile.app.MainUiState
import com.voicefile.app.MainViewModel
import com.voicefile.app.speech.InstallState
import com.voicefile.app.speech.ModelVariant
import com.voicefile.app.speech.Models

@Composable
fun SetupScreen(
    state: MainUiState,
    vm: MainViewModel,
    onRequestMic: () -> Unit,
    onRequestMedia: () -> Unit,
    onAddFolder: () -> Unit,
    onDone: () -> Unit,
) {
    var selectedVariant by remember { mutableStateOf(Models.SMALL) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        verticalGap(16)
        Text("One-time setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "VoiceFile works fully offline after these three steps.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        verticalGap(12)
        StepCard(
            number = 1,
            title = "Install the speech model",
            subtitle = "Required. The download needs internet once; afterwards the app never touches the network.",
        ) {
            when (val install = state.install) {
                is InstallState.Installed -> {
                    Text(
                        "\u2705 Model ready: ${install.variant.title}",
                        fontWeight = FontWeight.Medium,
                    )
                }
                is InstallState.Downloading -> {
                    val fraction = if (install.total > 0) {
                        install.received.toFloat() / install.total
                    } else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    val receivedMb = install.received / (1024.0 * 1024.0)
                    val totalMb = install.total / (1024.0 * 1024.0)
                    Text(
                        "Downloading ${install.fileName.ifEmpty { "…" }} — %.0f / %.0f MB".format(receivedMb, totalMb),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { vm.cancelInstall() }) { Text("Cancel") }
                }
                is InstallState.Failed -> {
                    Text(
                        "Download failed: ${install.message}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { vm.installModel(selectedVariant) }) { Text("Retry install") }
                }
                is InstallState.NotInstalled -> {
                    Models.ALL.forEach { variant ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedVariant = variant },
                        ) {
                            RadioButton(
                                selected = selectedVariant == variant,
                                onClick = { selectedVariant = variant },
                            )
                            Column {
                                Text(variant.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    variant.description,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { vm.installModel(selectedVariant) }) {
                        Text("Download & install (~${selectedVariant.approxMb} MB)")
                    }
                    Hint(
                        "Internet is only needed for this download. Models are OpenAI Whisper " +
                            "(Apache-2.0) via sherpa-onnx; audio never leaves the phone.",
                    )
                }
            }
        }

        StepCard(
            number = 2,
            title = "Microphone & media access",
            subtitle = "The microphone hears your request. Media access helps find songs, videos and photos outside your chosen folders.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LabeledStatusRow("Microphone", state.micGranted)
                    LabeledStatusRow("Media library (optional)", state.mediaGranted)
                }
            }
            Row {
                if (!state.micGranted) {
                    Button(onClick = onRequestMic) { Text("Grant microphone") }
                }
                if (!state.mediaGranted) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onRequestMedia) { Text("Grant media") }
                }
            }
        }

        StepCard(
            number = 3,
            title = "Choose folders to search",
            subtitle = "Pick locations like Downloads, Documents, Music, Movies or Pictures. Add as many as you like — the choice is remembered.",
        ) {
            Text(
                when (state.folders.size) {
                    0 -> "No folders authorized yet."
                    1 -> "1 folder authorized: ${state.folders.first().name}"
                    else -> "${state.folders.size} folders authorized: " +
                        state.folders.joinToString { it.name }
                },
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Button(onClick = onAddFolder) { Text("Add a folder") }
        }

        Spacer(Modifier.height(20.dp))
        val modelReady = state.install is InstallState.Installed
        Button(
            onClick = onDone,
            enabled = modelReady,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start using VoiceFile")
        }
        if (!modelReady) {
            Hint("Install the speech model to continue.")
        } else if (!state.micGranted || state.folders.isEmpty()) {
            Hint("Some steps are still pending — you can finish them later from Settings.")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$number",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            content()
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
