package Kinetic_Eco.Tracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import Kinetic_Eco.Tracker.R
import Kinetic_Eco.Tracker.data.ActivityAnalysis
import Kinetic_Eco.Tracker.data.CohortProfile
import Kinetic_Eco.Tracker.data.RouteCluster
import Kinetic_Eco.Tracker.data.SessionStats
import Kinetic_Eco.Tracker.services.AIAnalysisService
import Kinetic_Eco.Tracker.services.CohortAnalysisService
import Kinetic_Eco.Tracker.services.RouteIntelligenceService
import Kinetic_Eco.Tracker.services.SessionManager
import Kinetic_Eco.Tracker.data.ActivitySegment
import Kinetic_Eco.Tracker.data.TravelRecap
import Kinetic_Eco.Tracker.services.TravelRecapService
import Kinetic_Eco.Tracker.services.UserPreferencesManager

class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionManager = SessionManager(application)
    private val aiAnalysisService = AIAnalysisService.getInstance()
    private val userPrefsManager = UserPreferencesManager(application)
    private val routeIntelligenceService = RouteIntelligenceService(sessionManager)

    /**
     * Backfill batches per [loadRouteClusters] call. 8 × 25 = 200 sessions, enough to
     * catch up a typical history in a few visits while keeping any single load short.
     */
    private val MAX_BACKFILL_PASSES_PER_LOAD = 8
    
    // Travel recap: geocoded cities/countries + personal bests
    private val _travelRecap = MutableStateFlow<TravelRecap?>(null)
    val travelRecap: StateFlow<TravelRecap?> = _travelRecap.asStateFlow()

    private val _travelRecapLoading = MutableStateFlow(false)
    val travelRecapLoading: StateFlow<Boolean> = _travelRecapLoading.asStateFlow()

    fun loadTravelRecap(userId: String) {
        if (_travelRecapLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _travelRecapLoading.value = true
            try {
                val sessions = getAllSessions(userId).first()
                _travelRecap.value = TravelRecapService(getApplication()).compute(sessions)
            } catch (e: Exception) {
                android.util.Log.e("AnalyticsViewModel", "Travel recap failed", e)
            } finally {
                _travelRecapLoading.value = false
            }
        }
    }

    // CO2 tier + cohort analysis (computed locally from session history)
    private val _cohortProfile = MutableStateFlow<CohortProfile?>(null)
    val cohortProfile: StateFlow<CohortProfile?> = _cohortProfile.asStateFlow()

    // Route intelligence state
    private val _routeClusters = MutableStateFlow<List<RouteCluster>>(emptyList())
    val routeClusters: StateFlow<List<RouteCluster>> = _routeClusters.asStateFlow()

    private val _routeClustersLoading = MutableStateFlow(false)
    val routeClustersLoading: StateFlow<Boolean> = _routeClustersLoading.asStateFlow()

    // AI Analysis State
    private val _aiAnalysisState = MutableStateFlow<AIAnalysisState>(AIAnalysisState.Idle)
    val aiAnalysisState: StateFlow<AIAnalysisState> = _aiAnalysisState.asStateFlow()
    
    /**
     * Rolling window for AI analysis (1–366 days). Defaults to the past 7 days so a
     * fresh user (or anyone tapping "Analyze" without first picking a window) gets a
     * focused weekly summary instead of "everything since signup". Single-day mode
     * uses [analysisSessionDateKey] instead.
     */
    private val _rollingAnalysisDays = MutableStateFlow(7)
    val rollingAnalysisDays: StateFlow<Int> = _rollingAnalysisDays.asStateFlow()

    /** yyyy-MM-dd: AI uses sessions on this session date only (third mode vs rolling 7/30/90). */
    private val _analysisSessionDateKey = MutableStateFlow<String?>(null)
    val analysisSessionDateKey: StateFlow<String?> = _analysisSessionDateKey.asStateFlow()
    
    // One-shot flag: expand Session Summary when user lands on Analytics after ending a session
    private val _shouldExpandSessionSummary = MutableStateFlow(false)
    val shouldExpandSessionSummary: StateFlow<Boolean> = _shouldExpandSessionSummary.asStateFlow()

    fun requestExpandSessionSummary() {
        _shouldExpandSessionSummary.value = true
    }

    fun clearExpandSessionSummary() {
        _shouldExpandSessionSummary.value = false
    }

    // Selected session for detail view — StateFlow so SessionDetailScreen reacts to edits.
    private val _selectedSession = MutableStateFlow<SessionStats?>(null)
    val selectedSession: StateFlow<SessionStats?> = _selectedSession.asStateFlow()

    fun setSelectedSession(session: SessionStats) {
        _selectedSession.value = session
    }

    fun clearSelectedSession() {
        _selectedSession.value = null
    }

    fun updateSegments(sessionId: String, userId: String, segments: List<ActivitySegment>) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.updateSessionSegments(sessionId, userId, segments).onSuccess {
                val updated = sessionManager.getSessionById(sessionId)
                if (updated != null) _selectedSession.value = updated
            }
        }
    }
    
    fun getAllSessions(userId: String): Flow<List<SessionStats>> {
        return sessionManager.getAllSessions(userId)
    }
    
    suspend fun getLatestSession(userId: String): SessionStats? {
        return sessionManager.getLatestSession(userId)
    }
    
    /**
     * Restore sessions from Firestore into Room.
     * Called on login and app launch when user is logged in.
     */
    fun restoreSessionsFromFirestore(userId: String) {
        // Must not run on Main: Firestore + many Room writes will ANR ("app isn't responding").
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.restoreSessionsFromFirestore(userId)
        }
    }
    
    fun getAggregatedStats(sessions: List<SessionStats>): SessionStats {
        val folded = sessions.fold(SessionStats()) { acc, session ->
            SessionStats(
                totalDuration = acc.totalDuration + session.totalDuration,
                totalDistance = acc.totalDistance + session.totalDistance,
                caloriesBurned = acc.caloriesBurned + session.caloriesBurned,
                co2Emissions = acc.co2Emissions + session.co2Emissions,
                co2Conserved = acc.co2Conserved + session.co2Conserved,
                totalSteps = acc.totalSteps + session.totalSteps,
                topSpeedMps = maxOf(acc.topSpeedMps, session.topSpeedMps),
                segments = acc.segments + session.segments,
                breakdown = mergeBreakdowns(acc.breakdown, session.breakdown)
            )
        }
        // Derive totalSteps from breakdown (source of truth for per-activity steps)
        // Fixes undercount when session.totalSteps is 0 but breakdown has steps
        val stepsFromBreakdown = folded.breakdown.values.sumOf { it.steps }
        return folded.copy(
            totalSteps = if (stepsFromBreakdown > 0) stepsFromBreakdown else folded.totalSteps
        )
    }
    
    private fun mergeBreakdowns(
        breakdown1: Map<Kinetic_Eco.Tracker.data.ActivityType, Kinetic_Eco.Tracker.data.ActivityBreakdown>,
        breakdown2: Map<Kinetic_Eco.Tracker.data.ActivityType, Kinetic_Eco.Tracker.data.ActivityBreakdown>
    ): Map<Kinetic_Eco.Tracker.data.ActivityType, Kinetic_Eco.Tracker.data.ActivityBreakdown> {
        val result = breakdown1.toMutableMap()
        breakdown2.forEach { (activity, breakdown) ->
            val existing = result[activity] ?: Kinetic_Eco.Tracker.data.ActivityBreakdown()
            result[activity] = Kinetic_Eco.Tracker.data.ActivityBreakdown(
                time = existing.time + breakdown.time,
                distance = existing.distance + breakdown.distance,
                steps = existing.steps + breakdown.steps
            )
        }
        return result
    }
    
    // AI Analysis Functions
    fun setAnalysisSessionDay(dateKey: String) {
        _analysisSessionDateKey.value = dateKey
    }

    /** Clears single-day selection so rolling-window analysis is used again. */
    fun clearAnalysisSessionDay() {
        _analysisSessionDateKey.value = null
    }

    fun setRollingAnalysisDays(days: Int) {
        _analysisSessionDateKey.value = null
        _rollingAnalysisDays.value = days.coerceIn(1, 366)
    }

    fun analyzeActivity(userId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val day = _analysisSessionDateKey.value
            if (day != null) {
                val sessions = sessionManager.getAllSessions(userId).first()
                if (sessions.none { it.date == day }) {
                    val msg = getApplication<Application>().getString(
                        Kinetic_Eco.Tracker.R.string.analysis_no_sessions_this_day
                    )
                    withContext(Dispatchers.Main.immediate) { _aiAnalysisState.value = AIAnalysisState.Error(msg) }
                    return@launch
                }
            }

            withContext(Dispatchers.Main.immediate) { _aiAnalysisState.value = AIAnalysisState.Loading }

            val locale = resolveLocaleForAnalysis()
            val result = aiAnalysisService.analyzeActivity(_rollingAnalysisDays.value, locale, sessionDateKey = day)

            withContext(Dispatchers.Main.immediate) {
                _aiAnalysisState.value = result.fold(
                    onSuccess = { analysis -> AIAnalysisState.Success(analysis) },
                    onFailure = { error -> AIAnalysisState.Error(error.message ?: "Unknown error") }
                )
            }
        }
    }
    
    /**
     * Cluster the user's route history and surface repeat commutes with CO₂
     * trends and greener-alternative suggestions.
     *
     * [lookbackDays] controls the analysis window — default 90 days gives
     * enough data to detect weekly commute patterns while staying fast.
     * Call this lazily (e.g. when the user opens a "commute insights" section)
     * rather than on every session load.
     */
    fun loadRouteClusters(
        userId: String,
        lookbackDays: Int = 90,
        profile: Kinetic_Eco.Tracker.data.VehicleProfile =
            Kinetic_Eco.Tracker.data.VehicleProfile.DEFAULT
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _routeClustersLoading.value = true
            // Sessions saved before schema v9 have no denormalised endpoints and are
            // invisible to the clustering query. Backfill in bounded batches first —
            // one route in memory at a time — so an existing history is not silently
            // excluded, then cluster. Each pass shrinks the queue; the loop stops when
            // there is nothing left or the budget is spent, so opening this section can
            // never block on thousands of rows.
            var passes = 0
            while (passes < MAX_BACKFILL_PASSES_PER_LOAD) {
                val filled = runCatching { sessionManager.backfillRouteEndpoints(userId) }
                    .getOrDefault(0)
                if (filled == 0) break
                passes++
            }
            _routeClusters.value =
                routeIntelligenceService.getRouteClusters(userId, lookbackDays, profile)
            _routeClustersLoading.value = false
        }
    }

    fun resetAIAnalysis() {
        _aiAnalysisState.value = AIAnalysisState.Idle
    }

    /**
     * Recompute the cohort profile from [sessions]. Called from the UI when the
     * session list changes so the tier badge and insights stay current without a
     * dedicated network call.
     */
    fun computeCohortProfile(sessions: List<SessionStats>) {
        viewModelScope.launch(Dispatchers.Default) {
            _cohortProfile.value = CohortAnalysisService.compute(sessions)
        }
    }
    
    /** Resolves locale for AI analysis: "auto" -> device locale, else user preference. Returns en/fr/de/es/zh. */
    private fun resolveLocaleForAnalysis(): String {
        val pref = userPrefsManager.getLocalePreference()
        if (pref != "auto" && pref in setOf("en", "fr", "de", "es", "zh")) return pref
        val deviceLang = Locale.getDefault().language
        return when (deviceLang) {
            "fr", "de", "es", "zh" -> deviceLang
            else -> "en"
        }
    }
}

sealed class AIAnalysisState {
    object Idle : AIAnalysisState()
    object Loading : AIAnalysisState()
    data class Success(val analysis: ActivityAnalysis) : AIAnalysisState()
    data class Error(val message: String) : AIAnalysisState()
}



