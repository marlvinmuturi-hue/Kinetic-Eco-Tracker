package Kinetic_Eco.Tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import Kinetic_Eco.Tracker.data.UnitSystem
import Kinetic_Eco.Tracker.navigation.AppNavGraph
import Kinetic_Eco.Tracker.navigation.Screen
import Kinetic_Eco.Tracker.ui.components.AdMobBanner
import Kinetic_Eco.Tracker.ui.components.BottomNavigationBar
import Kinetic_Eco.Tracker.ui.components.DraggableFloatingPlayButton
import Kinetic_Eco.Tracker.ui.screens.*
import Kinetic_Eco.Tracker.ui.theme.KineticEcoTheme
import Kinetic_Eco.Tracker.viewmodel.AnalyticsViewModel
import Kinetic_Eco.Tracker.viewmodel.AuthViewModel
import Kinetic_Eco.Tracker.viewmodel.ProfileViewModel
import Kinetic_Eco.Tracker.viewmodel.TrackerViewModel
import Kinetic_Eco.Tracker.services.UserPreferencesManager
import Kinetic_Eco.Tracker.services.UserPhysicalProfile
import org.osmdroid.config.Configuration

class MainActivity : AppCompatActivity() {
    private val authViewModel: AuthViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }
    private val trackerViewModel: TrackerViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }
    private val analyticsViewModel: AnalyticsViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }
    private val profileViewModel: ProfileViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }
    
    private lateinit var userPrefsManager: UserPreferencesManager
    
    private var showActivitySelector by mutableStateOf(false)
    private var showStopDialogFromNav by mutableStateOf(false)
    private var isSavingSessionFromNav by mutableStateOf(false)
    private var isNavBarVisible by mutableStateOf(true)
    private var lastNavBarInteraction by mutableStateOf(0)
    private var unitSystem by mutableStateOf(UnitSystem.METRIC)
    private var currentLocale by mutableStateOf("auto")
    private var currentThemeMode by mutableStateOf("system")
    private var distanceAlertsEnabled by mutableStateOf(false)
    private var notificationSoundsEnabled by mutableStateOf(true)
    private var autoStartOnWalkEnabled by mutableStateOf(false)
    private var idleStopMinutes by mutableStateOf(10)
    private var currentRoute by mutableStateOf<String?>(null)
    private var physicalProfile by mutableStateOf<UserPhysicalProfile?>(null)
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        @Suppress("UNUSED_VARIABLE")
        val activityRecognitionGranted = permissions[Manifest.permission.ACTIVITY_RECOGNITION] ?: false
        @Suppress("UNUSED_VARIABLE")
        val postNotificationsGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (fineLocationGranted || coarseLocationGranted) {
            // Permissions granted
        } else {
            // Permissions denied
        }
    }
    
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            account?.let {
                authViewModel.signInWithGoogle(it)
            }
        } catch (e: ApiException) {
            // Handle error
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // OsmDroid: init config for tile cache (works offline with cached tiles)
        val ctx = applicationContext
        Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        
        // Firebase auto-initializes with google-services.json
        // No manual initialization needed
        
        // Initialize preferences manager and load physical profile
        userPrefsManager = UserPreferencesManager(applicationContext)

        // Apply app locale (auto = device, or user-selected language)
        val localeTag = try { userPrefsManager.getLocalePreference() } catch (e: Exception) { "auto" }
        if (localeTag.isNotEmpty() && localeTag != "auto") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
        // Apply theme mode (system, light, dark)
        applyThemeMode(try { userPrefsManager.getThemeMode() } catch (e: Exception) { "system" })
        physicalProfile = try { userPrefsManager.loadPhysicalProfile() } catch (e: Exception) { null }
        unitSystem = try { userPrefsManager.getUnitPreference() } catch (e: Exception) { UnitSystem.METRIC }
        currentLocale = try { userPrefsManager.getLocalePreference() } catch (e: Exception) { "auto" }
        currentThemeMode = try { userPrefsManager.getThemeMode() } catch (e: Exception) { "system" }
        distanceAlertsEnabled = try { userPrefsManager.getDistanceAlertsEnabled() } catch (e: Exception) { false }
        notificationSoundsEnabled = try { userPrefsManager.getNotificationSoundsEnabled() } catch (e: Exception) { true }
        autoStartOnWalkEnabled = try { userPrefsManager.getAutoStartOnWalkEnabled() } catch (e: Exception) { false }
        idleStopMinutes = try { userPrefsManager.getIdleStopMinutes() } catch (e: Exception) { 10 }
        
        // Start auto-start monitor service if enabled (works in background)
        if (autoStartOnWalkEnabled) {
            Kinetic_Eco.Tracker.services.AutoStartMonitorService.start(this)
        }
        
        // Request tracking permissions (non-blocking)
        requestTrackingPermissions()
        
        setContent {
            val darkTheme = when (currentThemeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            KineticEcoTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val currentUser by authViewModel.currentUser.collectAsStateWithLifecycle()
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    currentRoute = navBackStackEntry?.destination?.route
                    
                    // Navigate to Tracker when user logs in (avoids dynamic startDestination issues)
                    // Restore sessions from Firestore on login and app launch (when user is logged in)
                    LaunchedEffect(currentUser) {
                        currentUser?.let { user ->
                            analyticsViewModel.restoreSessionsFromFirestore(user.uid)
                            profileViewModel.loadLeaderboardOptIn(user.uid)
                            if (currentRoute == Screen.Login.route) {
                                navController.navigate(Screen.Tracker.route) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            }
                        }
                    }
                    
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Content area: tap empty space to toggle nav bar (hide when visible, show when hidden)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(onClick = { isNavBarVisible = !isNavBarVisible })
                        ) {
                        AppNavGraph(
                            navController = navController,
                            authViewModel = authViewModel,
                            trackerViewModel = trackerViewModel,
                            analyticsViewModel = analyticsViewModel,
                            profileViewModel = profileViewModel,
                            currentUser = currentUser,
                            unitSystem = unitSystem,
                            onUnitSystemChange = { u ->
                                unitSystem = u
                                userPrefsManager.setUnitPreference(u)
                            },
                            distanceAlertsEnabled = distanceAlertsEnabled,
                            onDistanceAlertsChange = { enabled ->
                                distanceAlertsEnabled = enabled
                                userPrefsManager.setDistanceAlertsEnabled(enabled)
                            },
                            notificationSoundsEnabled = notificationSoundsEnabled,
                            onNotificationSoundsChange = { enabled ->
                                notificationSoundsEnabled = enabled
                                userPrefsManager.setNotificationSoundsEnabled(enabled)
                            },
                            currentLocale = currentLocale,
                            onLocaleChange = { localeTag ->
                                userPrefsManager.setLocalePreference(localeTag)
                                currentLocale = localeTag
                                AppCompatDelegate.setApplicationLocales(
                                    if (localeTag == "auto") LocaleListCompat.getEmptyLocaleList()
                                    else LocaleListCompat.forLanguageTags(localeTag)
                                )
                                recreate()
                            },
                            currentThemeMode = currentThemeMode,
                            onThemeChange = { mode ->
                                userPrefsManager.setThemeMode(mode)
                                currentThemeMode = mode
                                applyThemeMode(mode)
                            },
                            autoStartOnWalkEnabled = autoStartOnWalkEnabled,
                            onAutoStartOnWalkChange = { enabled ->
                                autoStartOnWalkEnabled = enabled
                                userPrefsManager.setAutoStartOnWalkEnabled(enabled)
                                if (enabled) {
                                    Kinetic_Eco.Tracker.services.AutoStartMonitorService.start(this@MainActivity)
                                } else {
                                    Kinetic_Eco.Tracker.services.AutoStartMonitorService.stop(this@MainActivity)
                                }
                            },
                            idleStopMinutes = idleStopMinutes,
                            onIdleStopMinutesChange = { minutes ->
                                idleStopMinutes = minutes
                                userPrefsManager.setIdleStopMinutes(minutes)
                            },
                            launchGoogleSignIn = { launchGoogleSignIn() },
                            onShowActivitySelector = { showActivitySelector = true },
                            startDestination = Screen.Login.route,
                            physicalProfile = physicalProfile,
                            onPhysicalProfileSave = { profile ->
                                userPrefsManager.savePhysicalProfile(profile)
                                physicalProfile = profile
                                // Notify TrackingService to reload the profile for accurate calorie calculations
                                trackerViewModel.reloadPhysicalProfile()
                            }
                        )
                        }
                        
                        // Inactivity timer: hide nav bar after 3 seconds of no interaction
                        val showNav = currentUser != null && currentRoute != Screen.Login.route
                        LaunchedEffect(showNav, isNavBarVisible, lastNavBarInteraction) {
                            if (showNav && isNavBarVisible) {
                                delay(3000L)
                                isNavBarVisible = false
                            }
                        }

                        // Banner at top (below status bar). Placed before FAB so floating button stays above the ad where they overlap.
                        if (showNav) {
                            AdMobBanner(
                                modifier = Modifier.align(Alignment.TopCenter),
                                showAgainOnKey = currentRoute
                            )
                        }
                        
                        // Draggable floating play/pause button on non-Tracker tabs (Tracker has PulsatingButton)
                        val isTracking by trackerViewModel.isTracking.collectAsStateWithLifecycle()
                        // Do not wrap in fillMaxSize Box — it sits above NavGraph and can steal/block touches.
                        if (currentUser != null &&
                            currentRoute != Screen.Login.route &&
                            currentRoute != Screen.Tracker.route
                        ) {
                            DraggableFloatingPlayButton(
                                isTracking = isTracking,
                                onClick = { trackerViewModel.startTracking() },
                                onShowStopDialog = { showStopDialogFromNav = true },
                                onSavePosition = { x, y -> userPrefsManager.setFloatingButtonPosition(x, y) },
                                savedPosition = userPrefsManager.getFloatingButtonPosition(),
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        }

                        // Bottom Navigation (only show when logged in and not on login screen)
                        if (showNav) {
                            BottomNavigationBar(
                                currentRoute = currentRoute,
                                onNavigate = { route ->
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                visible = isNavBarVisible,
                                modifier = Modifier.align(Alignment.BottomCenter),
                                onInteraction = { lastNavBarInteraction++ }
                            )
                        }
                    }
                    
                    // Stop dialog when pausing from nav bar (any screen)
                    if (showStopDialogFromNav) {
                        StopTrackingDialog(
                            onDismiss = { if (!isSavingSessionFromNav) showStopDialogFromNav = false },
                            onPause = {
                                if (isSavingSessionFromNav) return@StopTrackingDialog
                                trackerViewModel.pauseTracking()
                                trackerViewModel.resetSession()
                                showStopDialogFromNav = false
                            },
                            onStopAndSave = {
                                if (isSavingSessionFromNav) return@StopTrackingDialog
                                val uid = currentUser?.uid ?: ""
                                if (uid.isEmpty()) {
                                    trackerViewModel.stopTracking()
                                    trackerViewModel.resetSession()
                                    showStopDialogFromNav = false
                                    return@StopTrackingDialog
                                }
                                isSavingSessionFromNav = true
                                trackerViewModel.stopAndSaveSessionAsync(uid) { result ->
                                    isSavingSessionFromNav = false
                                    showStopDialogFromNav = false
                                    result.onSuccess { analyticsViewModel.requestExpandSessionSummary() }
                                    result.onFailure { /* Could show toast */ }
                                }
                            },
                            onDiscard = {
                                if (isSavingSessionFromNav) return@StopTrackingDialog
                                trackerViewModel.stopTracking()
                                trackerViewModel.resetSession()
                                showStopDialogFromNav = false
                            },
                            isSaving = isSavingSessionFromNav
                        )
                    }

                    // Activity Selector Modal
                    if (showActivitySelector) {
                        ActivitySelectorScreen(
                            selectedMode = trackerViewModel.manualActivityMode.value,
                            onActivitySelected = { activity ->
                                trackerViewModel.setManualActivityMode(activity)
                                showActivitySelector = false
                            },
                            onDismiss = { showActivitySelector = false }
                        )
                    }
                }
            }
        }
    }
    
    private fun applyThemeMode(mode: String) {
        val nightMode = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun requestTrackingPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    fun launchGoogleSignIn() {
        val signInIntent = authViewModel.getGoogleSignInClient().signInIntent
        googleSignInLauncher.launch(signInIntent)
    }
}
