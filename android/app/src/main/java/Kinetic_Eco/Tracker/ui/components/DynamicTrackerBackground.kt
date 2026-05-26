package Kinetic_Eco.Tracker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import Kinetic_Eco.Tracker.data.ActivityType
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/**
 * Code-driven animated background for the Tracker tab: dark time-of-day base
 * with a subtle activity-colour tint that cross-fades on activity changes.
 */
@Composable
fun DynamicTrackerBackground(
    currentActivity: ActivityType,
    modifier: Modifier = Modifier,
    hourOverride: Int? = null
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val hour = hourOverride
        ?: remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }

    val (todTop, todBottom) = remember(hour, isLight) {
        if (isLight) lightTimeOfDayPalette(hour) else monochromeTimeOfDayPalette(hour)
    }

    val activityAccent by animateColorAsState(
        targetValue = activityAccentColor(currentActivity),
        animationSpec = tween(durationMillis = 900),
        label = "activity_accent"
    )

    val accentTopBlend = if (isLight) 0.10f else 0.20f
    val accentMidBlend = if (isLight) 0.05f else 0.10f
    val blobAlpha     = if (isLight) 0.12f else 0.25f

    val drift = rememberInfiniteTransition(label = "tracker_bg_drift")
    val driftAngle by drift.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tracker_bg_drift_angle"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to lerp(todTop, activityAccent, accentTopBlend),
                        0.55f to lerp(lerp(todTop, todBottom, 0.35f), activityAccent, accentMidBlend),
                        1.0f to todBottom
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f + (w * 0.25f) * cos(driftAngle)
            val cy = h / 2f + (h * 0.18f) * sin(driftAngle)
            val radius = (w.coerceAtLeast(h)) * 0.55f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activityAccent.copy(alpha = blobAlpha),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = radius
                ),
                size = Size(w, h)
            )
        }
    }
}

/** Per-activity accent colour blended into the dark base gradient. */
private fun activityAccentColor(activity: ActivityType): Color = when (activity) {
    ActivityType.IDLE            -> Color(0xFF708090)  // Slate grey
    ActivityType.WALKING         -> Color(0xFF4CAF80)  // Forest green
    ActivityType.RUNNING         -> Color(0xFFFF7043)  // Warm orange
    ActivityType.CYCLING         -> Color(0xFF29B6F6)  // Sky blue
    ActivityType.MOTORCYCLE      -> Color(0xFFAB47BC)  // Deep purple
    ActivityType.TRAIN           -> Color(0xFF5C8EE8)  // Steel blue
    ActivityType.DRIVING         -> Color(0xFFEF5350)  // Red
    ActivityType.ELECTRIC_VEHICLE-> Color(0xFF26C6DA)  // Cyan/teal
    ActivityType.FLYING          -> Color(0xFF90CAF9)  // Light blue
}

/** Subtle dark gray shifts by hour — base palette before activity tint. */
private fun monochromeTimeOfDayPalette(hour: Int): Pair<Color, Color> = when (hour) {
    in 5..7   -> Color(0xFF1A1F26) to Color(0xFF2A3038)
    in 8..11  -> Color(0xFF222830) to Color(0xFF151820)
    in 12..15 -> Color(0xFF252A32) to Color(0xFF12151A)
    in 16..18 -> Color(0xFF201C22) to Color(0xFF0E1014)
    in 19..21 -> Color(0xFF18161C) to Color(0xFF0A0B0E)
    else      -> Color(0xFF12141A) to Color(0xFF050608)
}

/** Airy light palette for light mode — soft tints that shift by time of day. */
private fun lightTimeOfDayPalette(hour: Int): Pair<Color, Color> = when (hour) {
    in 5..7   -> Color(0xFFE8F4FD) to Color(0xFFD6EAF8)  // dawn — soft blue
    in 8..11  -> Color(0xFFF0F8FF) to Color(0xFFE8F4FD)  // morning — bright airy
    in 12..15 -> Color(0xFFF5FBFF) to Color(0xFFEDF6FF)  // midday — nearly white
    in 16..18 -> Color(0xFFFFF8F0) to Color(0xFFFFF0E0)  // afternoon — warm
    in 19..21 -> Color(0xFFF5F0FF) to Color(0xFFEDE0FF)  // dusk — soft lavender
    else      -> Color(0xFFEEF0F8) to Color(0xFFE4E8F5)  // night — cool blue-gray
}
