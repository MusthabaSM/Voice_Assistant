package com.voicefile.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VoiceFileColors = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF0E1),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635F),
    secondaryContainer = Color(0xFFCCE8E2),
    surface = Color(0xFFFAFDFB),
    background = Color(0xFFFAFDFB),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
)

@Composable
fun VoiceFileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VoiceFileColors, content = content)
}
