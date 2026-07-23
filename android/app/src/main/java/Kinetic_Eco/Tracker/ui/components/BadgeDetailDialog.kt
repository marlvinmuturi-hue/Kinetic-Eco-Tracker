package Kinetic_Eco.Tracker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import Kinetic_Eco.Tracker.data.AchievementBadge
import Kinetic_Eco.Tracker.ui.utils.BadgeShareCardRenderer
import Kinetic_Eco.Tracker.ui.utils.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A magnified view of a single earned achievement [badge] that can be shared to social media as a
 * branded image. Used two ways:
 *   - **Tap-to-view**: tapping an earned badge chip on the dashboard opens it ([celebration] = false).
 *   - **Milestone pop-up**: shown automatically the first time a new tier is earned
 *     ([celebration] = true), which adds the "Achievement Unlocked!" header and a bouncier entrance.
 *
 * The Share button renders the badge to a Bitmap on a background thread via [BadgeShareCardRenderer]
 * and hands it to the system share sheet through [ShareUtils.shareImage].
 */
@Composable
fun BadgeDetailDialog(
    badge: AchievementBadge,
    celebration: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val tColor = badgeTierColor(badge.tier)
    val artworkRes = badgeArtworkDrawableRes(badge.id, badge.tier)

    var sharing by remember { mutableStateOf(false) }

    // Entrance animation — a gentle spring; bouncier when celebrating a fresh milestone.
    val scale = remember { Animatable(if (celebration) 0.5f else 0.9f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = if (celebration) Spring.DampingRatioMediumBouncy else Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (celebration) {
                    Text(
                        text = "🎉 Achievement Unlocked!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = tColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // Magnified badge over a soft tier-coloured glow.
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(tColor.copy(alpha = 0.28f), tColor.copy(alpha = 0f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (artworkRes != null) {
                        Image(
                            painter = painterResource(artworkRes),
                            contentDescription = "${badge.tier.label} ${badge.title}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(150.dp)
                        )
                    } else {
                        Text(text = badge.emoji, fontSize = 96.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = badge.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${badge.tier.label} tier",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tColor
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${badgeFmtVal(badge.currentValue, badge.unit)} ${badge.unit} · ${badge.description}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (sharing) return@Button
                        scope.launch {
                            sharing = true
                            try {
                                val bmp = withContext(Dispatchers.Default) {
                                    BadgeShareCardRenderer.render(context, badge)
                                }
                                ShareUtils.shareImage(context, bmp, ShareUtils.buildBadgeShareText(badge))
                            } finally {
                                sharing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = tColor)
                ) {
                    if (sharing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share")
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
        }
    }
}