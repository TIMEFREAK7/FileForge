package io.github.timefreak7.fileforge.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand palette (spec: Palette): deep teal/navy, cyan-to-teal accents, emerald, a restrained lime.
private val Navy = Color(0xFF0B2E3D)
private val Teal = Color(0xFF0F766E)
private val Cyan = Color(0xFF0891B2)
private val Emerald = Color(0xFF059669)
private val Lime = Color(0xFFA3E635)

private val Light = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCF2EE),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Cyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F1F9),
    onSecondaryContainer = Color(0xFF001F27),
    tertiary = Emerald,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6F9C4),
    onTertiaryContainer = Color(0xFF1B2B00),
    background = Color(0xFFF4F9FB),
    onBackground = Navy,
    surface = Color(0xFFF4F9FB),
    onSurface = Navy,
    surfaceVariant = Color(0xFFDCE8EC),
    onSurfaceVariant = Color(0xFF3F5560),
    surfaceContainer = Color(0xFFE8F1F4),
    surfaceContainerHigh = Color(0xFFE1ECF0),
    outline = Color(0xFF6F8791),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFCCF2EE),
    secondary = Color(0xFF67E8F9),
    onSecondary = Color(0xFF003641),
    secondaryContainer = Color(0xFF004E5E),
    onSecondaryContainer = Color(0xFFD3F1F9),
    tertiary = Lime,
    onTertiary = Color(0xFF213600),
    tertiaryContainer = Color(0xFF314F00),
    onTertiaryContainer = Color(0xFFE6F9C4),
    background = Color(0xFF07161E),
    onBackground = Color(0xFFE2EEF2),
    surface = Color(0xFF07161E),
    onSurface = Color(0xFFE2EEF2),
    surfaceVariant = Color(0xFF2A3F48),
    onSurfaceVariant = Color(0xFFB7CBD3),
    surfaceContainer = Color(0xFF10232C),
    surfaceContainerHigh = Color(0xFF172C36),
    outline = Color(0xFF82979F),
    error = Color(0xFFF2B8B5),
)

/**
 * Springs for every animation (spec: Motion system). Values match Material 3 Expressive's scheme;
 * shortcut: defined here because MotionScheme is internal in stable material3 1.4.0. Switch to
 * MaterialTheme.motionScheme once material3 1.5 is stable.
 */
object Motion {
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
}

@Composable
fun FileForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
