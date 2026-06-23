package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
  primary = TerminalBrightGreen,
  secondary = TerminalBrightCyan,
  tertiary = TerminalAmber,
  background = TerminalBg,
  surface = TerminalSurface,
  onPrimary = TerminalBg,
  onSecondary = TerminalBg,
  onBackground = TerminalWhite,
  onSurface = TerminalWhite,
  surfaceVariant = TerminalSurfaceVariant,
  onSurfaceVariant = TerminalGray
)

private val LightColorScheme = DarkColorScheme

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Default to terminal dark style!
  dynamicColor: Boolean = false, // Enforce our glorious Custom developer theme style
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
