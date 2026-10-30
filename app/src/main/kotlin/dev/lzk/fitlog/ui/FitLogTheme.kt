package dev.lzk.fitlog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object FitLogColors {
    val Background = Color(0xFFF7F8FC)
    val Teal = Color(0xFF287F84)
    val Mint = Color(0xFFDDF2F0)
    val Lavender = Color(0xFFECEAFB)
    val Cream = Color(0xFFFFF2C5)
    val Ink = Color(0xFF1F2937)
    val Muted = Color(0xFF6B7280)
    val Success = Color(0xFF438E80)
    val Border = Color(0xFFE8EBF2)
}
@Composable
fun FitLogTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (darkTheme) darkColorScheme(
        primary = Color(0xFF91D4D1), onPrimary = Color(0xFF093B3F),
        primaryContainer = Color(0xFF1C4143), onPrimaryContainer = Color(0xFFCEF4F0),
        secondaryContainer = Color(0xFF343146), onSecondaryContainer = Color(0xFFEAE5FF),
        tertiaryContainer = Color(0xFF49412A), onTertiaryContainer = Color(0xFFFFEDB2),
        background = Color(0xFF15191F), onBackground = Color(0xFFE2E7EF),
        surface = Color(0xFF1D222B), onSurface = Color(0xFFE2E7EF),
        surfaceVariant = Color(0xFF303745), onSurfaceVariant = Color(0xFFB1BAC8),
        outline = Color(0xFF424B59), outlineVariant = Color(0xFF343D4B),
    ) else lightColorScheme(
        primary = FitLogColors.Teal, onPrimary = Color.White,
        primaryContainer = FitLogColors.Mint, onPrimaryContainer = FitLogColors.Teal,
        secondaryContainer = FitLogColors.Lavender, onSecondaryContainer = FitLogColors.Ink,
        tertiaryContainer = FitLogColors.Cream, onTertiaryContainer = FitLogColors.Ink,
        background = FitLogColors.Background, onBackground = FitLogColors.Ink,
        surface = Color.White, onSurface = FitLogColors.Ink,
        surfaceVariant = FitLogColors.Lavender, onSurfaceVariant = FitLogColors.Muted,
        outline = FitLogColors.Border, outlineVariant = FitLogColors.Border,
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
