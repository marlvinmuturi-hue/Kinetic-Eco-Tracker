package Kinetic_Eco.Tracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Kinetic_Eco.Tracker.R

/**
 * Floating bottom navigation — Dashboard, Tracker, Analysis with icon + caption and a soft capsule look.
 */
@Composable
fun BottomNavigationBar(
    /** 0–2 when the main swipe pager is visible; -1 when on another screen (e.g. sessions list). */
    selectedMainTabIndex: Int,
    onMainTabSelected: (Int) -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val scheme = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(32.dp)
        val isLightBg = scheme.background.red + scheme.background.green + scheme.background.blue > 2.2f
        val outlineAlpha = if (isLightBg) 0.14f else 0.28f
        NavigationBar(
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 20.dp)
                .shadow(
                    elevation = 18.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = if (isLightBg) 0.07f else 0.14f),
                    spotColor = Color.Black.copy(alpha = if (isLightBg) 0.11f else 0.20f)
                )
                .clip(shape)
                .border(
                    width = 1.dp,
                    color = scheme.outline.copy(alpha = outlineAlpha),
                    shape = shape
                ),
            tonalElevation = 6.dp,
            containerColor = scheme.surface
        ) {
            BottomNavItem(
                icon = Icons.Default.Dashboard,
                contentDescription = stringResource(R.string.nav_dashboard),
                label = stringResource(R.string.nav_dashboard),
                selected = selectedMainTabIndex == 0,
                onClick = { onMainTabSelected(0) }
            )
            BottomNavItem(
                icon = Icons.Default.PlayCircle,
                contentDescription = stringResource(R.string.nav_tracker),
                label = stringResource(R.string.nav_tracker),
                selected = selectedMainTabIndex == 1,
                onClick = { onMainTabSelected(1) }
            )
            BottomNavItem(
                icon = Icons.Default.BarChart,
                contentDescription = stringResource(R.string.nav_analytics),
                label = stringResource(R.string.nav_analytics),
                selected = selectedMainTabIndex == 2,
                onClick = { onMainTabSelected(2) }
            )
        }
    }
}

@Composable
private fun RowScope.BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 400f),
        label = "navIconScale"
    )
    NavigationBarItem(
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(26.dp)
                    .scale(scale)
            )
        },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    letterSpacing = 0.55.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    lineHeight = 12.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        },
        alwaysShowLabel = true,
        selected = selected,
        onClick = onClick,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = scheme.onSurface,
            selectedTextColor = scheme.onSurface,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
            indicatorColor = scheme.surfaceVariant.copy(alpha = 0.65f)
        )
    )
}
