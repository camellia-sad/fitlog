package dev.lzk.fitlog.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import java.time.LocalTime

/** Day periods of the time theme, switched at fixed hours so no location permission is needed. */
enum class DayPeriod {
    DAWN, DAY, DUSK, NIGHT;
    companion object {
        fun at(time: LocalTime): DayPeriod = when (time.hour) { in 5..8 -> DAWN; in 9..15 -> DAY; in 16..18 -> DUSK; else -> NIGHT }
    }
}

/**
 * Holds the applied day period while the workout page is open, so the palette never changes
 * mid-set; the new period is applied once the user leaves the page.
 */
object ThemeClock {
    var holdForWorkout by mutableStateOf(false)
}

/** Sky colours top → bottom (the page paper is appended when drawing) plus optional decorations. */
@Immutable
data class SkyPalette(
    val stops: List<Color>, val sun: Color? = null, val sunY: Float = .5f,
    val cloud: Color? = null, val moon: Color? = null, val stars: Color? = null,
)

/**
 * Light-pixel palette. Colours are assigned by job: teal acts, orange marks achievements,
 * muscle hues only classify. Material roles cover surfaces and text; [grid] and [sky] belong to the backdrop.
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
    val grid: Color = Color.Transparent,
    val sky: SkyPalette = SkyPalette(emptyList()),
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

private val LightScheme = lightColorScheme(
    primary = Color(0xFF23797E), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCF1EE), onPrimaryContainer = Color(0xFF175A5E),
    secondaryContainer = Color(0xFFECE9FB), onSecondaryContainer = Color(0xFF1A2332),
    tertiaryContainer = Color(0xFFFFF0D4), onTertiaryContainer = Color(0xFF1A2332),
    background = Color(0xFFF5F7FA), onBackground = Color(0xFF1A2332),
    surface = Color.White, onSurface = Color(0xFF1A2332),
    surfaceVariant = Color(0xFFEEF1F6), onSurfaceVariant = Color(0xFF5A6477),
    outline = Color(0xFFCDD3DD), outlineVariant = Color(0xFFE2E6EE),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FCBC4), onPrimary = Color(0xFF0A2B2C),
    primaryContainer = Color(0xFF1B3533), onPrimaryContainer = Color(0xFFCDF1EC),
    secondaryContainer = Color(0xFF262339), onSecondaryContainer = Color(0xFFE8E4FF),
    tertiaryContainer = Color(0xFF382D1C), onTertiaryContainer = Color(0xFFFFE6BC),
    background = Color(0xFF12161D), onBackground = Color(0xFFE6EAF1),
    surface = Color(0xFF1A2029), onSurface = Color(0xFFE6EAF1),
    surfaceVariant = Color(0xFF242B36), onSurfaceVariant = Color(0xFFA0A9B8),
    outline = Color(0xFF3A4352), outlineVariant = Color(0xFF2A313D),
)

/* ---------- time theme: page paper is the sky's horizon colour ---------- */

private fun ColorScheme.tone(background: Long, surface: Long, surfaceVariant: Long, secondaryContainer: Long,
    outlineVariant: Long, primaryContainer: Long, onSurfaceVariant: Long? = null, outline: Long? = null) = copy(
    background = Color(background), surface = Color(surface), surfaceVariant = Color(surfaceVariant),
    secondaryContainer = Color(secondaryContainer), outlineVariant = Color(outlineVariant), primaryContainer = Color(primaryContainer),
    onSurfaceVariant = onSurfaceVariant?.let(::Color) ?: this.onSurfaceVariant, outline = outline?.let(::Color) ?: this.outline,
)

private fun timeScheme(period: DayPeriod): ColorScheme = when (period) {
    DayPeriod.DAWN -> LightScheme.tone(0xFFFBF2EB, 0xFFFFFDFB, 0xFFF5EAE2, 0xFFF1E6EF, 0xFFEEDFD5, 0xFFE3F0EA)
    DayPeriod.DAY -> LightScheme.tone(0xFFEDF5FA, 0xFFFFFFFF, 0xFFE4EEF5, 0xFFE4EAF7, 0xFFD9E5EE, 0xFFD9EFEC)
    DayPeriod.DUSK -> LightScheme.tone(0xFFF7EEF2, 0xFFFFFFFF, 0xFFF0E5EC, 0xFFEDE3F5, 0xFFE8DAE3, 0xFFE2EEEC)
    DayPeriod.NIGHT -> DarkScheme.tone(0xFF10162A, 0xFF182038, 0xFF212A44, 0xFF262E4E, 0xFF28314F, 0xFF17343A,
        onSurfaceVariant = 0xFFA3ACC4, outline = 0xFF3A4466)
}

private fun sky(vararg stops: Long) = stops.map(::Color)

private fun timeFit(period: DayPeriod): FitColors = when (period) {
    DayPeriod.DAWN -> LightFit.copy(grid = Color(0x14B9711A),
        sky = SkyPalette(sky(0xFFEC9F86, 0xFFF2B296, 0xFFF6C4A8, 0xFFF9D5BC), sun = Color(0xFFF79A36), sunY = .55f))
    DayPeriod.DAY -> LightFit.copy(grid = Color(0x1423797E),
        sky = SkyPalette(sky(0xFF7DBFE4, 0xFF96CCEA, 0xFFB0D9F0, 0xFFCBE6F5), sun = Color(0xFFFFD66B), sunY = .16f, cloud = Color.White))
    DayPeriod.DUSK -> LightFit.copy(grid = Color(0x149479E0),
        sky = SkyPalette(sky(0xFF9686D0, 0xFFB394CE, 0xFFD1A2C4, 0xFFE8B6BC, 0xFFF2CFC9), sun = Color(0xFFF2A13B), sunY = .6f))
    DayPeriod.NIGHT -> DarkFit.copy(grid = Color(0x0DFFF6D8),
        chestTint = Color(0xFF3A2233), shouldersTint = Color(0xFF33291E), armsTint = Color(0xFF2A2648),
        legsTint = Color(0xFF1A3030), backTint = Color(0xFF1D2A44), coreTint = Color(0xFF18303A),
        sky = SkyPalette(sky(0xFF30347A, 0xFF2A2F6C, 0xFF24295D, 0xFF1D234C, 0xFF161D3B), moon = Color(0xFFFFF0D4), stars = Color(0xFFFFF6D8)))
}

/* ---------- system / fixed light and dark: neutral paper, sky still follows the clock ---------- */

private fun lightSky(period: DayPeriod) = when (period) {
    DayPeriod.DAWN -> SkyPalette(sky(0xFFEFAE95, 0xFFF4C1A8, 0xFFF7D3C0, 0xFFF8E2D8), sun = Color(0xFFF79A36), sunY = .55f)
    DayPeriod.DAY -> SkyPalette(sky(0xFF8DC7E8, 0xFFA8D4EE, 0xFFC3E2F3, 0xFFDBEDF7), sun = Color(0xFFFFD66B), sunY = .16f, cloud = Color.White)
    DayPeriod.DUSK -> SkyPalette(sky(0xFFA595D6, 0xFFBFA3D3, 0xFFD8B5CF, 0xFFE9CCD6), sun = Color(0xFFF2A13B), sunY = .6f)
    DayPeriod.NIGHT -> SkyPalette(sky(0xFF7C84C4, 0xFF959CCF, 0xFFB0B5DB, 0xFFCBCEE6), moon = Color(0xFFFFF6E0), stars = Color.White)
}

private fun darkSky(period: DayPeriod) = when (period) {
    DayPeriod.DAWN -> SkyPalette(sky(0xFF6A4038, 0xFF573631, 0xFF432D2B, 0xFF2E2326), sun = Color(0xFFE8963F), sunY = .55f)
    DayPeriod.DAY -> SkyPalette(sky(0xFF235580, 0xFF1F476A, 0xFF1B3953, 0xFF172B3D), sun = Color(0xFFE8C060), sunY = .16f, cloud = Color(0xFF33607E))
    DayPeriod.DUSK -> SkyPalette(sky(0xFF56408C, 0xFF523A72, 0xFF4A3459, 0xFF352A42), sun = Color(0xFFE8963F), sunY = .6f)
    DayPeriod.NIGHT -> SkyPalette(sky(0xFF2C3270, 0xFF262B60, 0xFF20254F, 0xFF1A1F3C), moon = Color(0xFFF0E8CC), stars = Color(0xFFF3EED8))
}

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

/** Dialogs, sheets and menus read Material's surface containers; keep them on the card colour of the active palette. */
private fun ColorScheme.withContainers() = copy(
    surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = surface, surfaceContainerHighest = surfaceVariant, surfaceBright = surface, surfaceDim = background,
)

@Composable
private fun animate(color: Color) = animateColorAsState(color, tween(600), label = "主题色").value

/** Palette changes (a new day period, or a theme switch) fade over 600 ms instead of snapping. */
@Composable
private fun ColorScheme.animated(): ColorScheme = copy(
    primary = animate(primary), onPrimary = animate(onPrimary), primaryContainer = animate(primaryContainer),
    onPrimaryContainer = animate(onPrimaryContainer), secondaryContainer = animate(secondaryContainer),
    onSecondaryContainer = animate(onSecondaryContainer), tertiaryContainer = animate(tertiaryContainer),
    background = animate(background), onBackground = animate(onBackground), surface = animate(surface), onSurface = animate(onSurface),
    surfaceVariant = animate(surfaceVariant), onSurfaceVariant = animate(onSurfaceVariant), outline = animate(outline),
    outlineVariant = animate(outlineVariant), surfaceContainerLowest = animate(surfaceContainerLowest),
    surfaceContainerLow = animate(surfaceContainerLow), surfaceContainer = animate(surfaceContainer),
    surfaceContainerHigh = animate(surfaceContainerHigh), surfaceContainerHighest = animate(surfaceContainerHighest),
)

/** Whether the palette that [FitLogTheme] will apply is dark; used for system bar icon colour. */
fun isFitThemeDark(darkTheme: Boolean, period: DayPeriod, timeTheme: Boolean) = if (timeTheme) period == DayPeriod.NIGHT else darkTheme

/**
 * [timeTheme] uses the four day-period palettes (night is dark) and ignores [darkTheme];
 * otherwise the neutral light or dark palette applies and only the sky follows [period].
 */
@Composable
fun FitLogTheme(darkTheme: Boolean = isSystemInDarkTheme(), period: DayPeriod = DayPeriod.DAY, timeTheme: Boolean = false,
    content: @Composable () -> Unit) {
    val dark = isFitThemeDark(darkTheme, period, timeTheme)
    val scheme = (if (timeTheme) timeScheme(period) else if (dark) DarkScheme else LightScheme).withContainers()
    val fit = when {
        timeTheme -> timeFit(period)
        dark -> DarkFit.copy(grid = Color(0x126FCBC4), sky = darkSky(period))
        else -> LightFit.copy(grid = Color(0x1623797E), sky = lightSky(period))
    }
    CompositionLocalProvider(LocalFitColors provides fit) {
        MaterialTheme(colorScheme = scheme.animated(), typography = FitType, shapes = FitShapes, content = content)
    }
}
