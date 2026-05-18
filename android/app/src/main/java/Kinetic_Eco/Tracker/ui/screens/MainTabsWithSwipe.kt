package Kinetic_Eco.Tracker.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.distinctUntilChanged
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.navigation.Screen
import Kinetic_Eco.Tracker.ui.utils.toEnergyUnit
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import Kinetic_Eco.Tracker.viewmodel.AuthViewModel
import Kinetic_Eco.Tracker.viewmodel.ProfileViewModel
import Kinetic_Eco.Tracker.viewmodel.TrackerViewModel

/**
 * Swipeable horizontal pager for the three primary tabs:
 *   0 = Dashboard, 1 = Tracker (live GPS), 2 = Analysis
 *
 * The Settings + Profile screens are now reached via the floating Settings icon
 * embedded in Dashboard / Analysis (which navigates to [Screen.Settings]).
 *
 * Layout notes:
 *   • The pager itself uses [Modifier.clipToBounds] so adjacent pages (which
 *     are composed eagerly via [PageSize.Fill] + the default off-screen
 *     pre-composition) cannot draw outside the visible viewport.
 *   • Each page slot is also a [Box] that fills the page, paints the theme
 *     background, and clips its own content. This is critical because the
 *     Tracker page draws a full-bleed animated gradient (DynamicTrackerBackground);
 *     without an opaque, clipped wrapper its pixels can leak onto the
 *     Dashboard / Analysis pages on either side during layout / fast swipes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainTabsWithSwipe(
    selectedMainTabIndex: Int,
    onSelectedMainTabIndexChange: (Int) -> Unit,
    navController: NavHostController,
    authViewModel: AuthViewModel,
    trackerViewModel: TrackerViewModel,
    analyticsViewModel: AnalyticsViewModel,
    profileViewModel: ProfileViewModel,
    currentUser: FirebaseUser?,
    unitSystem: UnitSystem,
    autoStartOnWalkEnabled: Boolean,
    onShowActivitySelector: () -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = selectedMainTabIndex.coerceIn(0, 2),
        pageCount = { 3 }
    )

    LaunchedEffect(selectedMainTabIndex) {
        val target = selectedMainTabIndex.coerceIn(0, 2)
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page != selectedMainTabIndex) {
                    onSelectedMainTabIndexChange(page)
                }
            }
    }

    val pageBackground = MaterialTheme.colorScheme.background

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        pageSize = PageSize.Fill,
        pageSpacing = 0.dp,
        beyondBoundsPageCount = 1
    ) { page ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .background(pageBackground)
        ) {
            when (page) {
                0 -> DashboardScreen(
                    user = currentUser,
                    analyticsViewModel = analyticsViewModel,
                    profileViewModel = profileViewModel,
                    unitSystem = unitSystem,
                    onSettingsClick = { navController.navigate(Screen.Settings.route) },
                    onProfileClick = { navController.navigate(Screen.Profile.route) },
                    onSessionsClick = { navController.navigate(Screen.SessionsList.route) },
                    onAnalysisClick = { onSelectedMainTabIndexChange(2) },
                    onSessionClick = { session ->
                        analyticsViewModel.setSelectedSession(session)
                        navController.navigate(Screen.SessionDetail.route)
                    }
                )
                1 -> TrackerScreenWithFAB(
                    viewModel = trackerViewModel,
                    unitSystem = unitSystem,
                    energyUnit = unitSystem.toEnergyUnit(),
                    onActivitySelectorClick = onShowActivitySelector,
                    userId = currentUser?.uid ?: "",
                    autoStartOnWalkEnabled = autoStartOnWalkEnabled,
                    onSessionSaved = { analyticsViewModel.requestExpandSessionSummary() }
                )
                2 -> AnalysisScreen(
                    analyticsViewModel = analyticsViewModel,
                    userId = currentUser?.uid ?: "",
                    unitSystem = unitSystem,
                    onSettingsClick = { navController.navigate(Screen.Settings.route) },
                    onSessionClick = { session ->
                        analyticsViewModel.setSelectedSession(session)
                        navController.navigate(Screen.SessionDetail.route)
                    }
                )
                else -> Unit
            }
        }
    }
}
