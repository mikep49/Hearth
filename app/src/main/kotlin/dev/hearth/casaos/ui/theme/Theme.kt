package dev.hearth.casaos.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic colors Material 3 has no slot for. */
@Immutable
data class HearthColors(
    val good: Color,
    val warn: Color,
    val bad: Color,
    val cpu: Color,
    val memory: Color,
    val storage: Color,
    val download: Color,
    val upload: Color,
)

val LocalHearthColors = staticCompositionLocalOf {
    HearthColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

val MaterialTheme.hearth: HearthColors
    @Composable get() = LocalHearthColors.current

/** The four accent roles a palette overrides; everything else stays neutral. */
@Immutable
data class Accent(val primary: Color, val onPrimary: Color, val container: Color, val onContainer: Color)

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

enum class Palette(val label: String, val dark: Accent, val light: Accent) {
    Ember(
        "Ember",
        dark = Accent(Color(0xFFFF8A4C), Color(0xFF2A1100), Color(0xFF4A2410), Color(0xFFFFDBC8)),
        light = Accent(Color(0xFFC4561A), Color.White, Color(0xFFFFDBC8), Color(0xFF3A1500)),
    ),
    Ocean(
        "Ocean",
        dark = Accent(Color(0xFF5EB8FF), Color(0xFF00315A), Color(0xFF123B5E), Color(0xFFD1E4FF)),
        light = Accent(Color(0xFF0061A4), Color.White, Color(0xFFD1E4FF), Color(0xFF001D36)),
    ),
    Forest(
        "Forest",
        dark = Accent(Color(0xFF5FD48C), Color(0xFF00391C), Color(0xFF17462B), Color(0xFFB8F2CB)),
        light = Accent(Color(0xFF1B6D3F), Color.White, Color(0xFFB8F2CB), Color(0xFF002110)),
    ),
    Violet(
        "Violet",
        dark = Accent(Color(0xFFB69CFF), Color(0xFF2A0F6B), Color(0xFF3A2A6E), Color(0xFFE8DDFF)),
        light = Accent(Color(0xFF6750A4), Color.White, Color(0xFFE8DDFF), Color(0xFF21005D)),
    ),
    Rose(
        "Rose",
        dark = Accent(Color(0xFFFF7EA8), Color(0xFF5C0028), Color(0xFF5A1A33), Color(0xFFFFD9E2)),
        light = Accent(Color(0xFFB0306A), Color.White, Color(0xFFFFD9E2), Color(0xFF3E001D)),
    ),

    /** Material You: colors from the wallpaper. Android 12+ only; falls back to Ember elsewhere. */
    Wallpaper("Dynamic", dark = Ember.dark, light = Ember.light);

    companion object {
        val dynamicSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        /** Palettes this device can actually show. */
        val available: List<Palette> get() = entries.filter { it != Wallpaper || dynamicSupported }
    }
}

@Immutable
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.System,
    val palette: Palette = Palette.Ember,
    /** True black surfaces in dark mode, for OLED screens. */
    val pureBlack: Boolean = false,
)

@Composable
fun ThemeSettings.isDark(): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

private val DarkBase = darkColorScheme(
    secondary = Color(0xFF9FB4D6),
    onSecondary = Color(0xFF14233B),
    tertiary = Color(0xFF4CD9C0),
    onTertiary = Color(0xFF00382F),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF0B0D12),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF222733),
    onSurfaceVariant = Color(0xFFA6ACBB),
    surfaceContainerLowest = Color(0xFF08090D),
    surfaceContainerLow = Color(0xFF10131A),
    surfaceContainer = Color(0xFF151922),
    surfaceContainerHigh = Color(0xFF1B202B),
    surfaceContainerHighest = Color(0xFF232937),
    outline = Color(0xFF5B6272),
    outlineVariant = Color(0xFF2A303D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF410004),
)

private val LightBase = lightColorScheme(
    secondary = Color(0xFF4A5C7C),
    tertiary = Color(0xFF006B5C),
    background = Color(0xFFFBF8F5),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFBF8F5),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFEDE7E2),
    onSurfaceVariant = Color(0xFF55535A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F1EC),
    surfaceContainer = Color(0xFFF1EBE5),
    surfaceContainerHigh = Color(0xFFEBE4DD),
    surfaceContainerHighest = Color(0xFFE5DED6),
    outline = Color(0xFF857F86),
    outlineVariant = Color(0xFFD9D2CC),
)

// Secondary containers back the nav-bar indicator, selected chips and tonal buttons: tint them with the
// accent so switching palette recolors the whole app, not just the primary buttons.
private fun ColorScheme.withAccent(a: Accent) = copy(
    primary = a.primary,
    onPrimary = a.onPrimary,
    primaryContainer = a.container,
    onPrimaryContainer = a.onContainer,
    secondaryContainer = a.container,
    onSecondaryContainer = a.onContainer,
    surfaceTint = a.primary,
)

private fun ColorScheme.pureBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF07080A),
    surfaceContainer = Color(0xFF0E1013),
    surfaceContainerHigh = Color(0xFF15171B),
    surfaceContainerHighest = Color(0xFF1C1F24),
    outlineVariant = Color(0xFF22262E),
)

private val DarkHearth = HearthColors(
    good = Color(0xFF4ADE80), warn = Color(0xFFFBBF24), bad = Color(0xFFFF6B6B),
    cpu = Color(0xFFFF8A4C), memory = Color(0xFF7AA2FF), storage = Color(0xFFB794F6),
    download = Color(0xFF4CD9C0), upload = Color(0xFFFFB86B),
)

private val LightHearth = HearthColors(
    good = Color(0xFF15803D), warn = Color(0xFFB45309), bad = Color(0xFFC62828),
    cpu = Color(0xFFC4561A), memory = Color(0xFF3B5BDB), storage = Color(0xFF7048C8),
    download = Color(0xFF0F766E), upload = Color(0xFFB45309),
)

private val HearthTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

private val HearthShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The app's color scheme for [settings] in light or [dark] mode. Not composable, so home screen widgets can use it too. */
fun hearthColorScheme(context: Context, settings: ThemeSettings, dark: Boolean): ColorScheme {
    var scheme = if (settings.palette == Palette.Wallpaper && Palette.dynamicSupported) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkBase.withAccent(settings.palette.dark) else LightBase.withAccent(settings.palette.light)
    }
    if (dark && settings.pureBlack) scheme = scheme.pureBlack()
    return scheme
}

/** The chart colors that go with [scheme]. The CPU series follows the accent so the CPU gauge matches the chosen theme. */
fun hearthColors(scheme: ColorScheme, dark: Boolean): HearthColors =
    (if (dark) DarkHearth else LightHearth).copy(cpu = scheme.primary)

@Composable
fun HearthTheme(settings: ThemeSettings = ThemeSettings(), content: @Composable () -> Unit) {
    val dark = settings.isDark()
    val scheme = hearthColorScheme(LocalContext.current, settings, dark)
    val hearth = hearthColors(scheme, dark)

    CompositionLocalProvider(LocalHearthColors provides hearth) {
        MaterialTheme(colorScheme = scheme, typography = HearthTypography, shapes = HearthShapes, content = content)
    }
}
