package Kinetic_Eco.Tracker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.navigation.Screen
import Kinetic_Eco.Tracker.ui.theme.*

@Composable
fun BottomNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
    onInteraction: () -> Unit = {}
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        NavigationBar(
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, bottom = 24.dp)
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)),
            containerColor = Slate800
        ) {
        NavigationBarItem(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_tracker),
                    contentDescription = stringResource(R.string.nav_tracker),
                    modifier = Modifier.size(36.dp)
                )
            },
            label = {},
            selected = currentRoute == Screen.Tracker.route,
            onClick = { onInteraction(); onNavigate(Screen.Tracker.route) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Indigo500,
                selectedTextColor = Indigo500,
                unselectedIconColor = Slate400,
                unselectedTextColor = Slate400
            )
        )
        NavigationBarItem(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_analytics),
                    contentDescription = stringResource(R.string.nav_analytics),
                    modifier = Modifier.size(36.dp)
                )
            },
            label = {},
            selected = currentRoute == Screen.Analytics.route,
            onClick = { onInteraction(); onNavigate(Screen.Analytics.route) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Indigo500,
                selectedTextColor = Indigo500,
                unselectedIconColor = Slate400,
                unselectedTextColor = Slate400
            )
        )
        NavigationBarItem(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_profile),
                    contentDescription = stringResource(R.string.nav_profile),
                    modifier = Modifier.size(36.dp)
                )
            },
            label = {},
            selected = currentRoute == Screen.Profile.route,
            onClick = { onInteraction(); onNavigate(Screen.Profile.route) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Indigo500,
                selectedTextColor = Indigo500,
                unselectedIconColor = Slate400,
                unselectedTextColor = Slate400
            )
        )
        NavigationBarItem(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.nav_settings),
                    modifier = Modifier.size(36.dp)
                )
            },
            label = {},
            selected = currentRoute == Screen.Settings.route,
            onClick = { onInteraction(); onNavigate(Screen.Settings.route) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Indigo500,
                selectedTextColor = Indigo500,
                unselectedIconColor = Slate400,
                unselectedTextColor = Slate400
            )
        )
    }
    }
}
