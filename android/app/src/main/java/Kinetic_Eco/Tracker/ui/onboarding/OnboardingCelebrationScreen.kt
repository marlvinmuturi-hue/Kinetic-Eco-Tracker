package Kinetic_Eco.Tracker.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val CelebrationGreen = Color(0xFF2E7D32)
private val CelebrationTeal  = Color(0xFF00695C)

/**
 * Full-screen celebration shown once after the user completes onboarding.
 * Displays for [DISPLAY_MS] milliseconds then calls [onFinished] to let
 * the caller navigate to the main app.
 *
 * Visual: expanding concentric pulse rings on a brand gradient, a
 * scale-in headline, and a brief subtitle — no Lottie dependency required.
 */
@Composable
fun OnboardingCelebrationScreen(onFinished: () -> Unit) {
    // Auto-advance
    LaunchedEffect(Unit) {
        delay(DISPLAY_MS)
        onFinished()
    }

    // Pulsing rings — two offsets so they feel staggered
    val infinite = rememberInfiniteTransition(label = "celebration")
    val ring1 by infinite.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1"
    )
    val ring2 by infinite.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1_400, delayMillis = 500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring2"
    )

    // Scale-in for the text block
    val textScale = remember { Animatable(0.7f) }
    LaunchedEffect(Unit) {
        textScale.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(CelebrationGreen, CelebrationTeal)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Pulse rings drawn behind the text
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = size.minDimension * 0.75f

            fun drawRing(t: Float) {
                val r     = maxR * t
                val alpha = (1f - t).coerceIn(0f, 1f) * 0.30f
                drawCircle(
                    color  = Color.White.copy(alpha = alpha),
                    radius = r,
                    center = androidx.compose.ui.geometry.Offset(cx, cy),
                    style  = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                )
            }
            drawRing(ring1)
            drawRing(ring2)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier
                .scale(textScale.value)
                .padding(horizontal = 40.dp)
        ) {
            Text(
                text       = "✓",
                style      = MaterialTheme.typography.displayLarge,
                color      = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text       = "You're all set.",
                style      = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
                color      = Color.White
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text      = "Your journey starts now.",
                style     = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color     = Color.White.copy(alpha = 0.80f)
            )
        }
    }
}

private const val DISPLAY_MS = 1_800L