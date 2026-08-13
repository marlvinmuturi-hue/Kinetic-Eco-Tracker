package Kinetic_Eco.Tracker.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild

// ─────────────────────────────────────────────────────────────────────────────
// Frosted-glass design system — active in both dark and light theme.
//
// A fixed gradient (mirrors the web app's index.html background — dark teal→navy,
// or a bright sky-blue in light theme) is painted behind Dashboard/Profile/
// Settings/Analysis via [kineticGradientBackground], and every tile on top of it
// uses [glassTile] to blur/tint whatever sits behind it via the Haze library
// (pinned to 0.7.3 — see build.gradle.kts comment for why). Haze automatically
// falls back to drawing [HazeStyle.tint] as a flat scrim (no blur) on API < 31.
//
// Both [kineticGradientBackground] and [glassTile] read the current theme
// (light vs dark) internally via `MaterialTheme.colorScheme.background.luminance()`
// so call sites never need to branch on theme themselves — just call them plainly.
// ─────────────────────────────────────────────────────────────────────────────

/** Light frosted tile (dark theme) — regular content cards/tiles. */
private val KineticGlassStyleDark = HazeStyle(
    tint = Color.White.copy(alpha = 0.10f),
    blurRadius = 20.dp,
    noiseFactor = 0.15f,
)

/** Frosted tile tuned for a bright backdrop — more opaque so tiles read as distinct panels. */
private val KineticGlassStyleLight = HazeStyle(
    tint = Color.White.copy(alpha = 0.45f),
    blurRadius = 20.dp,
    noiseFactor = 0.08f,
)

/** Hairline border — light-on-dark in dark theme, dark-on-light in light theme. */
private val KineticGlassBorderDark = Color.White.copy(alpha = 0.12f)
private val KineticGlassBorderLight = Color.Black.copy(alpha = 0.08f)

/**
 * Backdrop painted behind the frosted-glass screens: dark teal→navy in dark theme,
 * bright sky-blue in light theme (mirrors the CSS gradient in the web app's
 * index.html). Kept as one shared modifier so every screen matches. Apply to the
 * outermost Box, alongside `Modifier.haze(state)`.
 */
@Composable
fun Modifier.kineticGradientBackground(): Modifier {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return this.drawBehind {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@drawBehind

        if (isDark) {
            // Base diagonal gradient: teal-slate top → near-black bottom.
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF17303F),
                        Color(0xFF102838),
                        Color(0xFF0D2233),
                        Color(0xFF0A1A2B),
                        Color(0xFF060F1C),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.4f, h),
                ),
            )
            // Soft teal glow, upper-left.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF2D758F).copy(alpha = 0.55f), Color(0xFF2D758F).copy(alpha = 0f)),
                    center = Offset(w * 0.12f, 0f),
                    radius = (w * 0.7f).coerceAtLeast(1f),
                ),
            )
            // Soft blue glow, upper-right.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF3B5BDB).copy(alpha = 0.35f), Color(0xFF3B5BDB).copy(alpha = 0f)),
                    center = Offset(w * 0.88f, h * 0.08f),
                    radius = (w * 0.6f).coerceAtLeast(1f),
                ),
            )
            // Soft deep glow, lower-right.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF1B3A52).copy(alpha = 0.6f), Color(0xFF1B3A52).copy(alpha = 0f)),
                    center = Offset(w * 0.9f, h),
                    radius = (w * 0.75f).coerceAtLeast(1f),
                ),
            )
        } else {
            // Base diagonal gradient: bright sky-blue top → soft near-white bottom.
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF6EC1E8),
                        Color(0xFF8ED2EE),
                        Color(0xFFBEE3F4),
                        Color(0xFFE3F3FA),
                        Color(0xFFF6FBFE),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.4f, h),
                ),
            )
            // Warm "sun" glow, upper-right — mirrors the weather-app reference photo.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFFFF3C4).copy(alpha = 0.65f), Color(0xFFFFF3C4).copy(alpha = 0f)),
                    center = Offset(w * 0.85f, h * 0.05f),
                    radius = (w * 0.7f).coerceAtLeast(1f),
                ),
            )
            // Soft cyan glow, upper-left.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF4FB3D9).copy(alpha = 0.35f), Color(0xFF4FB3D9).copy(alpha = 0f)),
                    center = Offset(w * 0.1f, h * 0.15f),
                    radius = (w * 0.65f).coerceAtLeast(1f),
                ),
            )
            // Soft pale glow, lower area, fading toward white.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)),
                    center = Offset(w * 0.5f, h),
                    radius = (w * 0.9f).coerceAtLeast(1f),
                ),
            )
        }
    }
}

/**
 * Applies the frosted-glass treatment to a tile: clips to [shape], blurs/tints
 * whatever [hazeState] has captured behind it, and draws the shared hairline
 * border — both tuned automatically for the current theme. Pair with
 * `Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent))`
 * (or any container whose own background you've set to transparent) so the blur
 * shows through.
 */
@Composable
fun Modifier.glassTile(
    hazeState: HazeState,
    shape: Shape,
): Modifier {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val style = if (isDark) KineticGlassStyleDark else KineticGlassStyleLight
    val borderColor = if (isDark) KineticGlassBorderDark else KineticGlassBorderLight
    return this
        .clip(shape)
        .hazeChild(state = hazeState, shape = shape, style = style)
        .border(BorderStroke(1.dp, borderColor), shape)
}
