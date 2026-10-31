package com.voicefile.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicefile.app.files.FileEntry

/** Small rounded language/mode chip built from stable foundation APIs only. */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = textColor, fontSize = 14.sp)
    }
}

/** Emoji glyph for a file's type — script-neutral, needs no icon library. */
fun fileEmoji(entry: FileEntry): String {
    val mime = entry.mimeType?.lowercase() ?: ""
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("audio/") || ext in AUDIO_EXTS -> "\uD83C\uDFB5"   // 🎵
        mime.startsWith("video/") || ext in VIDEO_EXTS -> "\uD83C\uDFAC"   // 🎬
        mime.startsWith("image/") || ext in IMAGE_EXTS -> "\uD83D\uDDBC\uFE0F" // 🖼️
        mime.startsWith("text/") || ext in DOC_EXTS -> "\uD83D\uDCC4"       // 📄
        else -> "\uD83D\uDCE6"                                              // 📦
    }
}

private val AUDIO_EXTS = setOf("mp3", "m4a", "wav", "flac", "ogg", "aac", "opus", "amr", "wma")
private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "mpg", "mpeg")
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")
private val DOC_EXTS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "epub",
)

/** Soft informational banner (green for success). */
@Composable
fun InfoCard(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE7F6EC)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            color = Color(0xFF1B5E20),
            fontSize = 14.sp,
        )
    }
}

/** Section heading used across screens. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
fun LabeledStatusRow(label: String, granted: Boolean, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (granted) "\u2705" else "\u26A0\uFE0F", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (!granted) {
                Text(
                    "Not granted yet",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun Hint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
fun verticalGap(height: Int) {
    Spacer(Modifier.height(height.dp))
}
