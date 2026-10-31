package com.voicefile.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicefile.app.MainUiState
import com.voicefile.app.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    state: MainUiState,
    vm: MainViewModel,
    onBack: () -> Unit,
    onAddFolder: () -> Unit,
    onRequestMedia: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folders", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            verticalGap(8)
            Text(
                "VoiceFile searches only the folders you authorize here, plus media files " +
                    "your device lets it read. Nothing else on the phone is accessed.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            verticalGap(10)
            Button(onClick = onAddFolder, modifier = Modifier.fillMaxWidth()) {
                Text("Add a folder…")
            }

            if (!state.mediaGranted) {
                verticalGap(6)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Media access not granted",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Text(
                            "Songs, videos and photos outside your folders are found only " +
                                "with media permission.",
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = onRequestMedia) { Text("Grant media access") }
                    }
                }
            }

            verticalGap(10)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Authorized folders (${state.folders.size})",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.indexing) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                TextButtonSmall(
                    label = if (state.indexing) "Indexing…" else "Rebuild index",
                    enabled = !state.indexing,
                    onClick = { vm.rebuildIndex() },
                )
            }
            state.indexSize?.let {
                Text(
                    "Index: $it files",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            verticalGap(4)
            if (state.folders.isEmpty()) {
                Text(
                    "No folders yet. Tap “Add a folder…” and pick e.g. Downloads or Documents.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.folders) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("\uD83D\uDCC1", fontSize = 20.sp) // 📁
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(folder.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    folder.uri.toString(),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { vm.removeFolder(folder.uri) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove ${folder.name}",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextButtonSmall(label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, enabled = enabled) {
        Text(label, fontSize = 13.sp)
    }
}
