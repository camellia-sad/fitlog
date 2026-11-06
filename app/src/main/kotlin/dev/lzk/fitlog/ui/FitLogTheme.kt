package dev.lzk.fitlog.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.R

/**
 * Light-pixel palette. Colors are assigned by job: teal acts, orange marks achievements,
 * muscle hues only classify. Material roles cover surfaces and text.
 */
@Immutable
data class FitColors(
    val tealDeep: Color,
    val orange: Color,
    val orangeDeep: Color,
    val cream: Color,
    val success: Color,
    val chest: Color, val chestTint: Color,
    val back: Color, val backTint: Color,
    val legs: Color, val legsTint: Color,
    val shoulders: Color, val shouldersTint: Color,
    val arms: Color, val armsTint: Color,
    val core: Color, val coreTint: Color,
)

private val LightFit = FitColors(
    tealDeep = Color(0xFF175A5E), orange = Color(0xFFF2A13B), orangeDeep = Color(0xFFB9711A), cream = Color(0xFFFFF0D4),
    success = Color(0xFF3F8F7F),
    chest = Color(0xFFE9798D), chestTint = Color(0xFFFCE7EB), back = Color(0xFF5E95E0), backTint = Color(0xFFE5EEFB),
    legs = Color(0xFF43A877), legsTint = Color(0xFFE0F3E8), shoulders = Color(0xFFD9A42F), shouldersTint = Color(0xFFFAF0D6),
    arms = Color(0xFF9479E0), armsTint = Color(0xFFEEE8FC), core = Color(0xFF3FA6C8), coreTint = Color(0xFFDFF2F8),
)

private val DarkFit = FitColors(
    tealDeep = Color(0xFF2E716D), orange = Color(0xFFF5B35C), orangeDeep = Color(0xFFF5B35C), cream = Color(0xFF382D1C),
    success = Color(0xFF6CC2AF),
    chest = Color(0xFFF08FA1), chestTint = Color(0xFF3A2229), back = Color(0xFF7EAAEC), backTint = Color(0xFF1D2A3D),
    legs = Color(0xFF62C08F), legsTint = Color(0xFF1A3026), shoulders = Color(0xFFE8BA52), shouldersTint = Color(0xFF352C1A),
    arms = Color(0xFFA994EC), armsTint = Color(0xFF29233D), core = Color(0xFF5FBBD8), coreTint = Color(0xFF192E36),
)

private val LocalFitColors = staticCompositionLocalOf { LightFit }

/** FitLog-specific colors that Material's scheme has no role for. */
val fitColors: FitColors
    @Composable @ReadOnlyComposable get() = LocalFitColors.current

/**
 * Jersey 10 (OFL, see assets/licenses): a pixel face drawn after sports-jersey numbers, chosen for
 * unambiguous 5/8/2 digits. Used only for numbers, timers and short Latin labels; it has one weight.
 */
val PixelFont = FontFamily(Font(R.font.jersey_10, FontWeight.Normal))

/** Jersey 10 is condensed, so sizes are scaled up to match the optical size of the system Chinese face. */
@Suppress("UNUSED_PARAMETER")
fun pixelNumber(size: TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = PixelFont, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None,
        fontSize = size * 1.2f, lineHeight = size * 1.3f)

private val BaseType = Typography()
private val FitType = BaseType.copy(
    headlineMedium = BaseType.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Two shape families only: soft radii for containers, [PixelShape] for things that act or show state. */
private val FitShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FitLogTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (darkTheme) darkColorScheme(
        primary = Color(0xFF6FCBC4), onPrimary = Color(0xFF0A2B2C),
        primaryContainer = Color(0xFF1B3533), onPrimaryContainer = Color(0xFFCDF1EC),
        secondaryContainer = Color(0xFF262339), onSecondaryContainer = Color(0xFFE8E4FF),
        tertiaryContainer = Color(0xFF382D1C), onTertiaryContainer = Color(0xFFFFE6BC),
        background = Color(0xFF12161D), onBackground = Color(0xFFE6EAF1),
        surface = Color(0xFF1A2029), onSurface = Color(0xFFE6EAF1),
        surfaceVariant = Color(0xFF242B36), onSurfaceVariant = Color(0xFFA0A9B8),
        outline = Color(0xFF3A4352), outlineVariant = Color(0xFF2A313D),
    ) else lightColorScheme(
        primary = Color(0xFF23797E), onPrimary = Color.White,
        primaryContainer = Color(0xFFDCF1EE), onPrimaryContainer = Color(0xFF175A5E),
        secondaryContainer = Color(0xFFECE9FB), onSecondaryContainer = Color(0xFF1A2332),
        tertiaryContainer = Color(0xFFFFF0D4), onTertiaryContainer = Color(0xFF1A2332),
        background = Color(0xFFF5F7FA), onBackground = Color(0xFF1A2332),
        surface = Color.White, onSurface = Color(0xFF1A2332),
        surfaceVariant = Color(0xFFEEF1F6), onSurfaceVariant = Color(0xFF5A6477),
        outline = Color(0xFFCDD3DD), outlineVariant = Color(0xFFE2E6EE),
    )
    CompositionLocalProvider(LocalFitColors provides if (darkTheme) DarkFit else LightFit) {
        MaterialTheme(colorScheme = scheme, typography = FitType, shapes = FitShapes, content = content)
    }
}
