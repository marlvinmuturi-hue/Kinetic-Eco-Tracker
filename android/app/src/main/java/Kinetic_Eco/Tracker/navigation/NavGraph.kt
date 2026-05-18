package Kinetic_Eco.Tracker.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.auth.FirebaseUser
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.data.VehicleProfile
import Kinetic_Eco.Tracker.data.PrimaryFuelType
import Kinetic_Eco.Tracker.services.StepMonitor
import Kinetic_Eco.Tracker.ui.onboarding.OnboardingDescriptionScreen
import Kinetic_Eco.Tracker.ui.onboarding.OnboardingPermissionsScreen
import Kinetic_Eco.Tracker.ui.onboarding.OnboardingProfileScreen
import Kinetic_Eco.Tracker.ui.onboarding.OnboardingWelcomeScreen
import Kinetic_Eco.Tracker.ui.screens.*
import Kinetic_Eco.Tracker.ui.utils.toEnergyUnit
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import Kinetic_Eco.Tracker.viewmodel.AuthViewModel
import Kinetic_Eco.Tracker.viewmodel.ProfileViewModel
import Kinetic_Eco.Tracker.viewmodel.TrackerViewModel
import Kinetic_Eco.Tracker.services.UserPhysicalProfile
import Kinetic_Eco.Tracker.services.UserPreferencesManager

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
    /** Pre-login welcome splash — first thing a user sees on first launch. */
    object OnboardingWelcome : Screen("onboarding_welcome")
    object Terms : Screen("terms")
    object Login : Screen("login")
    /** Post-login onboarding — 3 steps: Description, Permissions, Profile. */
    object OnboardingDescription : Screen("onboarding_description")
    object OnboardingPermissions : Screen("onboarding_permissions")
    object OnboardingProfile : Screen("onboarding_profile")
    /** Primary app destination: horizontal swipe between tracker / analytics / profile / settings. */
    object MainTabs : Screen("main_tabs")
    /** Tab ids for bottom bar (not separate NavHost routes). */
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
    notificationSoundsEnabled: Boolean = true,
    onNotificationSoundsChange: (Boolean) -> Unit = {},
    autoStartOnWalkEnabled: Boolean = false,
    onAutoStartOnWalkChange: (Boolean) -> Unit = {},
    idleStopMinutes: Int = 10,
    onIdleStopMinutesChange: (Int) -> Unit = {},
    currentLocale: String = "auto",
    onLocaleChange: (String) -> Unit = {},
    currentThemeMode: String = "dark",
    onThemeChange: (String) -> Unit = {},
    onGoPremium: () -> Unit = {},
    weeklyDigestEnabled: Boolean = true,
    onWeeklyDigestChange: (Boolean) -> Unit = {},
    launchGoogleSignIn: () -> Unit,
    onShowActivitySelector: () -> Unit,
    startDestination: String,
    onPhysicalProfileSave: (UserPhysicalProfile) -> Unit,
    onVehicleProfileSave: (VehicleProfile) -> Unit = {},
    userPrefsManager: UserPreferencesManager,
    selectedMainTabIndex: Int,
    onSelectedMainTabIndexChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val isTracking by trackerViewModel.isTracking.collectAsStateWithLifecycle()

    // App-wide auto-start: steps since this flow subscribed (not lifetime steps). Use uid in keys so
    // FirebaseUser reference changes do not restart the collector and reset the baseline.
    LaunchedEffect(currentUser?.uid, autoStartOnWalkEnabled, isTracking) {
        if (currentUser?.uid.isNullOrBlank() || isTracking) return@LaunchedEffect
        val prefs = userPrefsManager
        if (!autoStartOnWalkEnabled && !prefs.getPendingResumeAfterIdleAutoStop()) return@LaunchedEffect
        val stepMonitor = StepMonitor(context)
        if (!stepMonitor.hasStepCounter()) return@LaunchedEffect
        try {
            stepMonitor.getStepCountFlow().first { it >= IN_APP_AUTO_START_STEP_THRESHOLD }
            if (!trackerViewModel.isTracking.value) {
                trackerViewModel.startTracking()
            }
        } catch (_: CancellationException) {
            // Stopped tracking, toggled setting, or left — expected
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.fillMaxSize()
    ) {
        // ── Pre-login welcome splash (first thing on first launch) ───────────
        fadeComposable(Screen.OnboardingWelcome.route) {
            OnboardingWelcomeScreen(
                onGetStarted = {
                    userPrefsManager.setWelcomeSeen()
                    // From welcome we always head into Terms, unless this device
                    // already accepted the current terms version (covers users
                    // who previously accepted but were forced back through the
                    // new flow by the onboarding-version migration).
                    val next = if (!userPrefsManager.hasAcceptedCurrentTerms())
                        Screen.Terms.route
                    else
                        Screen.Login.route
                    navController.navigate(next) {
                        popUpTo(Screen.OnboardingWelcome.route) { inclusive = true }
                    }
                }
            )
        }

        fadeComposable(Screen.Terms.route) {
            TermsAcceptanceScreen(
                onAccept = {
                    userPrefsManager.setTermsAccepted()
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Terms.route) { inclusive = true }
                    }
                }
            )
        }

        fadeComposable(Screen.Login.route) {
            LoginScreen(
                viewModel = authViewModel,
                onSignInSuccess = {
                    // Post-login onboarding starts at the Description screen now;
                    // Welcome moved to the pre-login slot.
                    val dest = if (!userPrefsManager.isOnboardingDone())
                        Screen.OnboardingDescription.route
                    else
                        Screen.MainTabs.route
                    navController.navigate(dest) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onGoogleSignInClick = {},
                launchGoogleSignIn = launchGoogleSignIn
            )
        }

        // ── Post-login onboarding (3 steps) ──────────────────────────────────

        fadeComposable(Screen.OnboardingDescription.route) {
            OnboardingDescriptionScreen(
                onContinue = {
                    navController.navigate(Screen.OnboardingPermissions.route)
                }
            )
        }

        fadeComposable(Screen.OnboardingPermissions.route) {
            OnboardingPermissionsScreen(
                onContinue = {
                    navController.navigate(Screen.OnboardingProfile.route)
                }
            )
        }

        fadeComposable(Screen.OnboardingProfile.route) {
            // Make the profile screen reflect whatever the user already has on
            // file (e.g. a photo synced from another device) and forward photo
            // picks to ProfileViewModel, which owns Firebase Storage uploads.
            val onboardingProfile by profileViewModel.profile.collectAsStateWithLifecycle()
            LaunchedEffect(currentUser?.uid) {
                val uid = currentUser?.uid
                if (!uid.isNullOrBlank()) profileViewModel.loadProfile(uid)
            }
            OnboardingProfileScreen(
                initialPhysical = userPrefsManager.loadPhysicalProfile(),
                initialVehicle = userPrefsManager.loadVehicleProfile(),
                initialWeeklyCo2GoalKg = userPrefsManager.getWeeklyCo2GoalKg(),
                currentPhotoUrl = onboardingProfile?.photoUrl,
                onPhotoSelected = { uri ->
                    val uid = currentUser?.uid
                    if (!uid.isNullOrBlank()) {
                        profileViewModel.uploadPhoto(uid, uri)
                    }
                },
                onSave = { physical, vehicle, weeklyGoalKg ->
                    onPhysicalProfileSave(physical)
                    onVehicleProfileSave(vehicle)
                    userPrefsManager.setWeeklyCo2GoalKg(weeklyGoalKg)
                    userPrefsManager.setOnboardingDone()
                    // popUpTo(0) clears the entire back stack regardless of how many
                    // onboarding copies were pushed (e.g. double-navigation on first sign-in).
                    navController.navigate(Screen.MainTabs.route) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onSkip = {
                    userPrefsManager.setOnboardingDone()
                    navController.navigate(Screen.MainTabs.route) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        fadeComposable(Screen.MainTabs.route) {
            MainTabsWithSwipe(
                selectedMainTabIndex = selectedMainTabIndex,
                onSelectedMainTabIndexChange = onSelectedMainTabIndexChange,
                navController = navController,
                authViewModel = authViewModel,
                trackerViewModel = trackerViewModel,
                analyticsViewModel = analyticsViewModel,
                profileViewModel = profileViewModel,
                currentUser = currentUser,
                unitSystem = unitSystem,
                autoStartOnWalkEnabled = autoStartOnWalkEnabled,
                onShowActivitySelector = onShowActivitySelector
            )
        }

        // ── Settings (full screen, reached via floating icon on Dashboard / Analysis) ───
        fadeComposable(Screen.Settings.route) { navBackStackEntry ->
            val leaderboardOptedIn by profileViewModel.leaderboardOptedIn.collectAsStateWithLifecycle()
            val leaderboardLoading by profileViewModel.leaderboardLoading.collectAsStateWithLifecycle()
            val leaderboardError by profileViewModel.errorMessage.collectAsStateWithLifecycle()

            val electricRoadUser = remember(navBackStackEntry.id) {
                userPrefsManager.loadVehicleProfile().primaryFuelType == PrimaryFuelType.ELECTRIC
            }

            SettingsScreen(
                unitSystem = unitSystem,
                onUnitSystemChange = onUnitSystemChange,
                currentLocale = currentLocale,
                onLocaleChange = onLocaleChange,
                currentThemeMode = currentThemeMode,
                onThemeChange = onThemeChange,
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
                appVersion = "V1.5.0",
                leaderboardOptIn = leaderboardOptedIn ?: false,
                onLeaderboardOptInChange = { enabled ->
                    currentUser?.uid?.let { profileViewModel.setLeaderboardOptIn(it, enabled) }
                },
                leaderboardLoading = leaderboardLoading,
                leaderboardError = leaderboardError,
                onLeaderboardErrorDismiss = { profileViewModel.clearError() },
                electricRoadUser = electricRoadUser,
                onOpenActivitySelector = onShowActivitySelector,
                onGoPremium = onGoPremium,
                weeklyDigestEnabled = weeklyDigestEnabled,
                onWeeklyDigestChange = onWeeklyDigestChange,
                onProfileClick = { navController.navigate(Screen.Profile.route) },
                onBack = { navController.popBackStack() }
            )
        }

        // ── Profile (reached via Settings → Profile) ───────────────────────────
        fadeComposable(Screen.Profile.route) {
            ProfileScreen(
                user = currentUser,
                viewModel = analyticsViewModel,
                profileViewModel = profileViewModel,
                unitSystem = unitSystem,
                onSessionsClick = { navController.navigate(Screen.SessionsList.route) },
                onPhysicalProfileSave = onPhysicalProfileSave,
                onVehicleProfileSave = onVehicleProfileSave
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

