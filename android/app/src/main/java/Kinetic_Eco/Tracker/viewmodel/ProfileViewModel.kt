package Kinetic_Eco.Tracker.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import Kinetic_Eco.Tracker.data.LeaderboardCategory
import Kinetic_Eco.Tracker.data.LeaderboardEntry
import Kinetic_Eco.Tracker.data.UserProfile
import Kinetic_Eco.Tracker.services.LeaderboardPeriod
import Kinetic_Eco.Tracker.services.LeaderboardService
import Kinetic_Eco.Tracker.services.UserProfileService

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val profileService = UserProfileService()
    private val leaderboardService = LeaderboardService.getInstance()

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

    private val _leaderboardPeriod = MutableStateFlow<LeaderboardPeriod>(LeaderboardPeriod.AllTime)
    val leaderboardPeriod: StateFlow<LeaderboardPeriod> = _leaderboardPeriod.asStateFlow()

    private val _leaderboardCategory = MutableStateFlow(LeaderboardCategory.COMBINED)
    val leaderboardCategory: StateFlow<LeaderboardCategory> = _leaderboardCategory.asStateFlow()

    /** yyyy-MM-dd session start date; when set, leaderboard uses global daily #1 for that day. */
    private val _leaderboardSessionDayKey = MutableStateFlow<String?>(null)
    val leaderboardSessionDayKey: StateFlow<String?> = _leaderboardSessionDayKey.asStateFlow()

    private val _leaderboardLoading = MutableStateFlow(false)
    val leaderboardLoading: StateFlow<Boolean> = _leaderboardLoading.asStateFlow()

    fun loadProfile(userId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _profile.value = profileService.getProfile(userId)
            _isLoading.value = false
        }
    }

    fun saveDisplayName(userId: String, displayName: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            profileService.saveDisplayName(userId, displayName?.trim()?.takeIf { it.isNotEmpty() })
                .onSuccess {
                    _profile.value = _profile.value?.copy(displayName = displayName?.trim()?.takeIf { it.isNotEmpty() })
                        ?: UserProfile(displayName = displayName?.trim()?.takeIf { it.isNotEmpty() })
                }
                .onFailure { _errorMessage.value = "Failed to save name" }
            _isLoading.value = false
        }
    }

    fun uploadPhoto(userId: String, uri: Uri) {
        viewModelScope.launch {
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
        viewModelScope.launch {
            _leaderboardOptedIn.value = leaderboardService.isOptedIn(userId)
        }
    }

    fun setLeaderboardOptIn(userId: String, enabled: Boolean) {
        viewModelScope.launch {
            _errorMessage.value = null
            _leaderboardLoading.value = true
            val result = if (enabled) leaderboardService.optIn(userId) else leaderboardService.optOut(userId)
            result.onSuccess { _leaderboardOptedIn.value = enabled }
            result.onFailure { e ->
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
            _leaderboardLoading.value = false
            if (enabled) loadLeaderboard(userId)
        }
    }

    fun setLeaderboardPeriod(period: LeaderboardPeriod) {
        _leaderboardSessionDayKey.value = null
        _leaderboardPeriod.value = period
    }

    fun setLeaderboardRollingDays(days: Int) {
        _leaderboardSessionDayKey.value = null
        _leaderboardPeriod.value = LeaderboardPeriod.Rolling(days.coerceIn(1, 366))
    }

    fun setLeaderboardSingleDay(dateKey: String) {
        _leaderboardSessionDayKey.value = dateKey
        if (_leaderboardPeriod.value is LeaderboardPeriod.AllTime) {
            _leaderboardPeriod.value = LeaderboardPeriod.Rolling(30)
        }
    }

    fun setLeaderboardCategory(category: LeaderboardCategory) {
        _leaderboardCategory.value = category
    }

    fun loadLeaderboard(_userId: String) {
        viewModelScope.launch {
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
        viewModelScope.launch {
            val entry = _leaderboardEntries.value.find { it.userId == targetUserId } ?: return@launch
            val currentReaction = entry.reactions[currentUserId]
            val newEmoji = if (currentReaction == emojiCode) null else emojiCode
            leaderboardService.setReaction(targetUserId, currentUserId, newEmoji)
                .onSuccess { loadLeaderboard(currentUserId) }
        }
    }

    /** Refresh leaderboard entry after session sync (if opted in). */
    fun refreshLeaderboardIfOptedIn(userId: String) {
        viewModelScope.launch {
            try {
                if (leaderboardService.isOptedIn(userId)) {
                    leaderboardService.updateLeaderboardEntry(userId)
                    loadLeaderboard(userId)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "refreshLeaderboardIfOptedIn failed", t)
            }
        }
    }

    private companion object {
        private const val TAG = "ProfileViewModel"
    }
}
