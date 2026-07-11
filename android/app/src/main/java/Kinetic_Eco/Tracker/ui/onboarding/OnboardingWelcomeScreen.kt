package Kinetic_Eco.Tracker.ui.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.R

private val DeepTeal   = Color(0xFF00695C)
private val Emerald    = Color(0xFF1B5E20)
private val MidGreen   = Color(0xFF2E7D32)

/**
 * Pre-login welcome splash.
 *
 * - Animated background: slow vertical gradient that shifts between deep teal
 *   and emerald, signalling the app is alive from the very first frame.
 * - Logo: subtle pulse (scale 0.96 ↔ 1.04) so the brand mark breathes without
 *   requiring the Lottie dependency.
 * - Copy: impact-first hook replaces the generic feature sentence.
 */
@Composable
fun OnboardingWelcomeScreen(onGetStarted: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "welcome")

    // Gradient shift: teal top ↔ emerald top over 5 s
    val gradientFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(5_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bg_gradient"
    )
    val topColor    = lerp(DeepTeal, MidGreen, gradientFraction)
    val bottomColor = lerp(Emerald,  DeepTeal, gradientFraction)

    // Logo heartbeat
    val logoPulse by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue  = 1.04f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            Image(
                painter = painterResource(id = R.drawable.ic_app_logo),
                contentDescription = stringResource(id = R.string.app_name),
                modifier = Modifier
                    .size(132.dp)
                    .scale(logoPulse)
                    .clip(MaterialTheme.shapes.extraLarge)
            )

            Spacer(Modifier.height(28.dp))

            Text(
                text = stringResource(id = R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "The average person emits about 1 tonne of CO₂ on transport per year.\nTrack your emissions and conservation efforts today.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.88f)
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor   = DeepTeal
                )
            ) {
                Text(
                    text = "Get started",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}