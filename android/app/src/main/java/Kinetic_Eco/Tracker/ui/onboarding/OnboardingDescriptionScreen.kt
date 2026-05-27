package Kinetic_Eco.Tracker.ui.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class BenefitCard(
    val icon: ImageVector,
    val headline: String,
    val supporting: String
)

private val benefitCards = listOf(
    BenefitCard(
        icon      = Icons.Default.LocationOn,
        headline  = "Every route recorded.",
        supporting = "Distance, speed, altitude, and path — your full journey, not just a number."
    ),
    BenefitCard(
        icon      = Icons.AutoMirrored.Filled.DirectionsBike,
        headline  = "It knows the difference.",
        supporting = "Walk, cycle, drive, fly — 7 activity types detected automatically. No labels needed."
    ),
    BenefitCard(
        icon      = Icons.Default.Eco,
        headline  = "Your carbon footprint, honestly.",
        supporting = "Every km driven, every kg saved — tracked against real global CO₂ averages."
    ),
    BenefitCard(
        icon      = Icons.Default.BarChart,
        headline  = "Stay motivated.",
        supporting = "Weekly summaries, a personal leaderboard, and milestones that celebrate real progress."
    )
)

/**
 * Step 1 of 3 (post-login) — benefit tour.
 *
 * Four swipeable full-card panels replace the old feature-list card.
 * Each panel surfaces one outcome (not one feature), so users absorb the
 * value of the app before they've configured anything.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingDescriptionScreen(onContinue: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val pagerState  = rememberPagerState(pageCount = { benefitCards.size })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))

        Text(
            text = "Here's what you can do",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onBackground
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Swipe to explore",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        HorizontalPager(
            state    = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            val card = benefitCards[page]
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
                shape  = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement   = Arrangement.Center,
                    horizontalAlignment   = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector      = card.icon,
                            contentDescription = null,
                            tint             = colorScheme.onPrimaryContainer,
                            modifier         = Modifier.size(40.dp)
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    Text(
                        text      = card.headline,
                        style     = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color     = colorScheme.onSurface
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text      = card.supporting,
                        style     = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color     = colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Page-position dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            repeat(benefitCards.size) { index ->
                val selected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .size(if (selected) 10.dp else 7.dp)
                        .background(
                            color  = if (selected) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.25f),
                            shape  = CircleShape
                        )
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        OnboardingStepIndicator(currentStep = 0, totalSteps = 3)

        Spacer(Modifier.height(20.dp))

        Button(
            onClick  = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Text("Continue", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(32.dp))
    }
}