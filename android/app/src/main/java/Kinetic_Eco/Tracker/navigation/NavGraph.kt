package Kinetic_Eco.Tracker.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.auth.FirebaseUser
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.services.StepMonitor
import Kinetic_Eco.Tracker.ui.screens.*
import Kinetic_Eco.Tracker.ui.utils.toEnergyUnit
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import Kinetic_Eco.Tracker.viewmodel.AuthViewModel
import Kinetic_Eco.Tracker.viewmodel.ProfileViewModel
import Kinetic_Eco.Tracker.viewmodel.TrackerViewModel
import Kinetic_Eco.Tracker.services.UserPhysicalProfile

private const val IN_APP_AUTO_START_STEP_THRESHOLD = 15

private val fadeNavSpec = tween<Float>(durationMillis = 260)

private fun NavGraphBuilder.fadeComposable(
    route: String,
    content: @Composable AnimatedVisibilityScope.(NavBackStackEntry) -> Unit
) {
    composable(
        route = route,
        enterTransition = { fadeIn(animationSpec = fadeNavSpec) },
        exitTransition = { fadeOut(animationSpec = fadeNavSpec) },
        popEnterTransition = { fadeIn(animationSpec = fadeNavSpec) },
        popExitTransition = { fadeOut(animationSpec = fadeNavSpec) },
        content = content
    )
}

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Tracker : Screen("tracker")
    object Analytics : Screen("analytics")
    object Profile : Screen("profile")
    object Settings : Screen("settings")
    object Feedback : Screen("feedback")
    object SessionsList : Screen("sessions_list")
    object SessionDetail : Screen("session_detail")
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    authViewModel: AuthViewModel,
    trackerViewModel: TrackerViewModel,
    analyticsViewModel: AnalyticsViewModel,
    profileViewModel: ProfileViewModel,
    currentUser: FirebaseUser?,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    distanceAlertsEnabled: Boolean = false,
    onDistanceAlertsChange: (Boolean) -> Unit = {},
    notificationSoundsEnabled: Boolean = true,
    onNotificationSoundsChange: (Boolean) -> Unit = {},
    autoStartOnWalkEnabled: Boolean = false,
    onAutoStartOnWalkChange: (Boolean) -> Unit = {},
    idleStopMinutes: Int = 10,
    onIdleStopMinutesChange: (Int) -> Unit = {},
    currentLocale: String = "auto",
    onLocaleChange: (String) -> Unit = {},
    currentThemeMode: String = "system",
    onThemeChange: (String) -> Unit = {},
    launchGoogleSignIn: () -> Unit,
    onShowActivitySelector: () -> Unit,
    startDestination: String,
    physicalProfile: UserPhysicalProfile?,
    onPhysicalProfileSave: (UserPhysicalProfile) -> Unit
) {
    val context = LocalContext.current
    val isTracking by trackerViewModel.isTracking.collectAsStateWithLifecycle()

    // App-wide auto-start: step-based detection works on any screen when logged in and auto-start enabled
    LaunchedEffect(currentUser, autoStartOnWalkEnabled, isTracking) {
        if (currentUser != null && !isTracking && autoStartOnWalkEnabled) {
            val stepMonitor = StepMonitor(context)
            if (stepMonitor.hasStepCounter()) {
                stepMonitor.getStepCountFlow().collect { steps ->
                    if (steps >= IN_APP_AUTO_START_STEP_THRESHOLD) {
                        trackerViewModel.startTracking()
                        return@collect
                    }
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        fadeComposable(Screen.Login.route) {
            LoginScreen(
                viewModel = authViewModel,
                onSignInSuccess = {
                    navController.navigate(Screen.Tracker.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onGoogleSignInClick = {},
                launchGoogleSignIn = launchGoogleSignIn
            )
        }
        
        fadeComposable(Screen.Tracker.route) {
            TrackerScreenWithFAB(
                viewModel = trackerViewModel,
                unitSystem = unitSystem,
                energyUnit = unitSystem.toEnergyUnit(),
                onActivitySelectorClick = onShowActivitySelector,
                userId = currentUser?.uid ?: "",
                autoStartOnWalkEnabled = autoStartOnWalkEnabled,
                onSessionSaved = { analyticsViewModel.requestExpandSessionSummary() }
            )
        }
        
        fadeComposable(Screen.Analytics.route) {
            val currentStats = trackerViewModel.getSessionStats()
            AnalyticsScreen(
                viewModel = analyticsViewModel,
                userId = currentUser?.uid ?: "",
                currentSessionStats = currentStats,
                unitSystem = unitSystem,
                energyUnit = unitSystem.toEnergyUnit(),
                onNavigateToFeedback = {
                    navController.navigate(Screen.Feedback.route)
                }
            )
        }
        
        fadeComposable(Screen.Profile.route) {
            ProfileScreen(
                user = currentUser,
                viewModel = analyticsViewModel,
                profileViewModel = profileViewModel,
                unitSystem = unitSystem,
                onLogout = {
                    authViewModel.signOut()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onSessionsClick = {
                    navController.navigate(Screen.SessionsList.route)
                }
            )
        }
        
        fadeComposable(Screen.SessionsList.route) {
            val _allSessions by analyticsViewModel.getAllSessions(currentUser?.uid ?: "").collectAsStateWithLifecycle(initialValue = emptyList())
            SessionsListScreen(
                viewModel = analyticsViewModel,
                userId = currentUser?.uid ?: "",
                unitSystem = unitSystem,
                energyUnit = unitSystem.toEnergyUnit(),
                onBack = {
                    navController.popBackStack()
                },
                onSessionClick = { session ->
                    // Store the selected session ID in the view model for detail screen
                    analyticsViewModel.setSelectedSession(session)
                    navController.navigate(Screen.SessionDetail.route)
                }
            )
        }
        
        fadeComposable(Screen.SessionDetail.route) {
            val allSessions by analyticsViewModel.getAllSessions(currentUser?.uid ?: "").collectAsStateWithLifecycle(initialValue = emptyList())
            val selectedSession = analyticsViewModel.selectedSession
            SessionDetailScreen(
                session = selectedSession,
                allSessions = allSessions,
                unitSystem = unitSystem,
                energyUnit = unitSystem.toEnergyUnit(),
                onBack = {
                    analyticsViewModel.clearSelectedSession()
                    navController.popBackStack()
                }
            )
        }
        
        fadeComposable(Screen.Settings.route) {
            val leaderboardOptedIn by profileViewModel.leaderboardOptedIn.collectAsStateWithLifecycle()
            val leaderboardLoading by profileViewModel.leaderboardLoading.collectAsStateWithLifecycle()
            val leaderboardError by profileViewModel.errorMessage.collectAsStateWithLifecycle()
            LaunchedEffect(currentUser) {
                currentUser?.uid?.let { profileViewModel.loadLeaderboardOptIn(it) }
            }
            SettingsScreen(
                unitSystem = unitSystem,
                onUnitSystemChange = onUnitSystemChange,
                currentLocale = currentLocale,
                onLocaleChange = onLocaleChange,
                currentThemeMode = currentThemeMode,
                onThemeChange = onThemeChange,
                distanceAlertsEnabled = distanceAlertsEnabled,
                onDistanceAlertsChange = onDistanceAlertsChange,
                notificationSoundsEnabled = notificationSoundsEnabled,
                onNotificationSoundsChange = onNotificationSoundsChange,
                autoStartOnWalkEnabled = autoStartOnWalkEnabled,
                onAutoStartOnWalkChange = onAutoStartOnWalkChange,
                idleStopMinutes = idleStopMinutes,
                onIdleStopMinutesChange = onIdleStopMinutesChange,
                onLogout = {
                    authViewModel.signOut()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                appVersion = "V1.0.0",
                physicalProfile = physicalProfile,
                onPhysicalProfileSave = onPhysicalProfileSave,
                userId = currentUser?.uid ?: "",
                onSyncSessions = {
                    val r = trackerViewModel.syncSessionsToFirestore()
                    currentUser?.uid?.let { profileViewModel.refreshLeaderboardIfOptedIn(it) }
                    r
                },
                leaderboardOptIn = leaderboardOptedIn ?: false,
                onLeaderboardOptInChange = { enabled ->
                    currentUser?.uid?.let { profileViewModel.setLeaderboardOptIn(it, enabled) }
                },
                leaderboardLoading = leaderboardLoading,
                leaderboardError = leaderboardError,
                onLeaderboardErrorDismiss = { profileViewModel.clearError() }
            )
        }
        
        fadeComposable(Screen.Feedback.route) {
            FeedbackScreen(
                onBack = {
                    navController.popBackStack()
                },
                userId = currentUser?.uid ?: "",
                userEmail = currentUser?.email ?: ""
            )
        }
    }
}

