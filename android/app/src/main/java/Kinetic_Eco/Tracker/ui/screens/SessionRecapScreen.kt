package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import Kinetic_Eco.Tracker.data.ActivityType
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.services.Co2EquivalencyService
import Kinetic_Eco.Tracker.ui.utils.ShareCardRenderer
import Kinetic_Eco.Tracker.ui.utils.ShareUtils

/**
 * PROTOTYPE — celebratory post-session recap. Renders the shareable impact card once and shows it as a
 * live preview, so "what you see is what you share". Designed to be shown right after a session saves
 * (from the tracker flow) or opened from a saved session's detail screen.
 */
@Composable
fun SessionRecapScreen(
    stats: SessionStats,
    unitSystem: UnitSystem,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    val equivalency = remember(stats.co2Conserved) {
        if (stats.co2Conserved < 0.05) null
        else Co2EquivalencyService.getNetImpactEquivalencies(stats.co2Conserved, limit = 1)
            .firstOrNull()
            ?.let { "${it.icon} ${it.description.removePrefix("Equivalent to the CO₂ of ")}" }
    }

    // Render the share card once; the preview and the shared image are the same bitmap.
    val cardBitmap = remember(stats) { ShareCardRenderer.render(stats, unitSystem, equivalency) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            Text(
                text = celebratoryTitle(stats),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Here's your trip — share your impact 🌿",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Image(
                bitmap = cardBitmap.asImageBitmap(),
                contentDescription = "Session impact card",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Fit
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { ShareUtils.shareImage(context, cardBitmap, ShareUtils.buildSessionShareText(stats, unitSystem)) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share", fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

/** A short, encouraging headline based on the session's dominant activity (never guilt-based). */
private fun celebratoryTitle(stats: SessionStats): String {
    val main = stats.breakdown.entries
        .filter { it.key != ActivityType.IDLE }
        .maxByOrNull { it.value.distance }?.key
    return when (main) {
        ActivityType.WALKING -> "Nice walk! 🚶"
        ActivityType.RUNNING -> "Great run! 🏃"
        ActivityType.CYCLING -> "Sweet ride! 🚴"
        ActivityType.DRIVING, ActivityType.ELECTRIC_VEHICLE, ActivityType.MOTORCYCLE -> "Trip logged! 🚗"
        ActivityType.TRAIN -> "All aboard! 🚆"
        ActivityType.FLYING -> "Safe travels! ✈️"
        else -> "Trip complete! 🌿"
    }
}