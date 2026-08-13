package Kinetic_Eco.Tracker.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import Kinetic_Eco.Tracker.data.LeaderboardCategory
import Kinetic_Eco.Tracker.data.LeaderboardEntry
import Kinetic_Eco.Tracker.data.UserProfile
import Kinetic_Eco.Tracker.services.LeaderboardPeriod
import Kinetic_Eco.Tracker.services.LeaderboardService
import Kinetic_Eco.Tracker.services.UserPreferencesManager
import Kinetic_Eco.Tracker.services.UserProfileService

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val profileService = UserProfileService()
    private val leaderboardService = LeaderboardService.getInstance()
    private val userPrefsManager = UserPreferencesManager(application)

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _leaderboardOptedIn = MutableStateFlow<Boolean?>(null)
    val leaderboardOptedIn: StateFlow<Boolean?> = _leaderboardOptedIn.asStateFlow()

    private val _leaderboardEntries = MutableStateFlow<List<LeaderboardEntry>>(emptyList())
    val leaderboardEntries: StateFlow<List<LeaderboardEntry>> = _leaderboardEntries.asStateFlow()

    /**
     * The dashboard's compact leaderboard row is the only surface that renders entries, and it always
     * wants the current calendar week. The default must therefore match it: [loadLeaderboard] reads this
     * at call time, and `autoOptInOnLogin` loads without setting a period first, so any other default
     * races the dashboard's own load and can win — which is how a 30-day total ended up under a
     * weekly heading.
     */
    private val _leaderboardPeriod = MutableStateFlow<LeaderboardPeriod>(LeaderboardPeriod.ThisWeek)
    val leaderboardPeriod: StateFlow<LeaderboardPeriod> = _leaderboardPeriod.asStateFlow()

    /**
     * Single supported leaderboard category — CO₂ saved. Kept as a flow so
     * server-call signatures don't need to change and so a future re-introduction
     * of multiple categories is a small additive edit.
     */
    private val _leaderboardCategory = MutableStateFlow(LeaderboardCategory.CO2_SAVED)
    val leaderboardCategory: StateFlow<LeaderboardCategory> = _leaderboardCategory.asStateFlow()

    /** yyyy-MM-dd session start date; when set, leaderboard uses global daily #1 for that day. */
    private val _leaderboardSessionDayKey = MutableStateFlow<String?>(null)
    val leaderboardSessionDayKey: StateFlow<String?> = _leaderboardSessionDayKey.asStateFlow()

    private val _leaderboardLoading = MutableStateFlow(false)
    val leaderboardLoading: StateFlow<Boolean> = _leaderboardLoading.asStateFlow()

    /**
     * True only while an opt-in/opt-out *write* is in flight — deliberately separate from
     * [leaderboardLoading], which also covers entry *fetches*.
     *
     * The Settings switch disables itself on this flag. Sharing one flag meant a background
     * `loadLeaderboard` — which reads the whole `leaderboard` collection plus a reactions
     * subcollection per row, and is fired on login and by the dashboard — held the switch
     * disabled for the length of that fetch. Tapping it did nothing, silently, which is
     * indistinguishable from a dead control.
     */
    private val _leaderboardOptInBusy = MutableStateFlow(false)
    val leaderboardOptInBusy: StateFlow<Boolean> = _leaderboardOptInBusy.asStateFlow()

    fun loadProfile(userId: String) {
        // Pre-populate from cache so the greeting shows the real name immediately
        val cached = userPrefsManager.getCachedDisplayName()
        if (cached != null && _profile.value == null) {
            _profile.value = UserProfile(displayName = cached)
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            val fetched = profileService.getProfile(userId)
            _profile.value = fetched
            userPrefsManager.setCachedDisplayName(fetched?.displayName)
            _isLoading.value = false
        }
    }

    fun saveDisplayName(userId: String, displayName: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val trimmed = displayName?.trim()?.takeIf { it.isNotEmpty() }
            profileService.saveDisplayName(userId, trimmed)
                .onSuccess {
                    _profile.value = _profile.value?.copy(displayName = trimmed)
                        ?: UserProfile(displayName = trimmed)
                    userPrefsManager.setCachedDisplayName(trimmed)
                }
                .onFailure { _errorMessage.value = "Failed to save name" }
            _isLoading.value = false
        }
    }

    fun uploadPhoto(userId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _errorMessage.value = null
            profileService.uploadProfilePhoto(getApplication(), userId, uri)
                .onSuccess { url ->
                    _profile.value = _profile.value?.copy(photoUrl = url)
                        ?: UserProfile(photoUrl = url)
                }
                .onFailure { e ->
                    val msg = e.message?.lowercase() ?: ""
                    _errorMessage.value = when {
                        msg.contains("could not read") -> e.message
                        msg.contains("permission") || msg.contains("unauthorized") || msg.contains("storage/") ->
                            "Storage access denied. Ensure you're signed in and run: firebase deploy --only storage"
                        msg.contains("network") || msg.contains("unable to resolve") || msg.contains("connection") ->
                            "Upload failed. Check your internet connection."
                        else -> "Failed to upload: ${e.message?.take(60) ?: "Try again or choose a different image."}"
                    }
                }
            _isLoading.value = false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun loadLeaderboardOptIn(userId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            leaderboardService.getOptInStatus(userId)
                // A read that did not complete says nothing about the preference. Leave whatever is
                // already known in place rather than rendering an unread value as "off".
                .onSuccess { stored -> if (stored != null) _leaderboardOptedIn.value = stored }
                .onFailure { Log.w(TAG, "Opt-in status unavailable; leaving switch state unchanged", it) }
        }
    }

    /**
     * Opt the user into the leaderboard on login if they have never set a preference.
     * If they previously opted out (false), their choice is respected and they stay opted out.
     *
     * Auto-opt-in requires *positive* evidence that no preference exists. A read that failed is not
     * that evidence — treating it as such put opted-out users back on a public board whenever the
     * app cold-started offline.
     */
    fun autoOptInOnLogin(userId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _leaderboardLoading.value = true
            leaderboardService.getOptInStatus(userId)
                .onSuccess { stored ->
                    when (stored) {
                        null -> {
                            // Confirmed absent — no preference ever set, so opt them in automatically.
                            leaderboardService.optIn(userId)
                                .onSuccess { _leaderboardOptedIn.value = true }
                                .onFailure { _leaderboardOptedIn.value = false }
                        }
                        // User explicitly opted out — respect their choice.
                        false -> _leaderboardOptedIn.value = false
                        // Already opted in — just load fresh data.
                        true -> _leaderboardOptedIn.value = true
                    }
                }
                .onFailure {
                    // Unknown, not absent. Change nothing and try again on the next launch.
                    Log.w(TAG, "Could not read opt-in preference; skipping auto opt-in", it)
                }
            loadLeaderboard(userId)
            _leaderboardLoading.value = false
        }
    }

    /** Surfaced when the switch is tapped before Firebase auth has produced a uid to write against. */
    fun showLeaderboardSignInRequired() {
        _errorMessage.value = "Please sign in to change your leaderboard preference."
    }

    fun setLeaderboardOptIn(userId: String, enabled: Boolean) {
        // Ignore a second tap while a write is still in flight rather than disabling the control.
        if (_leaderboardOptInBusy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _errorMessage.value = null
            _leaderboardOptInBusy.value = true
            // Move the switch immediately. `optIn` aggregates the user's entire Firestore session
            // history before it writes the flag, so waiting for success left the control sitting in
            // its old position for seconds — it read as an ignored tap. Reverted below if the write
            // fails, which is the only case where the switch should snap back.
            val previous = _leaderboardOptedIn.value
            _leaderboardOptedIn.value = enabled
            val result = if (enabled) leaderboardService.optIn(userId) else leaderboardService.optOut(userId)
            result.onFailure { e ->
                _leaderboardOptedIn.value = previous
                val msg = (e.message ?: "Unknown error").lowercase()
                _errorMessage.value = when {
                    msg.contains("permission") || msg.contains("insufficient") ->
                        "Permission denied. Ensure Firestore rules are deployed (firebase deploy --only firestore)."
                    msg.contains("sign in") || msg.contains("authenticated") ->
                        "Please sign in and try again."
                    msg.contains("network") || msg.contains("unavailable") ->
                        "Network error. Check your connection and try again."
                    else -> "Failed to update leaderboard: ${e.message?.take(60) ?: "Unknown error"}"
                }
            }
            _leaderboardOptInBusy.value = false
            if (enabled) loadLeaderboard(userId)
        }
    }

    fun setLeaderboardPeriod(period: LeaderboardPeriod) {
        _leaderboardSessionDayKey.value = null
        _leaderboardPeriod.value = period
    }

    /** All-time chip: turn on all-time scores, or turn off and return to a rolling window. */
    fun toggleLeaderboardAllTime() {
        _leaderboardSessionDayKey.value = null
        _leaderboardPeriod.value = if (_leaderboardPeriod.value is LeaderboardPeriod.AllTime) {
            LeaderboardPeriod.ThisWeek
        } else {
            LeaderboardPeriod.AllTime
        }
    }

    fun setLeaderboardRollingDays(days: Int) {
        _leaderboardSessionDayKey.value = null
        _leaderboardPeriod.value = LeaderboardPeriod.Rolling(days.coerceIn(1, 366))
    }

    fun setLeaderboardSingleDay(dateKey: String) {
        _leaderboardSessionDayKey.value = dateKey
        if (_leaderboardPeriod.value is LeaderboardPeriod.AllTime) {
            _leaderboardPeriod.value = LeaderboardPeriod.ThisWeek
        }
    }

    fun setLeaderboardCategory(category: LeaderboardCategory) {
        _leaderboardCategory.value = category
    }

    fun loadLeaderboard(_userId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _leaderboardLoading.value = true
            val dayKey = _leaderboardSessionDayKey.value
            val result = if (dayKey != null) {
                leaderboardService.fetchDailyLeaderboardTop(_leaderboardCategory.value, dayKey)
            } else {
                leaderboardService.fetchLeaderboard(_leaderboardCategory.value, _leaderboardPeriod.value)
            }
            result.onSuccess { _leaderboardEntries.value = it }
                .onFailure { _leaderboardEntries.value = emptyList() }
            _leaderboardLoading.value = false
        }
    }

    /**
     * Add, replace, or remove emoji reaction on a leaderboard entry.
     * Emoji codes: fire, sweat, clap, joy, thumbs, cool.
     * If current user already gave the same emoji, removes it. Otherwise adds/replaces.
     */
    fun reactToEntry(currentUserId: String, targetUserId: String, emojiCode: String) {
        if (currentUserId == targetUserId) return
        viewModelScope.launch(Dispatchers.IO) {
            val entry = _leaderboardEntries.value.find { it.userId == targetUserId } ?: return@launch
            val currentReaction = entry.reactions[currentUserId]
            val newEmoji = if (currentReaction == emojiCode) null else emojiCode
            leaderboardService.setReaction(targetUserId, currentUserId, newEmoji)
                .onSuccess { loadLeaderboard(currentUserId) }
        }
    }

    /**
     * Refresh own leaderboard entry (if opted in) and then reload the list.
     *
     * Always reloads the list so users that have NOT opted in still see fresh
     * data for everyone else. The own-entry refresh is the critical part for
     * the user themselves: without it, the leaderboard doc is only ever
     * written once at opt-in time, leaving people stuck at 0.00 kg if they
     * opted in before any sessions had synced to Firestore.
     */
    fun refreshLeaderboardIfOptedIn(userId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (leaderboardService.isOptedIn(userId)) {
                    leaderboardService.updateLeaderboardEntry(userId)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "refreshLeaderboardIfOptedIn failed", t)
            }
            loadLeaderboard(userId)
        }
    }

    private companion object {
        private const val TAG = "ProfileViewModel"
    }
}
