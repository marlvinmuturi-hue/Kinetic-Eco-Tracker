package Kinetic_Eco.Tracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import Kinetic_Eco.Tracker.ui.theme.glassTile

// hazeState defaults to null everywhere in this file: screens that don't paint
// the gradient backdrop (Analytics, SessionDetail, Tracker) get the original
// flat containerColor unchanged. Only Dashboard/Profile/Settings pass a real
// HazeState, which is what switches these cards to the frosted-glass look.

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    emphasize: Boolean = false,
    hazeState: HazeState? = null
) {
    val shape = CardDefaults.shape
    Card(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (hazeState != null) Modifier.glassTile(hazeState, shape) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (hazeState != null) Color.Transparent else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(if (emphasize) 20.dp else 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = if (emphasize) 12.dp else 8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(if (emphasize) 28.dp else 20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SingleLineValueText(
                text = value,
                style = if (emphasize) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    iconPainter: Painter,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    emphasize: Boolean = false,
    hazeState: HazeState? = null
) {
    val shape = CardDefaults.shape
    Card(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (hazeState != null) Modifier.glassTile(hazeState, shape) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (hazeState != null) Color.Transparent else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(if (emphasize) 20.dp else 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = if (emphasize) 12.dp else 8.dp)
            ) {
                Icon(
                    painter = iconPainter,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(if (emphasize) 28.dp else 20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SingleLineValueText(
                text = value,
                style = if (emphasize) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
fun StatCardCompact(
    title: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    hazeState: HazeState? = null
) {
    val shape = CardDefaults.shape
    Card(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (hazeState != null) Modifier.glassTile(hazeState, shape) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (hazeState != null) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                SingleLineValueText(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

@Composable
fun StatCardCompact(
    title: String,
    value: String,
    iconPainter: Painter,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    hazeState: HazeState? = null
) {
    val shape = CardDefaults.shape
    Card(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (hazeState != null) Modifier.glassTile(hazeState, shape) else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (hazeState != null) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = iconPainter,
                contentDescription = title,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                SingleLineValueText(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}


