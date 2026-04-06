# 🔧 Complete Integration Guide

## 📝 Implementation Summary

I'm now integrating SessionManager with all fragments. Here's what's being updated:

### **Files Being Modified:**
1. ✅ `TrackerFragment.kt` - Auto-save, session resume, location tracking
2. ✅ `AnalyticsFragment.kt` - Session history list
3. ✅ `ProfileFragment.kt` - Enhanced statistics, export button
4. ✅ `fragment_analytics.xml` - Session history UI
5. ✅ `fragment_profile.xml` - Export button

---

## 🚀 TrackerFragment Updates

### **Key Changes:**

#### 1. **Add SessionManager**
```kotlin
// Add to class properties
private lateinit var sessionManager: SessionManager
private var currentSessionId: String = ""
private var maxSpeed: Double = 0.0
private var startLocation: LocationPoint? = null
private var endLocation: LocationPoint? = null
```

#### 2. **Initialize in onViewCreated**
```kotlin
sessionManager = SessionManager.getInstance(requireContext())

// Check for active session
checkForActiveSession()
```

#### 3. **Check for Active Session**
```kotlin
private fun checkForActiveSession() {
    val activeSession = sessionManager.getActiveSession()
    if (activeSession != null && activeSession.status != SessionStatus.COMPLETED) {
        // Prompt user to resume
        AlertDialog.Builder(requireContext())
            .setTitle("Resume Session?")
            .setMessage("You have an unfinished session. Continue tracking?")
            .setPositiveButton("Resume") { _, _ ->
                resumeSession(activeSession)
            }
            .setNegativeButton("Start Fresh") { _, _ ->
                sessionManager.clearActiveSession()
            }
            .show()
    }
}
```

#### 4. **Resume Session**
```kotlin
private fun resumeSession(session: Session) {
    currentSessionId = session.id
    totalDistance = session.distance * 1000 // convert back to meters
    elapsedTime = session.duration * 1000 // convert back to ms
    pausedTime = session.pauseDuration * 1000
    stepCount = session.steps
    maxSpeed = session.maxSpeed
    startLocation = session.startLocation
    
    when (session.status) {
        SessionStatus.PAUSED -> {
            trackingState = TrackingState.PAUSED
            // Update UI for paused state
        }
        SessionStatus.ACTIVE -> {
            startTracking() // Resume tracking
        }
        else -> {}
    }
}
```

#### 5. **Track Start Location**
```kotlin
// In locationCallback, first location received:
if (startLocation == null && location.accuracy < 50) {
    startLocation = LocationPoint(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracy = location.accuracy,
        timestamp = location.time
    )
}
```

#### 6. **Track Max Speed**
```kotlin
// In updateActivityType method
if (currentSpeed > maxSpeed) {
    maxSpeed = currentSpeed
}
```

#### 7. **Auto-save on Pause**
```kotlin
private fun pauseTracking() {
    if (trackingState != TrackingState.TRACKING) return
    
    trackingState = TrackingState.PAUSED
    pauseStartTime = System.currentTimeMillis()
    
    // Create session object
    val session = createSessionObject(SessionStatus.PAUSED)
    
    // Save locally
    sessionManager.saveActiveSession(session)
    
    // Save to Firestore
    lifecycleScope.launch {
        sessionManager.saveSession(session)
    }
    
    // Update UI...
}
```

#### 8. **Auto-save on Resume**
```kotlin
private fun resumeTracking() {
    if (trackingState != TrackingState.PAUSED) return
    
    trackingState = TrackingState.TRACKING
    pausedTime += System.currentTimeMillis() - pauseStartTime
    
    // Save updated session
    val session = createSessionObject(SessionStatus.ACTIVE)
    sessionManager.saveActiveSession(session)
    
    lifecycleScope.launch {
        sessionManager.saveSession(session)
    }
    
    // Restart location updates...
}
```

#### 9. **Save on Stop**
```kotlin
private fun stopTracking() {
    if (trackingState == TrackingState.STOPPED) return
    
    trackingState = TrackingState.STOPPED
    
    // Set end location
    lastLocation?.let {
        endLocation = LocationPoint(
            latitude = it.latitude,
            longitude = it.longitude,
            accuracy = it.accuracy,
            timestamp = it.time
        )
    }
    
    // Create final session
    val session = createSessionObject(SessionStatus.COMPLETED)
    
    // Clear active session
    sessionManager.clearActiveSession()
    
    // Stop updates...
    
    // Show summary
    showSessionSummary(session)
}
```

#### 10. **Create Session Object**
```kotlin
private fun createSessionObject(status: SessionStatus): Session {
    val distanceKm = totalDistance / 1000.0
    val durationSec = elapsedTime / 1000
    val pauseSec = pausedTime / 1000
    val avgSpeed = if (durationSec > 0) (totalDistance / durationSec) * 3.6 else 0.0
    
    val carbonSaved = when {
        currentSpeed < 15.0 -> distanceKm * 0.21
        currentSpeed < 25.0 -> distanceKm * 0.19
        else -> distanceKm * 0.05
    }
    
    return Session(
        id = currentSessionId,
        userId = auth.currentUser?.uid ?: "",
        distance = distanceKm,
        duration = durationSec,
        pauseDuration = pauseSec,
        avgSpeed = avgSpeed,
        maxSpeed = maxSpeed,
        carbonFootprint = carbonSaved,
        activityType = currentActivity,
        steps = stepCount,
        startTime = startTime,
        endTime = System.currentTimeMillis(),
        startLocation = startLocation,
        endLocation = endLocation,
        status = status
    )
}
```

#### 11. **Updated Session Summary**
```kotlin
private fun showSessionSummary(session: Session) {
    val summary = """
        Session Complete! 🎉
        
        📏 Distance: ${String.format("%.2f", session.distance)} km
        ⏱️ Time: ${formatDuration(session.duration)}
        🏃 Avg Speed: ${String.format("%.1f", session.avgSpeed)} km/h
        ⚡ Max Speed: ${String.format("%.1f", session.maxSpeed)} km/h
        🌱 CO₂ Saved: ${String.format("%.2f", session.carbonFootprint)} kg
        👟 Steps: ${session.steps}
        🎯 Activity: ${session.activityType}
        
        Great job!
    """.trimIndent()
    
    AlertDialog.Builder(requireContext())
        .setTitle("Session Summary")
        .setMessage(summary)
        .setPositiveButton("Save Session") { _, _ ->
            lifecycleScope.launch {
                sessionManager.saveSession(session)
                Toast.makeText(requireContext(), "Session saved!", Toast.LENGTH_SHORT).show()
                resetSession()
            }
        }
        .setNegativeButton("Discard") { _, _ ->
            resetSession()
        }
        .setCancelable(false)
        .show()
}
```

---

## 📊 AnalyticsFragment Updates

### **Key Changes:**

#### 1. **Add SessionManager and UI**
```kotlin
private lateinit var sessionManager: SessionManager
private val sessionList = mutableListOf<Session>()
private lateinit var adapter: SessionAdapter
```

#### 2. **Load Sessions in onViewCreated**
```kotlin
sessionManager = SessionManager.getInstance(requireContext())
loadSessions()
```

#### 3. **Load Sessions Method**
```kotlin
private fun loadSessions() {
    lifecycleScope.launch {
        try {
            binding.progressBar.visibility = View.VISIBLE
            
            val result = sessionManager.getAllSessions(100)
            if (result.isSuccess) {
                sessionList.clear()
                sessionList.addAll(result.getOrNull() ?: emptyList())
                updateSessionList()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Failed to load sessions",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e("AnalyticsFragment", "Error loading sessions", e)
        } finally {
            binding.progressBar.visibility = View.GONE
        }
    }
}
```

#### 4. **Update Session List UI**
```kotlin
private fun updateSessionList() {
    if (sessionList.isEmpty()) {
        binding.tvNoSessions.visibility = View.VISIBLE
        binding.recyclerViewSessions.visibility = View.GONE
        return
    }
    
    binding.tvNoSessions.visibility = View.GONE
    binding.recyclerViewSessions.visibility = View.VISIBLE
    
    // Group by date and display
    val grouped = sessionList.groupBy { session ->
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(session.startTime))
    }
    
    // Update adapter
    adapter.updateSessions(grouped)
}
```

---

## 👤 ProfileFragment Updates

### **Key Changes:**

#### 1. **Add SessionManager**
```kotlin
private lateinit var sessionManager: SessionManager
```

#### 2. **Load Enhanced Statistics**
```kotlin
private fun loadEnhancedStats() {
    lifecycleScope.launch {
        try {
            binding.progressBar.visibility = View.VISIBLE
            
            val result = sessionManager.getUserStats()
            if (result.isSuccess) {
                val stats = result.getOrNull()
                if (stats != null) {
                    updateStatsUI(stats)
                }
            }
        } catch (e: Exception) {
            Log.e("ProfileFragment", "Error loading stats", e)
        } finally {
            binding.progressBar.visibility = View.GONE
        }
    }
}
```

#### 3. **Update Stats UI**
```kotlin
private fun updateStatsUI(stats: UserStats) {
    binding.apply {
        tvTotalDistance.text = String.format("%.1f km", stats.totalDistance)
        tvTotalDuration.text = formatDuration(stats.totalDuration)
        tvTotalSessions.text = "${stats.totalSessions}"
        tvTotalCo2Saved.text = String.format("%.1f kg", stats.totalCO2Saved)
        tvTotalSteps.text = String.format("%,d", stats.totalSteps)
        tvLongestSession.text = String.format("%.1f km", stats.longestSession)
        tvLongestDuration.text = formatDuration(stats.longestDuration)
        tvFastestSpeed.text = String.format("%.1f km/h", stats.fastestSpeed)
    }
}
```

#### 4. **Add Export Button**
```kotlin
binding.btnExport.setOnClickListener {
    exportSessions()
}

private fun exportSessions() {
    lifecycleScope.launch {
        try {
            val csv = sessionManager.exportSessionsToCSV()
            shareCSV(csv)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Export failed: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

private fun shareCSV(csv: String) {
    val file = File(requireContext().cacheDir, "sessions_export.csv")
    file.writeText(csv)
    
    val uri = FileProvider.getUriForFile(
        requireContext(),
        "${requireContext().packageName}.provider",
        file
    )
    
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    
    startActivity(Intent.createChooser(intent, "Export Sessions"))
}
```

---

## 🔄 Next Steps

I'll now update all these files with the complete implementations. This will take a few minutes.

**After completion, you'll have:**
1. ✅ Auto-save on pause/resume/stop
2. ✅ Session resume after app restart
3. ✅ Session history list in Analytics
4. ✅ Enhanced statistics in Profile
5. ✅ Export to CSV functionality
6. ✅ Offline sync

**Ready to apply all changes?**









