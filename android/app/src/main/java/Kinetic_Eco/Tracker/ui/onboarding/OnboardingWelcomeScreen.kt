package Kinetic_Eco.Tracker.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.R

/**
 * Pre-login welcome splash. Stripped down to logo + app name + a 1–2 line
 * welcome message + "Get started". The detailed feature list lives in
 * [OnboardingDescriptionScreen], which is shown AFTER the user creates an
 * account or signs in (so authenticated users don't see the marketing tour
 * every cold start).
 *
 * Step indicator is intentionally omitted here — Welcome is the very first
 * thing a user sees on first launch and shouldn't feel like "step 1/N" yet;
 * it should feel like an invitation.
 *
 * The logo image is loaded from `R.drawable.ic_app_logo` (the bundled app
 * icon — green tree with carbon-molecule "leaves" on a teal-to-emerald
 * gradient). The drawable already includes its own rounded-square framing,
 * but we still clip with `MaterialTheme.shapes.extraLarge` so future logo
 * swaps with non-pre-rounded artwork still render correctly.
 */
@Composable
fun OnboardingWelcomeScreen(
    onGetStarted: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

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
                .clip(MaterialTheme.shapes.extraLarge)
        )

        Spacer(Modifier.height(28.dp))

        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = colorScheme.onBackground
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Welcome — track every step, ride, and trip, and see your environmental impact in real time.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Text(
                text = "Get started",
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(Modifier.height(40.dp))
    }
}
