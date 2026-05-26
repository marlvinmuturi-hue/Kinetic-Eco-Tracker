package Kinetic_Eco.Tracker.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ─────────────────────────────────────────────────────────────────────────────
// Two-variant palette — Dark and Light only.
//
// Dark : near-black background (#0A0A0A) + dark-gray cards (#1F1F1F)
// Light: pure white background (#FFFFFF) + very light gray cards (#F5F5F5)
//
// A single fixed accent (Emerald500, #10B981) drives buttons / FAB / selected
// chips / progress indicators in both modes — there is no per-user accent
// picker. Material You / dynamic color is intentionally NOT used.
// ─────────────────────────────────────────────────────────────────────────────

/** Near-black page background for dark mode. */
private val KineticDarkBackground = Color(0xFF0A0A0A)
/** Dark-gray "card" / surface tone for dark mode — text content lives in here. */
private val KineticDarkSurface = Color(0xFF1F1F1F)
/** Slightly lighter elevated surface for input fields, dialogs, bottom sheets. */
private val KineticDarkSurfaceVariant = Color(0xFF2A2A2A)

/** Pure white page background for light mode. */
private val KineticLightBackground = Color(0xFFFFFFFF)
/** Pure white card / surface tone for light mode — cards rely on borders/elevation, not color, for distinction. */
private val KineticLightSurface = Color(0xFFFFFFFF)
/** Very light gray elevated surface for input fields, dialogs, bottom sheets. */
private val KineticLightSurfaceVariant = Color(0xFFF2F2F2)

/** Single fixed primary used in both modes (also defined in Color.kt as Emerald500). */
private val KineticPrimary = Color(0xFF10B981)

internal val KineticDarkColorScheme = darkColorScheme(
    primary = KineticPrimary,
    secondary = KineticPrimary,
    tertiary = KineticPrimary,
    background = KineticDarkBackground,
    surface = KineticDarkSurface,
    surfaceVariant = KineticDarkSurfaceVariant,
    error = Red500,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFFB8B8B8),
    onError = Color.White,
    outline = Color(0xFF3A3A3A)
)

internal val KineticLightColorScheme = lightColorScheme(
    primary = KineticPrimary,
    secondary = KineticPrimary,
    tertiary = KineticPrimary,
    background = KineticLightBackground,
    surface = KineticLightSurface,
    surfaceVariant = KineticLightSurfaceVariant,
    error = Red500,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF111111),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF555555),
    onError = Color.White,
    outline = Color(0xFFC4C4C4)
)

/**
 * App theme. The only knob is [darkTheme] — there is no accent / dynamic
 * color customization any more. Callers in production set [darkTheme] from
 * the user's saved preference (light/dark only); previews can let it fall
 * through to the device setting via [isSystemInDarkTheme].
 */
@Composable
fun KineticEcoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) KineticDarkColorScheme else KineticLightColorScheme

    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = context.findActivity() ?: return@SideEffect
            val window = activity.window
            val bg = colorScheme.background.toArgb()
            window.statusBarColor = bg
            window.navigationBarColor = bg
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
