package Kinetic_Eco.Tracker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
    /**
     * Set when the activity is launched (or re-delivered) via the
     * "What are you doing?" confirm notification's `EXTRA_OPEN_ACTIVITY_SELECTOR`.
     * Consumed by a Compose [LaunchedEffect] which routes the user to the
     * Tracker tab and pops [ActivitySelectorScreen] once the user is signed in
     * and the nav graph has settled. Cleared as soon as the request fires so
     * configuration changes don't replay it.
     */
    private var pendingShowActivitySelector by mutableStateOf(false)
    private var showStopDialogFromNav by mutableStateOf(false)
    private var isSavingSessionFromNav by mutableStateOf(false)
    private var isNavBarVisible by mutableStateOf(true)
    private var unitSystem by mutableStateOf(UnitSystem.METRIC)
    private var currentLocale by mutableStateOf("auto")
    private var currentThemeMode by mutableStateOf("dark")
    private var notificationSoundsEnabled by mutableStateOf(true)
    private var autoStartOnWalkEnabled by mutableStateOf(false)
    private var idleStopMinutes by mutableStateOf(10)
    private var weeklyDigestEnabled by mutableStateOf(true)
    private var dailyDigestEnabled by mutableStateOf(true)
    /** True while the one-time Auto Detect setup dialog (idle-time picker) is visible. */
    private var showAutoDetectSetupDialog by mutableStateOf(false)
    private var currentRoute by mutableStateOf<String?>(null)
    /** Which primary tab (0–3) is shown inside [Screen.MainTabs]; survives sub-navigation (e.g. sessions list). */
    private var selectedMainTabIndex by mutableIntStateOf(0)
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        @Suppress("UNUSED_VARIABLE")
        val activityRecognitionGranted = permissions[Manifest.permission.ACTIVITY_RECOGNITION] ?: false
        val postNotificationsGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (fineLocationGranted || coarseLocationGranted) {
            // Permissions granted
        } else {
            // Permissions denied
        }
        // Re-bind activity transitions after user grants recognition (auto-start was a no-op until now).
        if (activityRecognitionGranted && ::userPrefsManager.isInitialized &&
            (userPrefsManager.getAutoStartOnWalkEnabled() || userPrefsManager.getPendingResumeAfterIdleAutoStop())
        ) {
            Kinetic_Eco.Tracker.services.AutoStartMonitorService.start(this@MainActivity)
        }
    }

    /** "All the time" location — required on many devices for GPS updates while the app is not visible. */
    private val requestBackgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("MainActivity", "ACCESS_BACKGROUND_LOCATION granted=$granted")
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

        // One-time onboarding-flow migration. Bumping CURRENT_ONBOARDING_FLOW_VERSION
        // inside UserPreferencesManager forces existing users back through the
        // (potentially restructured) onboarding once. Safe to call on every launch
        // — it short-circuits when the device is already on the current version.
        userPrefsManager.migrateOnboardingFlowIfNeeded()

        // One-time theme migration: collapse the legacy 3-mode + accent-picker
        // world down to Light/Dark only. Users previously on "system" are pinned
        // to whatever System resolves to *right now* so the visual change is
        // imperceptible. Pass the device's current dark-mode state via the
        // resources configuration since UserPreferencesManager has no Compose
        // / view context.
        val systemIsDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        userPrefsManager.migrateThemeIfNeeded(systemIsDark)

        // Apply app locale (auto = device, or user-selected language)
        val localeTag = try { userPrefsManager.getLocalePreference() } catch (e: Exception) { "auto" }
        if (localeTag.isNotEmpty() && localeTag != "auto") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
        // Apply theme mode (light or dark — "system" is no longer supported and
        // existing values have been migrated above).
        applyThemeMode(try { userPrefsManager.getThemeMode() } catch (e: Exception) { "dark" })
        unitSystem = try { userPrefsManager.getUnitPreference() } catch (e: Exception) { UnitSystem.METRIC }
        currentLocale = try { userPrefsManager.getLocalePreference() } catch (e: Exception) { "auto" }
        currentThemeMode = try { userPrefsManager.getThemeMode() } catch (e: Exception) { "dark" }
        notificationSoundsEnabled = try { userPrefsManager.getNotificationSoundsEnabled() } catch (e: Exception) { true }
        autoStartOnWalkEnabled = try { userPrefsManager.getAutoStartOnWalkEnabled() } catch (e: Exception) { false }
        idleStopMinutes = try { userPrefsManager.getIdleStopMinutes() } catch (e: Exception) { 10 }
        weeklyDigestEnabled = try { userPrefsManager.isWeeklyDigestEnabled() } catch (e: Exception) { true }
        dailyDigestEnabled = try { userPrefsManager.isDailyDigestEnabled() } catch (e: Exception) { true }
        
        // Auto-start on walk, or re-arm after idle auto-stop (pending flag)
        if (autoStartOnWalkEnabled || userPrefsManager.getPendingResumeAfterIdleAutoStop()) {
            Kinetic_Eco.Tracker.services.AutoStartMonitorService.start(this)
            requestBackgroundLocationIfNeeded()
        }
        
        // Request tracking permissions (non-blocking)
        requestTrackingPermissions()

        // Pick up any extras delivered with the launching intent — most importantly
        // EXTRA_OPEN_ACTIVITY_SELECTOR from the auto-start "What are you doing?"
        // confirm notification. onNewIntent handles the same case when the activity
        // is already alive (singleTop).
        handleIncomingIntent(intent)

        setContent {
            val initialStartDestination = remember {
                // Onboarding flow on first launch (and after a flow-version
                // migration): pre-login Welcome → Terms → Login. Returning users
                // who are still signed in are redirected forward by the
                // LaunchedEffect on currentUser below.
                when {
                    !userPrefsManager.hasSeenWelcome() -> Screen.OnboardingWelcome.route
                    !userPrefsManager.hasAcceptedCurrentTerms() -> Screen.Terms.route
                    else -> Screen.Login.route
                }
            }
            // Only two valid modes now; default to dark for any unexpected value.
            val darkTheme = currentThemeMode != "light"
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
                            // Only redirect here when the nav stack is still sitting on Login.
                            // onSignInSuccess in LoginScreen handles the same redirect for interactive
                            // sign-in; this LaunchedEffect handles the "already logged in on launch"
                            // case. Guard with the full onboarding-route set so we do not push a
                            // second copy if the user is already mid-flow (e.g. mid-onboarding when
                            // the auth state observer re-fires after a transient sign-out/sign-in).
                            val onOnboarding = currentRoute == Screen.OnboardingWelcome.route ||
                                currentRoute == Screen.OnboardingDescription.route ||
                                currentRoute == Screen.OnboardingPermissions.route ||
                                currentRoute == Screen.OnboardingProfile.route
                            if (currentRoute == Screen.Login.route && !onOnboarding) {
                                selectedMainTabIndex = 0
                                // Post-login onboarding starts at the Description screen; Welcome
                                // is now the pre-login splash and is skipped once the user is
                                // authenticated.
                                val dest = if (!userPrefsManager.isOnboardingDone())
                                    Screen.OnboardingDescription.route
                                else
                                    Screen.MainTabs.route
                                navController.navigate(dest) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }

                    // ── Activity-confirm notification deep link ───────────────────
                    //
                    // When TrackingService fires the "What are you doing?" notification
                    // and the user taps the body / "Confirm in app", MainActivity is
                    // brought to the foreground (singleTop) with EXTRA_OPEN_ACTIVITY_SELECTOR.
                    // We finish the redirect here so it survives:
                    //   • Cold launch (intent on first composition)
                    //   • Warm launch via onNewIntent (pendingShowActivitySelector flips)
                    //   • The user being on a non-MainTabs route (we navigate first,
                    //     then the keying on currentRoute fires the selector once we land).
                    //
                    // Guarded on currentUser != null — auto-start tracking should only
                    // be running for a signed-in user, but if the auth state somehow
                    // lapsed we'd rather drop the request than yank an onboarding/login
                    // user into the Tracker tab.
                    LaunchedEffect(pendingShowActivitySelector, currentUser, currentRoute) {
                        if (!pendingShowActivitySelector) return@LaunchedEffect
                        if (currentUser == null) return@LaunchedEffect
                        // Don't hijack onboarding/terms/login flows — drop the request silently.
                        val onBlockingFlow = currentRoute == Screen.Login.route ||
                            currentRoute == Screen.Terms.route ||
                            currentRoute == Screen.OnboardingWelcome.route ||
                            currentRoute == Screen.OnboardingDescription.route ||
                            currentRoute == Screen.OnboardingPermissions.route ||
                            currentRoute == Screen.OnboardingProfile.route
                        if (onBlockingFlow) {
                            pendingShowActivitySelector = false
                            return@LaunchedEffect
                        }
                        if (currentRoute == Screen.MainTabs.route) {
                            // Already on the tabbed shell: just switch to the Tracker
                            // tab and pop the in-app selector.
                            selectedMainTabIndex = 1
                            showActivitySelector = true
                            isNavBarVisible = true
                            pendingShowActivitySelector = false
                        } else if (currentRoute != null) {
                            // Sub-screen (Settings, Profile, SessionDetail, …): bounce
                            // back to MainTabs first. The next pass through this effect
                            // (re-keyed on currentRoute) lands in the MainTabs branch
                            // above and finishes the redirect.
                            selectedMainTabIndex = 1
                            navController.navigate(Screen.MainTabs.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }

                    val autoHideScope = rememberCoroutineScope()
                    val autoHideJob = remember { mutableStateOf<Job?>(null) }
                    val nestedScrollConnection = remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                isNavBarVisible = true
                                autoHideJob.value?.cancel()
                                autoHideJob.value = autoHideScope.launch {
                                    delay(3_000)
                                    isNavBarVisible = false
                                }
                                return Offset.Zero
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        autoHideJob.value = autoHideScope.launch {
                            delay(3_000)
                            isNavBarVisible = false
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize().nestedScroll(nestedScrollConnection)) {
                        Box(modifier = Modifier.fillMaxSize()) {
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
                                // Only "light" / "dark" reach this callback now (the
                                // selector card has no third option), but UserPreferencesManager
                                // normalises anyway for safety.
                                userPrefsManager.setThemeMode(mode)
                                currentThemeMode = mode
                                applyThemeMode(mode)
                            },
                            onGoPremium = { openPremiumOrStoreListing() },
                            weeklyDigestEnabled = weeklyDigestEnabled,
                            onWeeklyDigestChange = { enabled ->
                                weeklyDigestEnabled = enabled
                                userPrefsManager.setWeeklyDigestEnabled(enabled)
                            },
                            dailyDigestEnabled = dailyDigestEnabled,
                            onDailyDigestChange = { enabled ->
                                dailyDigestEnabled = enabled
                                userPrefsManager.setDailyDigestEnabled(enabled)
                            },
                            autoStartOnWalkEnabled = autoStartOnWalkEnabled,
                            onAutoStartOnWalkChange = { enabled ->
                                autoStartOnWalkEnabled = enabled
                                userPrefsManager.setAutoStartOnWalkEnabled(enabled)
                                if (enabled) {
                                    Kinetic_Eco.Tracker.services.AutoStartMonitorService.start(this@MainActivity)
                                    requestBackgroundLocationIfNeeded()
                                } else if (!userPrefsManager.getPendingResumeAfterIdleAutoStop()) {
                                    // Do not tear down monitor while "resume after idle auto-stop" is still armed
                                    Kinetic_Eco.Tracker.services.AutoStartMonitorService.stop(this@MainActivity)
                                }
                            },
                            idleStopMinutes = idleStopMinutes,
                            onIdleStopMinutesChange = { minutes ->
                                idleStopMinutes = minutes
                                userPrefsManager.setIdleStopMinutes(minutes)
                            },
                            launchGoogleSignIn = { launchGoogleSignIn() },
                            onShowActivitySelector = { pendingShowActivitySelector = true },
                            startDestination = initialStartDestination,
                            onPhysicalProfileSave = { profile ->
                                userPrefsManager.savePhysicalProfile(profile)
                                // Notify TrackingService to reload the profile for accurate calorie calculations
                                trackerViewModel.reloadPhysicalProfile()
                            },
                            onVehicleProfileSave = { profile ->
                                userPrefsManager.saveVehicleProfile(profile)
                                trackerViewModel.reloadVehicleProfile()
                            },
                            userPrefsManager = userPrefsManager,
                            selectedMainTabIndex = selectedMainTabIndex,
                            onSelectedMainTabIndexChange = { selectedMainTabIndex = it }
                        )
                        }
                        
                        val isOnboardingRoute = currentRoute == Screen.OnboardingWelcome.route ||
                            currentRoute == Screen.OnboardingDescription.route ||
                            currentRoute == Screen.OnboardingPermissions.route ||
                            currentRoute == Screen.OnboardingProfile.route
                        val isFullScreenSettingsRoute = currentRoute == Screen.Settings.route ||
                            currentRoute == Screen.Profile.route
                        val showNav = currentUser != null &&
                            currentRoute != Screen.Login.route &&
                            !isOnboardingRoute &&
                            !isFullScreenSettingsRoute

                        // Banner at top (below status bar). Placed before FAB so floating button stays above the ad where they overlap.
                        if (showNav) {
                            AdMobBanner(
                                modifier = Modifier.align(Alignment.TopCenter),
                                showAgainOnKey = "${currentRoute}_${selectedMainTabIndex}"
                            )
                        }
                        
                        // Draggable floating play/pause button on non-Tracker tabs (Tracker has PulsatingButton)
                        val isTracking by trackerViewModel.isTracking.collectAsStateWithLifecycle()
                        // Do not wrap in fillMaxSize Box — it sits above NavGraph and can steal/block touches.
                        if (currentUser != null &&
                            currentRoute != Screen.Login.route &&
                            currentRoute != Screen.Terms.route &&
                            !isOnboardingRoute &&
                            !isFullScreenSettingsRoute &&
                            // Tab index 1 = Tracker tab (live GPS) — hide floating play button there
                            (currentRoute != Screen.MainTabs.route || selectedMainTabIndex != 1)
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
                                selectedMainTabIndex = when (currentRoute) {
                                    Screen.MainTabs.route -> selectedMainTabIndex
                                    else -> -1
                                },
                                onMainTabSelected = { index ->
                                    selectedMainTabIndex = index
                                    navController.navigate(Screen.MainTabs.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                visible = isNavBarVisible &&
                                    (currentRoute != Screen.MainTabs.route || selectedMainTabIndex != 1),
                                modifier = Modifier.align(Alignment.BottomCenter),
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
                                if (activity == null) {
                                    // Always enable auto-start when Auto Detect is chosen
                                    if (!autoStartOnWalkEnabled) {
                                        autoStartOnWalkEnabled = true
                                        userPrefsManager.setAutoStartOnWalkEnabled(true)
                                        Kinetic_Eco.Tracker.services.AutoStartMonitorService.start(this@MainActivity)
                                    }
                                    // Show the idle-time setup dialog only on the first selection
                                    if (!userPrefsManager.isAutodetectSetupDone()) {
                                        showAutoDetectSetupDialog = true
                                    }
                                }
                            },
                            onDismiss = { showActivitySelector = false }
                        )
                    }

                    // Auto Detect one-time setup: idle-time preference dialog
                    if (showAutoDetectSetupDialog) {
                        AutoDetectSetupDialog(
                            currentIdleMinutes = idleStopMinutes,
                            onConfirm = { minutes ->
                                idleStopMinutes = minutes
                                userPrefsManager.setIdleStopMinutes(minutes)
                                userPrefsManager.setAutodetectSetupDone()
                                showAutoDetectSetupDialog = false
                            },
                            onDismiss = {
                                userPrefsManager.setAutodetectSetupDone()
                                showAutoDetectSetupDialog = false
                            }
                        )
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Ensure subsequent reads of getIntent() see the new payload (default
        // Activity behaviour keeps the original launching intent otherwise).
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Inspect the launching/redelivered intent for notification deep-link extras.
     *
     * Currently handles the activity-confirm notification: when the user taps
     * the body or the "Confirm in app" action, [TrackingService] sends us an
     * intent carrying [EXTRA_OPEN_ACTIVITY_SELECTOR]. We:
     *   1. Dismiss the originating notification (defence-in-depth — the
     *      notification is built with `setAutoCancel(true)`, but explicit
     *      cancellation guarantees it's gone even on weird OEM behaviour).
     *   2. Flip [pendingShowActivitySelector] so a Compose [LaunchedEffect] can
     *      finish the redirect once the nav graph and auth state are observable.
     *   3. Strip the extra so a configuration change doesn't replay the redirect.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_ACTIVITY_SELECTOR, false) == true) {
            pendingShowActivitySelector = true
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(Kinetic_Eco.Tracker.services.ActivityConfirmReceiver.CONFIRM_NOTIFICATION_ID)
            } catch (_: Exception) {
                // Cancellation is best-effort; setAutoCancel on the notification
                // already handles the body-tap path.
            }
            intent.removeExtra(EXTRA_OPEN_ACTIVITY_SELECTOR)
        }
    }

    /** Opens Play Store app listing — swap for Play Billing / paywall when ready. */
    private fun openPremiumOrStoreListing() {
        val pkg = packageName
        val marketUri = Uri.parse("market://details?id=$pkg")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, marketUri))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            } catch (_: ActivityNotFoundException) {
                android.util.Log.w("MainActivity", "Could not open Play Store for premium flow")
            }
        }
    }

    private fun applyThemeMode(mode: String) {
        // Only Light and Dark are supported; anything else collapses to Dark.
        val nightMode = if (mode == "light") {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
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

    private fun requestBackgroundLocationIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    companion object {
        /**
         * Boolean extra delivered by [Kinetic_Eco.Tracker.services.TrackingService]
         * via its activity-confirm notification ("What are you doing?"). When
         * present and true, MainActivity routes the user to the Tracker tab
         * (index 1 inside [Screen.MainTabs]) and pops [ActivitySelectorScreen]
         * so they can pick the correct activity in-app.
         */
        const val EXTRA_OPEN_ACTIVITY_SELECTOR = "kinetic_eco.EXTRA_OPEN_ACTIVITY_SELECTOR"
    }
}
