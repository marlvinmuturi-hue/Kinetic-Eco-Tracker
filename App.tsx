import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Tracker } from './components/Tracker';
import { Analytics } from './components/Analytics';
import { Profile } from './components/Profile';
import { LoginForm } from './components/LoginForm';
import { SettingsPage } from './components/SettingsPage';
import { FloatingMenu } from './components/FloatingMenu';
import { FeedbackPage } from './components/FeedbackPage';
import { ActivitySelector } from './components/ActivitySelector';
import { FloatingActivityButton } from './components/FloatingActivityButton';
import { ConfirmationModal } from './components/ConfirmationModal';
import { ActivityType, SessionStats, GeoPosition, UserProfile, UnitSystem } from './types';
import { SPEED_THRESHOLDS, CO2_FACTORS, CALORIE_FACTORS_PER_HOUR } from './constants';
import { CalorieEngine } from './services/calorieEngine';
import { MapPin } from 'lucide-react';
import { authenticateOrCreateProfile, getActiveUserEmail, setActiveUserEmail, loadProfile, logoutUser, saveSessionForUser, syncSocialProfile } from './services/profileService';
import { APP_CONFIG } from './appConfig';
import { mobileGPS } from './services/mobileGPSService';
import { webSensors } from './services/webSensorService';
import { getBackgroundService } from './services/backgroundService';
import { mediaSessionService } from './services/mediaSessionService';
import { signInWithEmail, signUpWithEmail, signInWithGoogle, resetPassword, signOutUser, onAuthStateChange, getCurrentUser, getUserProfile, handleRedirectResult } from './services/authService';
import { showKmNotification, getKmNotificationsEnabled } from './services/kmNotificationService';

const UNIT_STORAGE_KEY = 'kinetic_unit_preference';
const ACTIVE_SESSION_KEY = 'kinetic_active_session';

/** Raw summed GPS segments ~15–20% long vs true path; matches Android TrackingService. */
const GPS_PATH_DISTANCE_SCALE = 0.83;

interface PersistedSessionState {
  isTracking: boolean;
  sessionDuration: number;
  sessionDistance: number;
  currentActivity: ActivityType;
  stats: SessionStats;
  lastUpdateTime: number;
  lastPosition: GeoPosition | null;
}

const App: React.FC = () => {
  // State
  const [view, setView] = useState<'TRACKER' | 'ANALYTICS' | 'PROFILE' | 'SETTINGS' | 'FEEDBACK'>('TRACKER');
  const [isTracking, setIsTracking] = useState(false);
  const [permissionGranted, setPermissionGranted] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [activeUser, setActiveUser] = useState<UserProfile | null>(null);
  const [authError, setAuthError] = useState<string | null>(null);
  const [isInitializing, setIsInitializing] = useState(true);
  const [unitSystem, setUnitSystem] = useState<UnitSystem>('METRIC');
  const [menuOpen, setMenuOpen] = useState(false);
  
  // Manual activity selection
  const [activitySelectorOpen, setActivitySelectorOpen] = useState(false);
  const [showStopConfirmation, setShowStopConfirmation] = useState(false);
  const [isSavingSession, setIsSavingSession] = useState(false);
  const [manualActivityMode, setManualActivityMode] = useState<ActivityType | 'AUTO'>('AUTO');

  // Real-time tracking data
  const [currentSpeed, setCurrentSpeed] = useState(0); // m/s
  const [currentCoords, setCurrentCoords] = useState<GeoPosition | null>(null);
  const [currentActivity, setCurrentActivity] = useState<ActivityType>(ActivityType.IDLE);
  
  // Speed smoothing and validation
  const smoothedSpeedRef = useRef<number>(0);
  const speedHistoryRef = useRef<number[]>([]);
  
  // Elevation tracking
  const lastAltitudeRef = useRef<number | null>(null);
  const sessionElevationGainRef = useRef<number>(0);
  const sessionElevationLossRef = useRef<number>(0);
  
  // Session accumulation
  const [sessionDuration, setSessionDuration] = useState(0); // seconds
  const [sessionDistance, setSessionDistance] = useState(0); // meters
  
  // Detailed stats for analytics
  const statsRef = useRef<SessionStats>({
    totalDuration: 0,
    totalDistance: 0,
    caloriesBurned: 0,
    co2Emissions: 0,
    co2Conserved: 0,
    segments: [],
    breakdown: {
      [ActivityType.IDLE]: { time: 0, distance: 0 },
      [ActivityType.WALKING]: { time: 0, distance: 0 },
      [ActivityType.RUNNING]: { time: 0, distance: 0 },
      [ActivityType.CYCLING]: { time: 0, distance: 0 },
      [ActivityType.DRIVING]: { time: 0, distance: 0 },
      [ActivityType.ELECTRIC_VEHICLE]: { time: 0, distance: 0 },
      [ActivityType.FLYING]: { time: 0, distance: 0 },
    }
  });

  // Ref for manualActivityMode - GPS callback uses this to avoid stale closure
  const manualActivityModeRef = useRef<ActivityType | 'AUTO'>('AUTO');
  
  // Watch ID for geolocation
  const watchId = useRef<number | null>(null);
  const lastUpdateRef = useRef<number>(Date.now());
  const lastPositionRef = useRef<GeoPosition | null>(null);
  const isTrackingRef = useRef<boolean>(false);
  
  // Activity debouncing - prevent flickering
  const activityHistory = useRef<ActivityType[]>([]);
  const lastStableActivity = useRef<ActivityType>(ActivityType.IDLE);
  
  // Activity persistence tracking - how long current activity has been ongoing
  const activityStartTimeRef = useRef<number>(Date.now());
  const activityDurationSeconds = useRef<number>(0);

  // Kilometer milestone notification tracking
  const lastNotifiedKmRef = useRef<number>(0);
  const durationAtLastKmRef = useRef<number>(0);
  
  // Dedicated timer for duration tracking
  const timerIntervalRef = useRef<number | null>(null);
  const timerStartTimeRef = useRef<number>(0);

  // Sensor state for hybrid calculation
  const sensorDataRef = useRef({
    acceleration: 0,
    rotationRate: 0,
    lastStepTime: 0,
    stepCount: 0
  });
  
  // Helper functions for state persistence
  const saveActiveSession = useCallback(() => {
    if (!isTrackingRef.current) {
      // Clear saved session if not tracking
      try {
        localStorage.removeItem(ACTIVE_SESSION_KEY);
        console.log('Cleared active session from storage');
      } catch (error) {
        console.error('Failed to clear session storage:', error);
      }
      return;
    }

    try {
      const sessionState: PersistedSessionState = {
        isTracking: true,
        sessionDuration: sessionDuration,
        sessionDistance: sessionDistance,
        currentActivity: currentActivity,
        stats: statsRef.current,
        lastUpdateTime: lastUpdateRef.current,
        lastPosition: lastPositionRef.current,
      };
      localStorage.setItem(ACTIVE_SESSION_KEY, JSON.stringify(sessionState));
      console.log('Active session saved to storage:', {
        duration: sessionDuration.toFixed(2),
        distance: sessionDistance.toFixed(2)
      });
    } catch (error) {
      console.error('Failed to save session state:', error);
    }
  }, [sessionDuration, sessionDistance, currentActivity]);

  const restoreActiveSession = useCallback(() => {
    try {
      const savedState = localStorage.getItem(ACTIVE_SESSION_KEY);
      if (!savedState) {
        console.log('No active session to restore');
        return null;
      }

      const sessionState: PersistedSessionState = JSON.parse(savedState);
      console.log('Restoring active session from storage:', {
        duration: sessionState.sessionDuration.toFixed(2),
        distance: sessionState.sessionDistance.toFixed(2)
      });
      return sessionState;
    } catch (error) {
      console.error('Failed to restore session state:', error);
      return null;
    }
  }, []);

  // Background service for wake lock and visibility
  const backgroundServiceRef = useRef(getBackgroundService());

  // Update background service callbacks when saveActiveSession changes
  useEffect(() => {
    backgroundServiceRef.current.updateCallbacks({
      onVisibilityChange: (isVisible) => {
        console.log(`Page is now ${isVisible ? 'visible' : 'hidden'}`);
        if (!isVisible && isTrackingRef.current) {
          // Save state when going to background
          saveActiveSession();
        }
        if (isVisible && isTrackingRef.current) {
          // Re-acquire wake lock when page becomes visible again
          backgroundServiceRef.current.reacquireWakeLockIfNeeded();
        }
      },
      onWakeLockAcquired: () => {
        console.log('Wake Lock acquired - tracking will continue in background');
      },
      onWakeLockReleased: () => {
        console.warn('Wake Lock released - device may sleep');
        // Try to re-acquire if still tracking
        if (isTrackingRef.current) {
          setTimeout(() => {
            backgroundServiceRef.current.requestWakeLock();
          }, 1000);
        }
      },
      onWakeLockError: (error) => {
        console.warn('Wake Lock error (non-critical):', error);
        // Wake Lock is not critical, tracking can continue without it
      }
    });
  }, [saveActiveSession]);

  // Dedicated timer for duration tracking - independent of GPS updates
  useEffect(() => {
    // Clear any existing timer
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = null;
    }

    if (!isTracking) {
      return;
    }

    // Record start time
    timerStartTimeRef.current = Date.now();
    const initialDuration = sessionDuration;
    console.log('Starting dedicated timer with initial duration:', initialDuration);

    // Reset kilometer milestone tracking only for fresh session (not when continuing)
    if (sessionDuration === 0 && sessionDistance === 0) {
      lastNotifiedKmRef.current = 0;
      durationAtLastKmRef.current = 0;
    }

    // Update duration every second
    timerIntervalRef.current = window.setInterval(() => {
      if (isTrackingRef.current) {
        const elapsed = Math.floor((Date.now() - timerStartTimeRef.current) / 1000);
        const newDuration = initialDuration + elapsed;
        
        setSessionDuration(newDuration);
        
        // Update stats ref
        statsRef.current.totalDuration = newDuration;
        
        // Update breakdown for current activity
        const activity = lastStableActivity.current;
        const activityDuration = statsRef.current.breakdown[activity].time + 1;
        statsRef.current.breakdown[activity].time = activityDuration;
        
        // Calculate calories using advanced CalorieEngine
        // Uses speed, elevation, and user profile for accurate calculations
        const userProfile = profile?.physicalProfile;
        const currentSpeedMps = currentSpeed > 0 ? currentSpeed : null;
        
        const kcalPerSecond = CalorieEngine.calculateCalories(
          activity,
          1, // 1 second
          currentSpeedMps,
          0, // Elevation handled per GPS update
          0,
          userProfile
        );
        
        statsRef.current.caloriesBurned += kcalPerSecond;
      }
    }, 1000);

    return () => {
      if (timerIntervalRef.current) {
        clearInterval(timerIntervalRef.current);
        timerIntervalRef.current = null;
      }
    };
  }, [isTracking, sessionDuration]);

  // Layer 2: Enforce speed-activity consistency using per-activity thresholds (speed only, altitude not used)
  const enforceActivitySpeedConsistency = (activity: ActivityType, speed: number, _altitude: number): ActivityType => {
    const bounds = getActivitySpeedBounds(activity);
    if (!bounds) return activity;
    const [lower, upper] = bounds;
    const tooFast = speed >= upper;
    const tooSlow = speed < lower;
    if (!tooFast && !tooSlow) return activity;
    return reclassifyBySpeed(speed);
  };

  const getActivitySpeedBounds = (activity: ActivityType): [number, number] | null => {
    switch (activity) {
      case ActivityType.IDLE: return [0, SPEED_THRESHOLDS.WALKING_MIN];
      case ActivityType.WALKING: return [SPEED_THRESHOLDS.WALKING_MIN, SPEED_THRESHOLDS.RUNNING_MIN];
      case ActivityType.RUNNING: return [SPEED_THRESHOLDS.RUNNING_MIN, SPEED_THRESHOLDS.CYCLING_MIN];
      case ActivityType.CYCLING: return [SPEED_THRESHOLDS.CYCLING_MIN, SPEED_THRESHOLDS.DRIVING_MIN];
      case ActivityType.DRIVING:
      case ActivityType.ELECTRIC_VEHICLE: return [SPEED_THRESHOLDS.DRIVING_MIN, SPEED_THRESHOLDS.FLYING_MIN];
      case ActivityType.FLYING: return [SPEED_THRESHOLDS.FLYING_MIN, Infinity];
      default: return null;
    }
  };

  const reclassifyBySpeed = (speed: number): ActivityType => {
    if (speed < SPEED_THRESHOLDS.WALKING_MIN) return ActivityType.IDLE;
    if (speed < SPEED_THRESHOLDS.RUNNING_MIN) return ActivityType.WALKING;
    if (speed < SPEED_THRESHOLDS.CYCLING_MIN) return ActivityType.RUNNING;
    if (speed < SPEED_THRESHOLDS.DRIVING_MIN) return ActivityType.CYCLING;
    if (speed < SPEED_THRESHOLDS.FLYING_MIN) return ActivityType.DRIVING;
    return ActivityType.FLYING;
  };

  // Determine Activity based on Speed (m/s) - Enhanced with altitude awareness
  const classifyActivity = (speed: number): ActivityType => {
    const altitude = currentCoords?.altitude || 0;
    let baseActivity = reclassifyBySpeed(speed);
    // OVERRIDE: Never classify as IDLE if speed > 3 km/h
    if (baseActivity === ActivityType.IDLE && speed >= SPEED_THRESHOLDS.IDLE_OVERRIDE) {
      baseActivity = ActivityType.WALKING;
    }
    return enforceActivitySpeedConsistency(baseActivity, speed, altitude);
  };

  // Get most common activity from history (for debouncing)
  const getMostCommonActivity = (activities: ActivityType[]): ActivityType => {
    if (activities.length === 0) return ActivityType.IDLE;
    
    const counts: Record<ActivityType, number> = {
      [ActivityType.IDLE]: 0,
      [ActivityType.WALKING]: 0,
      [ActivityType.RUNNING]: 0,
      [ActivityType.CYCLING]: 0,
      [ActivityType.DRIVING]: 0,
      [ActivityType.ELECTRIC_VEHICLE]: 0,
      [ActivityType.FLYING]: 0,
    };
    
    activities.forEach(activity => {
      counts[activity]++;
    });
    
    let maxCount = 0;
    let mostCommon = ActivityType.IDLE;
    
    (Object.keys(counts) as ActivityType[]).forEach(activity => {
      if (counts[activity] > maxCount) {
        maxCount = counts[activity];
        mostCommon = activity;
      }
    });
    
    return mostCommon;
  };

  // Update activity with debouncing to prevent flickering
  const updateActivityWithDebounce = (rawActivity: ActivityType) => {
    // Add to history
    activityHistory.current.push(rawActivity);
    
    // Keep only last 3 readings (faster response time)
    if (activityHistory.current.length > 3) {
      activityHistory.current.shift();
    }
    
    // Calculate how long the current activity has been ongoing
    const currentTime = Date.now();
    activityDurationSeconds.current = (currentTime - activityStartTimeRef.current) / 1000;
    
    // Only update if we have enough readings and they're consistent
    if (activityHistory.current.length >= 2) {
      const mostCommon = getMostCommonActivity(activityHistory.current);
      const consistentCount = activityHistory.current.filter(a => a === mostCommon).length;
      
      // Don't switch if the detected activity is the same as current
      if (mostCommon === lastStableActivity.current) {
        return;
      }
      
      const currentActivity = lastStableActivity.current;
      
      // Calculate activity change magnitude for adaptive debouncing
      const isSignificantChange = (
        (currentActivity === ActivityType.IDLE && mostCommon !== ActivityType.IDLE) ||
        (currentActivity !== ActivityType.IDLE && mostCommon === ActivityType.IDLE) ||
        Math.abs(getActivitySpeed(currentActivity) - getActivitySpeed(mostCommon)) > 2
      );
      
      // Check if activities are compatible (e.g., driving -> running is not compatible)
      const isCompatible = areActivitiesCompatible(currentActivity, mostCommon);
      
      // Activity Persistence Logic: The longer an activity continues, the harder to switch
      let requiredConsistency = 2; // Base requirement
      
      // Apply persistence threshold based on how long the activity has been ongoing
      if (activityDurationSeconds.current > 120) { // 2+ minutes
        // Extended activity: require ALL 3 readings to be consistent
        requiredConsistency = 3;
        console.log(`Activity persistence: ${currentActivity} ongoing for ${activityDurationSeconds.current.toFixed(0)}s - requiring 3/3 consistency`);
      } else if (activityDurationSeconds.current > 60) { // 1-2 minutes
        // Established activity: require higher consistency
        requiredConsistency = isSignificantChange ? 2 : 3;
      } else if (activityDurationSeconds.current > 30) { // 30-60 seconds
        // New activity settling in: moderate requirements
        requiredConsistency = isSignificantChange ? 2 : 3;
      } else {
        // Fresh activity (< 30 seconds): use standard adaptive logic
        requiredConsistency = isSignificantChange ? 2 : 3;
      }
      
      // Incompatible transitions require ALL readings to be consistent, regardless of duration
      if (!isCompatible) {
        requiredConsistency = 3;
        console.log(`Incompatible transition: ${currentActivity} -> ${mostCommon} - requiring 3/3 consistency`);
      }
      
      // Check if we have enough consistent readings to switch
      if (consistentCount >= requiredConsistency) {
        // Reset activity start time when switching
        activityStartTimeRef.current = currentTime;
        activityDurationSeconds.current = 0;
        
        lastStableActivity.current = mostCommon;
        setCurrentActivity(mostCommon);
        console.log(`Activity changed: ${currentActivity} -> ${mostCommon} (${consistentCount}/${activityHistory.current.length} consistent, duration: ${((currentTime - activityStartTimeRef.current) / 1000).toFixed(0)}s, compatible: ${isCompatible})`);
      } else {
        console.log(`Activity change rejected: ${currentActivity} -> ${mostCommon} (${consistentCount}/${requiredConsistency} required, duration: ${activityDurationSeconds.current.toFixed(0)}s, compatible: ${isCompatible})`);
      }
    }
  };
  
  // Helper to get typical speed for each activity (for change magnitude calculation)
  const getActivitySpeed = (activity: ActivityType): number => {
    switch (activity) {
      case ActivityType.IDLE: return 0;
      case ActivityType.WALKING: return 1.4; // ~5 km/h
      case ActivityType.RUNNING: return 3.5; // ~12.5 km/h
      case ActivityType.CYCLING: return 5.5; // ~20 km/h
      case ActivityType.DRIVING: return 15; // ~54 km/h
      case ActivityType.ELECTRIC_VEHICLE: return 15;
      case ActivityType.FLYING: return 150; // ~540 km/h
      default: return 0;
    }
  };

  // Check if two activities are compatible (likely to transition between)
  const areActivitiesCompatible = (from: ActivityType, to: ActivityType): boolean => {
    // Same activity is always compatible
    if (from === to) return true;
    
    // IDLE is compatible with everything (starting/stopping any activity)
    if (from === ActivityType.IDLE || to === ActivityType.IDLE) return true;
    
    // Define incompatible transitions (highly unlikely in real life)
    const incompatiblePairs: [ActivityType, ActivityType][] = [
      // Can't go from high-speed activities directly to low-speed without stopping
      [ActivityType.DRIVING, ActivityType.WALKING],
      [ActivityType.DRIVING, ActivityType.RUNNING],
      [ActivityType.ELECTRIC_VEHICLE, ActivityType.WALKING],
      [ActivityType.ELECTRIC_VEHICLE, ActivityType.RUNNING],
      [ActivityType.FLYING, ActivityType.WALKING],
      [ActivityType.FLYING, ActivityType.RUNNING],
      [ActivityType.FLYING, ActivityType.CYCLING],
      
      // Can't jump from cycling to driving without a transition
      [ActivityType.CYCLING, ActivityType.DRIVING],
      [ActivityType.CYCLING, ActivityType.ELECTRIC_VEHICLE],
      
      // Can't go from running to driving/cycling without stopping
      [ActivityType.RUNNING, ActivityType.DRIVING],
      [ActivityType.RUNNING, ActivityType.ELECTRIC_VEHICLE],
      [ActivityType.RUNNING, ActivityType.CYCLING],
    ];
    
    // Check if this pair is incompatible (check both directions)
    return !incompatiblePairs.some(([a, b]) => 
      (from === a && to === b) || (from === b && to === a)
    );
  };

  // Realistic maximum speeds (in m/s) to filter GPS errors
  const MAX_REALISTIC_SPEEDS = {
    [ActivityType.IDLE]: 0.5,           // 1.8 km/h
    [ActivityType.WALKING]: 2.0,        // 7.2 km/h (fast walk)
    [ActivityType.RUNNING]: 7.0,        // 25 km/h (world-class sprint)
    [ActivityType.CYCLING]: 16.7,       // 60 km/h (pro cyclist)
    [ActivityType.DRIVING]: 50.0,       // 180 km/h (reasonable max)
    [ActivityType.ELECTRIC_VEHICLE]: 50.0, // 180 km/h
    [ActivityType.FLYING]: 250.0        // 900 km/h (commercial flight)
  };

  // Validate and cap speed based on activity type - Enhanced for flying
  const validateSpeed = (speed: number, activity: ActivityType, altitude?: number): number => {
    // Dynamic max speed based on activity and altitude
    let maxSpeed = MAX_REALISTIC_SPEEDS[activity];
    
    if (activity === ActivityType.FLYING) {
      const isAtCruise = (altitude || 0) > 8000;
      maxSpeed = isAtCruise ? 333.0 : 150.0; // 1200 km/h cruise, 540 km/h takeoff/landing
    }
    
    if (speed > maxSpeed) {
      console.log(`Speed ${(speed * 3.6).toFixed(1)} km/h exceeds max for ${activity} (alt: ${altitude?.toFixed(0)}m), capping to ${(maxSpeed * 3.6).toFixed(1)} km/h`);
      return maxSpeed;
    }
    return speed;
  };

  // Apply EMA (Exponential Moving Average) smoothing to speed - Enhanced for flying
  const smoothSpeed = (newSpeed: number, activity: ActivityType = ActivityType.IDLE): number => {
    // Walking: small max change per fix — GPS position noise becomes huge m/s spikes
    const MAX_SPEED_CHANGE =
      activity === ActivityType.FLYING ? 30.0
      : (activity === ActivityType.WALKING || activity === ActivityType.IDLE) ? 2.5
      : 10.0;
    const alpha =
      activity === ActivityType.FLYING ? 0.4
      : (activity === ActivityType.WALKING || activity === ActivityType.IDLE) ? 0.35
      : 0.3;
    
    // First reading
    if (smoothedSpeedRef.current === 0) {
      smoothedSpeedRef.current = newSpeed;
      return newSpeed;
    }
    
    // Reject outliers (sudden massive speed changes)
    const speedChange = Math.abs(newSpeed - smoothedSpeedRef.current);
    if (speedChange > MAX_SPEED_CHANGE) {
      console.log(`Rejecting speed outlier: ${(newSpeed * 3.6).toFixed(1)} km/h (change: ${(speedChange * 3.6).toFixed(1)} km/h)`);
      return smoothedSpeedRef.current; // Keep previous speed
    }
    
    // Apply EMA smoothing
    smoothedSpeedRef.current = (alpha * newSpeed) + ((1 - alpha) * smoothedSpeedRef.current);
    return smoothedSpeedRef.current;
  };

  // Calculate distance between two coordinates using Haversine formula
  const calculateDistance = useCallback((lat1: number, lon1: number, lat2: number, lon2: number): number => {
    const R = 6371000; // Earth's radius in meters
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLon = (lon2 - lon1) * Math.PI / 180;
    const a = 
      Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
      Math.sin(dLon / 2) * Math.sin(dLon / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c; // Distance in meters
  }, []);

  const handleGeoSuccess = useCallback((position: GeolocationPosition) => {
    const { latitude, longitude, altitude, speed: rawSpeed, accuracy } = position.coords;
    const now = Date.now();
    const timeDelta = (now - lastUpdateRef.current) / 1000; // in seconds
    
    // GPS Accuracy filtering - reject poor quality readings
    const MAX_ACCEPTABLE_ACCURACY = 65; // meters
    if (accuracy > MAX_ACCEPTABLE_ACCURACY) {
      console.log(`Poor GPS accuracy (${accuracy.toFixed(1)}m), skipping update`);
      return;
    }
    
    // Ignore updates that come too quickly (GPS glitches)
    // Reduced from 0.5s to allow faster updates for activity detection
    if (timeDelta < 0.3) {
      console.log(`Update too fast (${timeDelta.toFixed(2)}s), skipping`);
      return;
    }
    
    console.log("handleGeoSuccess called:", {
      isTracking: isTrackingRef.current,
      timeDelta: timeDelta.toFixed(2),
      accuracy: accuracy.toFixed(1),
      hasLastPosition: !!lastPositionRef.current
    });
    
    // Calculate speed from distance (more reliable than GPS-reported speed)
    let calculatedSpeed = 0;
    if (lastPositionRef.current && timeDelta > 0) {
      const distance = calculateDistance(
        lastPositionRef.current.latitude,
        lastPositionRef.current.longitude,
        latitude,
        longitude
      );
      calculatedSpeed = distance / timeDelta; // m/s
      console.log(`Speed from distance: ${(calculatedSpeed * 3.6).toFixed(1)} km/h (${distance.toFixed(1)}m in ${timeDelta.toFixed(1)}s)`);
    }
    
    // Use calculated speed primarily, GPS speed as fallback
    let speed = calculatedSpeed > 0 ? calculatedSpeed : (rawSpeed || 0);
    
    // Get preliminary activity classification for validation
    const preliminaryActivity = classifyActivity(speed);
    
    // Apply activity-specific speed cap BEFORE smoothing (with altitude awareness)
    speed = validateSpeed(speed, preliminaryActivity, altitude || undefined);
    
    // --- HYBRID SENSOR FUSION ---
    const sensors = webSensors.getLatestData();

    // Activity-specific hybrid logic - GUARD: Never zero/cap when speed > 3 km/h (clearly moving)
    if (speed >= SPEED_THRESHOLDS.IDLE_OVERRIDE) {
      // User is clearly moving - don't let sensors override GPS/calculated speed
    } else if (preliminaryActivity === ActivityType.IDLE) {
      // If stationary according to sensors, force speed to 0
      if (sensors.acceleration < 0.2 && sensors.rotationRate < 0.1) {
        speed = 0;
      }
    } else if (preliminaryActivity === ActivityType.WALKING || preliminaryActivity === ActivityType.RUNNING) {
      // If no acceleration peaks, might be GPS drift
      if (sensors.acceleration < 0.5) {
        speed = Math.min(speed, 1.0); // Cap at slow walk if no movement detected
      }
    } else if (preliminaryActivity === ActivityType.CYCLING || preliminaryActivity === ActivityType.DRIVING) {
      // Use accelerometer to confirm vehicle is actually moving (filtering GPS jitter)
      if (sensors.acceleration < 0.05 && speed < 1.0) {
        speed = 0;
      }
    }
    
    // Apply EMA smoothing with outlier rejection (activity-aware)
    speed = smoothSpeed(speed, preliminaryActivity);

    // Activity after smoothing — used to cap walking distance (avoid runaway GPS + speed inflation)
    const activityAfterSpeed =
      manualActivityModeRef.current !== 'AUTO'
        ? manualActivityModeRef.current
        : classifyActivity(speed);

    // Calculate distance from coordinate changes (more accurate than speed)
    let distanceDelta = 0;
    if (lastPositionRef.current) {
      distanceDelta = calculateDistance(
        lastPositionRef.current.latitude,
        lastPositionRef.current.longitude,
        latitude,
        longitude
      );
      
      // Filter out GPS drift - ignore very small movements (0.5m for better slow-walking accuracy)
      const MIN_DISTANCE_THRESHOLD = 0.5;
      if (distanceDelta < MIN_DISTANCE_THRESHOLD) {
        distanceDelta = 0;
      }

      // Walking: only clip segments where implied speed is implausible (spike), not every segment —
      // a flat ~2 m/s cap per tick undercounted real walks when GPS batches or steps lag.
      if (activityAfterSpeed === ActivityType.WALKING && timeDelta > 0 && distanceDelta > 0.5) {
        const impliedMps = distanceDelta / timeDelta;
        const SPIKE_WALKING_MAX_MPS = 2.95; // ~10.6 km/h per segment — above this, treat as jitter
        if (impliedMps > SPIKE_WALKING_MAX_MPS) {
          distanceDelta = Math.min(distanceDelta, SPIKE_WALKING_MAX_MPS * timeDelta + 0.5);
        }
      }
      
      // Do not inflate distance with speed*time while walking/idle — that doubled GPS drift + speed
      if (speed > 0 && timeDelta > 0) {
        const speedBasedDistance = speed * timeDelta;
        if (
          activityAfterSpeed !== ActivityType.WALKING &&
          activityAfterSpeed !== ActivityType.IDLE &&
          distanceDelta < 2.5 &&
          speed > 2.5
        ) {
          distanceDelta = Math.max(distanceDelta, speedBasedDistance * 0.85);
        }
      }
    } else {
      console.log("First position received, no distance calculated yet");
    }
    
    // Update last position and time
    lastUpdateRef.current = now;
    lastPositionRef.current = {
      latitude,
      longitude,
      altitude,
      speed,
      timestamp: position.timestamp,
      accuracy
    };
    
    // Track elevation changes for calorie calculation
    if (altitude !== null && lastAltitudeRef.current !== null) {
      const elevationDelta = altitude - lastAltitudeRef.current;
      if (elevationDelta > 0) {
        sessionElevationGainRef.current += elevationDelta;
      } else if (elevationDelta < 0) {
        sessionElevationLossRef.current += Math.abs(elevationDelta);
      }
    }
    lastAltitudeRef.current = altitude;
    
    // Update real-time state
    setCurrentSpeed(speed);
    setCurrentCoords({
      latitude,
      longitude,
      altitude,
      speed,
      timestamp: position.timestamp,
      accuracy
    });

    // Classify activity - use manual mode if set, otherwise auto-detect
    // Use ref to avoid stale closure when user switches mode while tracking
    const mode = manualActivityModeRef.current;
    let activity: ActivityType;
    if (mode !== 'AUTO') {
      // Manual mode: use the selected activity
      activity = mode;
    } else {
      // Auto mode: classify based on speed and update with debouncing
      activity = classifyActivity(speed);
      updateActivityWithDebounce(activity);
    }
    
    // If manual mode, set current activity directly without debouncing
    if (mode !== 'AUTO') {
      lastStableActivity.current = activity;
      setCurrentActivity(activity);
    }

    // Update Media Session metadata with current activity and speed
    if (isTrackingRef.current) {
      const speedKmh = (speed * 3.6).toFixed(1);
      mediaSessionService.updateMetadata({
        isActive: true,
        activity: lastStableActivity.current, // Use stable activity, not raw
        speed: `${speedKmh} km/h`
      });
    }

    // Only accumulate if tracking and we have valid data
    // Use ref to avoid closure issues
    // Increased timeDelta limit from 60s to 300s (5 min) to handle background throttling/sleep better
    if (isTrackingRef.current && timeDelta > 0 && timeDelta < 300) {
      distanceDelta *= GPS_PATH_DISTANCE_SCALE;

      console.log("Accumulating tracking data:", {
        timeDelta: timeDelta.toFixed(2),
        distanceDelta: distanceDelta.toFixed(2),
        speed: speed.toFixed(2),
        activity: lastStableActivity.current
      });
      
      // Only update distance (duration is handled by dedicated timer)
      setSessionDistance(prev => {
        const newValue = prev + distanceDelta;
        console.log("Distance update:", prev.toFixed(2), "->", newValue.toFixed(2));

        // Check kilometer milestone for notifications
        if (getKmNotificationsEnabled()) {
          const newKm = Math.floor(newValue / 1000);
          if (newKm > 0 && newKm > lastNotifiedKmRef.current) {
            const currentDuration = statsRef.current.totalDuration;
            const secondsForKm = lastNotifiedKmRef.current === 0
              ? currentDuration
              : currentDuration - durationAtLastKmRef.current;
            showKmNotification(newKm, secondsForKm);
            lastNotifiedKmRef.current = newKm;
            durationAtLastKmRef.current = currentDuration;
          }
        }

        return newValue;
      });

      // Update Analytics Refs
      const currentStats = statsRef.current;
      currentStats.totalDistance += distanceDelta;
      
      // Breakdown update - use stable activity
      const stableAct = lastStableActivity.current;
      currentStats.breakdown[stableAct].distance += distanceDelta;

      // CO2 Calculation: Separate emissions from conservation
      // Positive CO2_FACTORS = emissions (driving/flying)
      // Negative CO2_FACTORS = conservation (walking)
      const co2Impact = (CO2_FACTORS[stableAct] * (distanceDelta / 1000));
      
      if (co2Impact > 0) {
        // Activity produces emissions (driving, flying)
        currentStats.co2Emissions += co2Impact;
      } else if (co2Impact < 0) {
        // Activity conserves CO2 (walking instead of driving)
        currentStats.co2Conserved += Math.abs(co2Impact);
      }
      // IDLE has no impact (co2Impact = 0)
      
      console.log("Tracking update complete:", {
        totalDistance: currentStats.totalDistance.toFixed(2),
        totalDuration: currentStats.totalDuration.toFixed(2)
      });
    } else {
      console.log("NOT accumulating - isTracking:", isTrackingRef.current, "timeDelta:", timeDelta.toFixed(2));
    }
  }, [calculateDistance, updateActivityWithDebounce]);

  const handleGeoError = (error: GeolocationPositionError) => {
    console.error("Geo error:", error);
    setIsTracking(false); // Stop tracking on error
    setPermissionGranted(false);
    
    // Provide user-friendly error messages
    let errorMessage = "Location access error: ";
    switch (error.code) {
      case error.PERMISSION_DENIED:
        errorMessage = "Location permission denied. Please allow location access in your browser settings and try again.";
        break;
      case error.POSITION_UNAVAILABLE:
        errorMessage = "Location information unavailable. Please check your device's location settings.";
        break;
      case error.TIMEOUT:
        errorMessage = "Location request timed out. Please try again.";
        break;
      default:
        errorMessage = error.message || "An unknown error occurred while accessing location.";
        break;
    }
    setErrorMsg(errorMessage);
  };

  const startTracking = async () => {
    try {
      console.log("startTracking called"); // Debug log
      
      // Check device capabilities using mobile GPS service
      const deviceInfo = mobileGPS.getDeviceInfo();
      console.log("Device info:", deviceInfo);
      
      if (!deviceInfo.supportsGPS) {
        setErrorMsg("GPS/Geolocation is not supported on this device. Please use a device with GPS capabilities.");
        return;
      }
      
      setErrorMsg(null);
      
      // Check if we're on HTTPS (required for geolocation in production)
      if (location.protocol !== 'https:' && location.hostname !== 'localhost' && location.hostname !== '127.0.0.1') {
        setErrorMsg("Geolocation requires HTTPS. Your app is deployed on HTTPS, so this should work. If you see this error, please refresh the page.");
        return;
      }
      
      setIsTracking(true);
      isTrackingRef.current = true; // Update ref immediately
      console.log("Tracking started, isTrackingRef set to:", isTrackingRef.current);
      
      // Start motion sensors for hybrid tracking
      webSensors.startListening();

      // Update Media Session for Android play controls
      mediaSessionService.setPlaybackState(true);
      mediaSessionService.updateMetadata({
        isActive: true,
        activity: currentActivity,
        speed: `${(currentSpeed * 3.6).toFixed(1)} km/h`
      });
      
      // Request wake lock to prevent device from sleeping during tracking
      try {
        await backgroundServiceRef.current.requestWakeLock();
      } catch (wakeLockError) {
        console.warn("Wake lock failed (non-critical):", wakeLockError);
        // Continue even if wake lock fails
      }
      
      // Reset session if it's a fresh start (could implement pause/resume later)
      if (sessionDuration === 0) {
        lastUpdateRef.current = Date.now();
        lastPositionRef.current = null; // Reset last position
        console.log("Fresh session started, reset position tracking");
      }
      
      // Check permission status first
      const permissionStatus = await mobileGPS.checkPermission();
      console.log("GPS Permission status:", permissionStatus);
      
      if (permissionStatus === 'denied') {
        setIsTracking(false);
        isTrackingRef.current = false;
        setErrorMsg("Location permission is denied. Please enable location access in your device/browser settings to use GPS tracking.");
        return;
      }
      
      // First, request permission explicitly using mobile GPS service
      // This will trigger the browser's permission prompt
      console.log("Requesting GPS permission via mobile service..."); // Debug log
      
      mobileGPS.getCurrentPosition(
        (position) => {
          console.log("GPS permission granted, position received:", position); // Debug log
          setPermissionGranted(true);
          handleGeoSuccess(position);
          
          // Now start watching for position updates using mobile-optimized GPS
          const watchIdResult = mobileGPS.watchPosition(
            (pos) => {
              handleGeoSuccess(pos);
            },
            handleGeoError
          );
          
          if (watchIdResult !== null) {
            watchId.current = watchIdResult;
            console.log("GPS watching started with mobile service, watchId:", watchId.current); // Debug log
          } else {
            console.error("Failed to start GPS watching");
            setIsTracking(false);
            isTrackingRef.current = false;
            setErrorMsg("Failed to start GPS tracking. Please try again.");
          }
        },
        (error) => {
          console.error("GPS getCurrentPosition error:", error); // Debug log
          setIsTracking(false);
          isTrackingRef.current = false;
          handleGeoError(error);
        }
      );
    } catch (error) {
      console.error("Error in startTracking:", error);
      setIsTracking(false);
      isTrackingRef.current = false;
      setErrorMsg("An error occurred while starting tracking. Please try again.");
    }
  };

  const stopTracking = () => {
    setIsTracking(false);
    isTrackingRef.current = false; // Update ref immediately
    console.log("Tracking stopped, isTrackingRef set to:", isTrackingRef.current);
    
    // Clear the dedicated timer
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = null;
    }
    
    // Update Media Session for Android play controls
    mediaSessionService.setPlaybackState(false);
    mediaSessionService.updateMetadata({
      isActive: false,
      activity: currentActivity,
      speed: `${(currentSpeed * 3.6).toFixed(1)} km/h`
    });
    
    // Release wake lock when tracking stops
    backgroundServiceRef.current.releaseWakeLock();
    
    // Stop motion sensors
    webSensors.stopListening();

    // Stop GPS watching using mobile service
    mobileGPS.stopWatching();
    if (watchId.current !== null) {
      watchId.current = null;
    }
    
    // Show confirmation modal to Save or Discard
    setShowStopConfirmation(true);
  };

  const handleSaveSession = () => {
    if (isSavingSession) return;
    setIsSavingSession(true);
    
    // Clear saved active session since tracking is complete
    try {
      localStorage.removeItem(ACTIVE_SESSION_KEY);
      console.log('Cleared active session from storage');
    } catch (error) {
      console.error('Failed to clear session storage:', error);
    }
    
    // Save session
    if (activeUser) {
      try {
        // Deep clone stats before persisting so the stored snapshot is immutable
        const snapshot: SessionStats = JSON.parse(JSON.stringify(statsRef.current));
        
        // Validate session has meaningful data
        if (snapshot.totalDistance < 1 && snapshot.totalDuration < 5) {
          console.warn('Session too short or no movement detected, not saving');
          setErrorMsg('Session was too short to save (minimum 5 seconds or 1 meter)');
          setIsSavingSession(false);
          setShowStopConfirmation(false);
          // Still reset the session even if not saving
          resetSessionState();
          setView('TRACKER');
          return;
        }
        
        console.log('Saving session:', {
          duration: snapshot.totalDuration,
          distance: snapshot.totalDistance,
          emissions: snapshot.co2Emissions,
          conserved: snapshot.co2Conserved
        });
        
        const sessionStartMs = timerStartTimeRef.current > 0 ? timerStartTimeRef.current : undefined;
        const updatedProfile = saveSessionForUser(activeUser.email, snapshot, sessionStartMs);
        if (updatedProfile) {
          setActiveUser({ ...updatedProfile });
          console.log('Session saved successfully to localStorage');
          setShowStopConfirmation(false);
          setIsSavingSession(false);
          
          // CRITICAL FIX: Reset stats AFTER saving to prevent accumulation
          resetSessionState();
          
          // Switch to Analytics view to show the saved session
          setView('ANALYTICS');
        } else {
          console.error('Failed to save session - no updated profile returned');
          setErrorMsg('Failed to save session. Please try again.');
          setIsSavingSession(false);
        }
      } catch (error) {
        console.error('Error saving session:', error);
        setErrorMsg('Error saving session: ' + (error instanceof Error ? error.message : 'Unknown error'));
        setIsSavingSession(false);
      }
    } else {
      console.warn('No active user - session not saved');
      setErrorMsg('Please log in to save your sessions');
      setShowStopConfirmation(false);
      setIsSavingSession(false);
    }
  };

  const handleDiscardSession = () => {
    if (isSavingSession) return;
    setShowStopConfirmation(false);
    resetSessionState();
    setView('TRACKER');
  };

  // Extracted reset logic to be reused after save AND discard
  const resetSessionState = () => {
    // Reset all refs and state
    statsRef.current = {
      totalDuration: 0,
      totalDistance: 0,
      caloriesBurned: 0,
      co2Emissions: 0,
      co2Conserved: 0,
      segments: [],
      breakdown: {
        [ActivityType.IDLE]: { time: 0, distance: 0 },
        [ActivityType.WALKING]: { time: 0, distance: 0 },
        [ActivityType.RUNNING]: { time: 0, distance: 0 },
        [ActivityType.CYCLING]: { time: 0, distance: 0 },
        [ActivityType.DRIVING]: { time: 0, distance: 0 },
        [ActivityType.ELECTRIC_VEHICLE]: { time: 0, distance: 0 },
        [ActivityType.FLYING]: { time: 0, distance: 0 },
      }
    };
    setSessionDuration(0);
    setSessionDistance(0);
    setCurrentSpeed(0);
    setCurrentActivity(ActivityType.IDLE);
    lastUpdateRef.current = Date.now();
    lastPositionRef.current = null; // Reset last position
    
    // Reset speed smoothing
    smoothedSpeedRef.current = 0;
    speedHistoryRef.current = [];
    
    // Reset elevation tracking
    lastAltitudeRef.current = null;
    sessionElevationGainRef.current = 0;
    sessionElevationLossRef.current = 0;
    
    // Reset activity debouncing
    activityHistory.current = [];
    lastStableActivity.current = ActivityType.IDLE;
    
    // Reset activity persistence tracking
    activityStartTimeRef.current = Date.now();
    activityDurationSeconds.current = 0;
    
    // Clear timer
    if (timerIntervalRef.current) {
      clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = null;
    }
    timerStartTimeRef.current = 0;
    
    // Clear saved session state
    try {
      localStorage.removeItem(ACTIVE_SESSION_KEY);
      console.log('Session reset and cleared from storage');
    } catch (error) {
      console.error('Failed to clear session storage:', error);
    }
  };

  // Backward compatibility wrapper
  const resetSession = () => {
    resetSessionState();
    setView('TRACKER');
  };

  // Handle Authentication and App Initialization
  useEffect(() => {
    let isMounted = true;
    let authUnsubscribe: (() => void) | null = null;

    const initialize = async () => {
      console.log("Starting app initialization...");

      // 1. Check for immediate local session (UX speed)
      const localEmail = getActiveUserEmail();
      if (localEmail) {
        const localProfile = loadProfile(localEmail);
        if (localProfile && isMounted) {
          console.log("Found local session for:", localEmail);
          setActiveUser(localProfile);
        }
      }

      // 2. Setup Firebase Auth State Listener
      authUnsubscribe = onAuthStateChange(async (user) => {
        if (!isMounted) return;
        
        console.log("Firebase Auth state change observed. User:", user?.email);
        
        if (user && user.email) {
          try {
            // First try to get profile from Firestore
            let profile = await getUserProfile(user.uid);
            
            if (!profile) {
              console.log("No Firestore profile found, syncing from social account:", user.email);
              // syncSocialProfile handles both finding local existing and creating new placeholder
              profile = syncSocialProfile(user.email);
            }

            if (profile && isMounted) {
              setActiveUser(profile);
              setActiveUserEmail(profile.email);
              setIsInitializing(false);
            }
          } catch (err) {
            console.error("Error syncing profile:", err);
          }
        } else {
          // If no Firebase user, check if we're in a "logged out" state
          // but keep local user if they were already there (offline support)
          if (!getActiveUserEmail() && isMounted) {
            setActiveUser(null);
          }
          if (isMounted) setIsInitializing(false);
        }
      });

      // 3. Handle Google Redirect Result specifically
      try {
        const redirectUser = await handleRedirectResult();
        if (redirectUser && redirectUser.email && isMounted) {
          console.log("Google redirect sign-in successful for:", redirectUser.email);
          const profile = syncSocialProfile(redirectUser.email);
          setActiveUser(profile);
          setActiveUserEmail(profile.email);
          setIsInitializing(false);
        }
      } catch (error) {
        console.error("Redirect handling error:", error);
      }

      // 4. Safety Timeout
      setTimeout(() => {
        if (isMounted && isInitializing) {
          console.log("Initialization safety timeout reached");
          setIsInitializing(false);
        }
      }, 4000);
    };

    initialize();

    // 5. Initialize Media Session API for Android play controls
    mediaSessionService.initialize(
      () => { if (!isTrackingRef.current) startTracking(); },
      () => { if (isTrackingRef.current) stopTracking(); }
    );

    return () => {
      isMounted = false;
      if (authUnsubscribe) authUnsubscribe();
      mobileGPS.stopWatching();
      mediaSessionService.clear();
    };
  }, []);

  // Handle Android back button separately
  useEffect(() => {
    const handleBackButton = (event: PopStateEvent) => {
      // If not on TRACKER view, navigate back to it
      if (view !== 'TRACKER') {
        setView('TRACKER');
        // Push state to prevent full page back
        window.history.pushState({ view: 'TRACKER' }, '', window.location.href);
      }
    };

    // Listen for popstate (Android back button)
    window.addEventListener('popstate', handleBackButton);

    return () => {
      window.removeEventListener('popstate', handleBackButton);
    };
  }, [view]);

  const navigationOptions = useMemo(() => {
    if (!activeUser) return [];
    return [
      { label: 'Tracker', key: 'TRACKER' as const },
      { label: 'Analytics', key: 'ANALYTICS' as const },
      { label: 'Profile', key: 'PROFILE' as const },
      { label: 'Settings', key: 'SETTINGS' as const },
      { label: 'Feedback', key: 'FEEDBACK' as const },
    ];
  }, [activeUser]);

  // Loads the Firestore profile for a freshly authenticated Firebase user
  // and falls back to the legacy localStorage profile if Firestore has none.
  const hydrateProfileForUser = async (
    uid: string,
    email: string,
    password: string
  ) => {
    const profile = await getUserProfile(uid);
    if (profile) {
      setActiveUser(profile);
      setAuthError(null);
      return;
    }
    const legacyResult = authenticateOrCreateProfile(email, password);
    if (legacyResult.profile) {
      setActiveUser(legacyResult.profile);
      setAuthError(null);
    }
  };

  const handleLogin = async (email: string, password: string) => {
    try {
      const firebaseResult = await signInWithEmail(email, password);

      if (firebaseResult.error) {
        // Surface the real sign-in error. Sign-up is now an explicit user
        // action via handleSignUp (no more silent auto-create on first login).
        setAuthError(firebaseResult.error);
        return;
      }

      if (firebaseResult.user) {
        await hydrateProfileForUser(firebaseResult.user.uid, email, password);
      }
    } catch (error: any) {
      // Network/Firebase outage: fall back to localStorage-based auth so
      // existing offline users can still get into the app.
      const result = authenticateOrCreateProfile(email, password);
      if (result.error) {
        setAuthError(result.error);
        return;
      }
      if (result.profile) {
        setActiveUser(result.profile);
        setAuthError(null);
      }
    }
  };

  const handleSignUp = async (email: string, password: string) => {
    try {
      const result = await signUpWithEmail(email, password);
      if (result.error) {
        setAuthError(result.error);
        return;
      }
      if (result.user) {
        await hydrateProfileForUser(result.user.uid, email, password);
      }
    } catch (error: any) {
      setAuthError(error?.message || 'Failed to create account. Please try again.');
    }
  };

  const handleGoogleSignIn = async () => {
    try {
      const result = await signInWithGoogle();
      if (result.error) {
        setAuthError(result.error);
        return;
      }
      if (result.user && result.user.email) {
        console.log("Sign-in with Google successful for:", result.user.email);
        const profile = syncSocialProfile(result.user.email);
        setActiveUser(profile);
        setActiveUserEmail(profile.email);
        setAuthError(null);
      }
    } catch (error: any) {
      setAuthError(error.message || 'Failed to sign in with Google');
    }
  };

  const handleForgotPassword = async (email: string) => {
    const result = await resetPassword(email);
    if (result.error) {
      throw new Error(result.error);
    }
  };

  const handleLogout = async () => {
    try {
      // Sign out from Firebase Auth
      await signOutUser();
    } catch (error) {
      console.error('Firebase sign out error:', error);
    }
    // Also clear localStorage
    logoutUser();
    setActiveUser(null);
    setView('TRACKER');
    setMenuOpen(false);
  };

  const handleUnitChange = (unit: UnitSystem) => {
    setUnitSystem(unit);
    try {
      localStorage.setItem(UNIT_STORAGE_KEY, unit);
    } catch {
      // ignore storage issues
    }
  };

  const toggleUnitQuickly = () => {
    handleUnitChange(unitSystem === 'METRIC' ? 'IMPERIAL' : 'METRIC');
  };
  
  const handleActivityModeSelect = (mode: ActivityType | 'AUTO') => {
    setManualActivityMode(mode);
    manualActivityModeRef.current = mode; // Update ref immediately so GPS callback sees it
    
    if (mode !== 'AUTO' && isTracking) {
      // Switching to manual mode: update activity immediately
      setCurrentActivity(mode);
      lastStableActivity.current = mode;
      activityHistory.current = [];
      activityStartTimeRef.current = Date.now();
      activityDurationSeconds.current = 0;
    } else if (mode === 'AUTO') {
      // Switching to auto-detect: reset display to idle until movement is detected
      setCurrentActivity(ActivityType.IDLE);
      lastStableActivity.current = ActivityType.IDLE;
      activityHistory.current = [];
      activityStartTimeRef.current = Date.now();
      activityDurationSeconds.current = 0;
    }
    
    console.log(`Activity mode changed to: ${mode}`);
  };

  // Keep isTrackingRef in sync with isTracking state
  useEffect(() => {
    isTrackingRef.current = isTracking;
    console.log("isTracking state changed to:", isTracking, "ref updated");
  }, [isTracking]);

  // Keep manualActivityModeRef in sync (e.g. initial mount, future state sources)
  useEffect(() => {
    manualActivityModeRef.current = manualActivityMode;
  }, [manualActivityMode]);

  // Auto-save session state periodically while tracking
  useEffect(() => {
    if (!isTracking) return;

    // Save immediately when tracking starts
    saveActiveSession();

    // Save every 5 seconds while tracking
    const intervalId = setInterval(() => {
      if (isTrackingRef.current) {
        saveActiveSession();
      }
    }, 5000);

    return () => clearInterval(intervalId);
  }, [isTracking, sessionDuration, sessionDistance, currentActivity, saveActiveSession]);

  // Restore session state on mount if there was an active session
  useEffect(() => {
    const restored = restoreActiveSession();
    if (restored && restored.isTracking) {
      console.log('Found active session, restoring state...');
      
      // Restore state
      setSessionDuration(restored.sessionDuration);
      setSessionDistance(restored.sessionDistance);
      setCurrentActivity(restored.currentActivity);
      statsRef.current = restored.stats;
      lastUpdateRef.current = restored.lastUpdateTime;
      lastPositionRef.current = restored.lastPosition;
      // Sync km milestone refs so we don't re-notify for already completed km
      const restoredKm = Math.floor(restored.sessionDistance / 1000);
      lastNotifiedKmRef.current = restoredKm;
      durationAtLastKmRef.current = restored.sessionDuration;
      
      // Don't automatically restart tracking, but show a message
      setErrorMsg(`Previous session detected (${(restored.sessionDuration / 60).toFixed(1)} min, ${(restored.sessionDistance / 1000).toFixed(2)} km). Tap play to continue or reset to start fresh.`);
      
      console.log('Session state restored successfully');
    }
  }, [restoreActiveSession]);

  // Function to request location permission proactively
  const requestLocationPermission = useCallback(async () => {
    if (!navigator.geolocation) {
      console.log("Geolocation not supported");
      return;
    }

    // Check if we're on HTTPS (required for geolocation)
    if (location.protocol !== 'https:' && location.hostname !== 'localhost' && location.hostname !== '127.0.0.1') {
      console.log("Geolocation requires HTTPS");
      return;
    }

    try {
      // Check current permission status
      const permissionStatus = await mobileGPS.checkPermission();
      console.log("Current location permission status:", permissionStatus);

      if (permissionStatus === 'granted') {
        console.log("Location permission already granted");
        setPermissionGranted(true);
        return;
      }

      if (permissionStatus === 'denied') {
        setErrorMsg("Location permission is denied. Please enable it in your browser settings to use GPS tracking.");
        return;
      }

      // If permission is 'prompt', request it proactively
      if (permissionStatus === 'prompt' || permissionStatus === null) {
        console.log("Requesting location permission...");
        
        // Request permission by getting current position (this triggers the browser prompt)
        mobileGPS.getCurrentPosition(
          (position) => {
            console.log("Location permission granted!");
            setPermissionGranted(true);
            setErrorMsg(null);
            // Update current coordinates
            setCurrentCoords({
              latitude: position.coords.latitude,
              longitude: position.coords.longitude,
              altitude: position.coords.altitude,
              speed: position.coords.speed || 0,
              timestamp: position.timestamp,
              accuracy: position.coords.accuracy
            });
          },
          (error) => {
            console.log("Location permission denied or error:", error);
            if (error.code === error.PERMISSION_DENIED) {
              setErrorMsg("Location permission was denied. Please allow location access to use GPS tracking features.");
            }
          }
        );
      }
    } catch (error) {
      console.error("Error checking/requesting location permission:", error);
    }
  }, []);

  useEffect(() => {
    try {
      const storedUnit = localStorage.getItem(UNIT_STORAGE_KEY) as UnitSystem | null;
      if (storedUnit === 'METRIC' || storedUnit === 'IMPERIAL') {
        setUnitSystem(storedUnit);
      }
    } catch {
      // ignore
    }
    
    // Listen for Android native FAB clicks
    const handleNativeActivitySelector = () => {
      setActivitySelectorOpen(true);
    };
    
    window.addEventListener('openActivitySelector', handleNativeActivitySelector);
    
    // Expose function for Android to call
    (window as any).openActivitySelector = () => {
      setActivitySelectorOpen(true);
    };
    
    (window as any).setActivitySelectorOpen = (open: boolean) => {
      setActivitySelectorOpen(open);
    };
    
    // Check geolocation permission status on mount
    if (navigator.geolocation && 'permissions' in navigator) {
      navigator.permissions.query({ name: 'geolocation' }).then((result) => {
        if (result.state === 'denied') {
          setErrorMsg("Location permission is denied. Please enable it in your browser settings to use tracking.");
        } else if (result.state === 'prompt') {
          // Automatically request permission
          requestLocationPermission();
        } else if (result.state === 'granted') {
          setPermissionGranted(true);
        }
        result.onchange = () => {
          if (result.state === 'denied') {
            setErrorMsg("Location permission was denied. Please enable it in your browser settings.");
            setIsTracking(false);
            isTrackingRef.current = false;
          } else if (result.state === 'granted') {
            setPermissionGranted(true);
            setErrorMsg(null);
          }
        };
      }).catch(() => {
        // Permissions API not supported, try requesting anyway
        requestLocationPermission();
      });
    } else {
      // Permissions API not available, try requesting anyway
      requestLocationPermission();
    }
    
    return () => {
      window.removeEventListener('openActivitySelector', handleNativeActivitySelector);
    };
  }, [requestLocationPermission]);

  // Request location permission when user logs in
  useEffect(() => {
    if (activeUser) {
      // Small delay to ensure page is fully loaded
      const timer = setTimeout(() => {
        requestLocationPermission();
      }, 1000);
      return () => clearTimeout(timer);
    }
  }, [activeUser, requestLocationPermission]);

  useEffect(() => {
    if (!menuOpen) return;
    const onEsc = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    window.addEventListener('keydown', onEsc);
    return () => window.removeEventListener('keydown', onEsc);
  }, [menuOpen]);

  // Cleanup on unmount
  useEffect(() => {
    // Save state before unload if tracking
    const handleBeforeUnload = () => {
      if (isTrackingRef.current) {
        saveActiveSession();
      }
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload);
      // Cleanup background service
      backgroundServiceRef.current.cleanup();
      // Stop GPS if still running
      if (isTrackingRef.current) {
        mobileGPS.stopWatching();
      }
    };
  }, [saveActiveSession]);

  const renderFloatingMenu = (isAuthenticated: boolean) => (
    <FloatingMenu
      open={menuOpen}
      onToggle={() => setMenuOpen((prev) => !prev)}
      onShowSettings={() => {
        if (isAuthenticated) {
          setView('SETTINGS');
        }
        setMenuOpen(false);
      }}
      onShowFeedback={() => {
        if (isAuthenticated) {
          setView('FEEDBACK');
        }
        setMenuOpen(false);
      }}
      onLogout={() => {
        if (isAuthenticated) {
          handleLogout();
        }
      }}
      onPlayPause={() => {
        if (isAuthenticated) {
          if (isTracking) {
            stopTracking();
          } else {
            startTracking();
          }
          setMenuOpen(false);
        }
      }}
      isTracking={isTracking}
      unitSystem={unitSystem}
      onUnitQuickToggle={toggleUnitQuickly}
      isAuthenticated={isAuthenticated}
      onLoginClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
    />
  );

  if (isInitializing) {
    return (
      <div className="min-h-screen bg-slate-950 flex items-center justify-center p-4">
        <div className="text-center">
          <div className="w-12 h-12 border-4 border-green-400 border-t-transparent rounded-full animate-spin mx-auto mb-4 shadow-lg shadow-green-500/20"></div>
          <h2 className="text-white font-bold text-lg mb-1">Authenticating</h2>
          <p className="text-slate-400 text-sm animate-pulse">Syncing your profile with Kinetic...</p>
        </div>
      </div>
    );
  }

  // Final check: if Firebase says we are logged in but our profile state didn't sync yet,
  // don't show the login page - keep waiting or try to force sync.
  const firebaseUser = getCurrentUser();
  if (firebaseUser && !activeUser) {
    return (
      <div className="min-h-screen bg-slate-950 flex items-center justify-center p-4">
        <div className="text-center">
          <div className="w-12 h-12 border-4 border-blue-400 border-t-transparent rounded-full animate-spin mx-auto mb-4"></div>
          <p className="text-slate-400 text-sm italic">Loading your profile data...</p>
          <button 
            onClick={() => window.location.reload()} 
            className="mt-6 text-xs text-slate-500 underline"
          >
            Taking too long? Click to retry
          </button>
        </div>
      </div>
    );
  }

  if (!activeUser) {
    return (
      <>
        <LoginForm
          onSubmit={handleLogin}
          onSignUp={handleSignUp}
          onGoogleSignIn={handleGoogleSignIn}
          onForgotPassword={handleForgotPassword}
          onClearError={() => setAuthError(null)}
          error={authError}
        />
        {renderFloatingMenu(false)}
      </>
    );
  }

  return (
    <div className="min-h-screen bg-slate-900 flex flex-col">
      {/* Header */}
      <header className="bg-slate-900 border-b border-slate-800 p-3 sm:p-4 sticky top-0 z-50 backdrop-blur-md bg-opacity-80 safe-area-top">
        <div className="max-w-4xl mx-auto flex items-center justify-between">
          <button
            type="button"
            onClick={() => setView('TRACKER')}
            className="flex items-center space-x-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-green-400 rounded-lg active:opacity-80 transition-opacity min-h-[44px] touch-manipulation"
          >
            <div className="w-8 h-8 sm:w-10 sm:h-10 bg-gradient-to-tr from-green-400 to-blue-500 rounded-lg flex items-center justify-center shadow-lg shadow-blue-500/20">
               <MapPin size={20} className="text-white" />
            </div>
            <h1 className="text-lg sm:text-xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-white to-slate-400">
              Kinetic
            </h1>
          </button>
          <div className="flex items-center space-x-2 sm:space-x-4">
            <nav className="hidden md:flex space-x-2">
              {navigationOptions.map((option) => (
                <button
                  key={option.key}
                  onClick={() => setView(option.key)}
                  className={`px-3 py-1 rounded-full text-xs font-semibold transition min-h-[44px] touch-manipulation ${
                    view === option.key ? 'bg-green-500/20 text-green-300 border border-green-500/40' : 'text-slate-400 hover:text-white'
                  }`}
                >
                  {option.label}
                </button>
              ))}
            </nav>
            <div className="text-[10px] sm:text-xs text-slate-500 font-mono text-right hidden sm:block">
              {APP_CONFIG.displayVersion} • {view}
              <p className="text-[8px] sm:text-[10px] text-slate-500">
                {activeUser.email}
                {activeUser.isLegacy && ' • legacy'}
              </p>
            </div>
            {/* Mobile view indicator */}
            <div className="text-xs text-slate-500 font-mono sm:hidden">
              {view}
            </div>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-grow flex flex-col items-center justify-center p-3 sm:p-4 pb-20 sm:pb-4 safe-area-bottom" style={{ pointerEvents: 'auto' }}>
        {errorMsg && (
          <div className="bg-red-900/50 border border-red-500/50 text-red-200 p-4 rounded-lg mb-6 max-w-md w-full text-sm">
            Warning: {errorMsg}
            <button onClick={() => setErrorMsg(null)} className="ml-4 underline opacity-70 hover:opacity-100">Dismiss</button>
          </div>
        )}

        {view === 'TRACKER' && (
          <Tracker 
            isTracking={isTracking}
            onToggleTracking={isTracking ? stopTracking : startTracking}
            currentActivity={currentActivity}
            currentSpeed={currentSpeed}
            currentCoords={currentCoords}
            duration={sessionDuration}
            distance={sessionDistance}
            unitSystem={unitSystem}
            manualActivityMode={manualActivityMode}
          />
        )}

        {view === 'ANALYTICS' && (
          <Analytics 
            stats={statsRef.current}
            profile={activeUser}
          />
        )}

        {view === 'PROFILE' && (
          <Profile
            profile={activeUser}
            onLogout={handleLogout}
            onUpdateProfile={(updatedProfile) => {
              setActiveUser(updatedProfile);
              // Save to localStorage
              const profiles = JSON.parse(localStorage.getItem('kinetic_profiles') || '{}');
              profiles[updatedProfile.email] = updatedProfile;
              localStorage.setItem('kinetic_profiles', JSON.stringify(profiles));
            }}
          />
        )}

        {view === 'SETTINGS' && (
          <SettingsPage
            unitSystem={unitSystem}
            onUnitChange={handleUnitChange}
            onNavigateProfile={() => setView('PROFILE')}
            onLogout={handleLogout}
            profile={activeUser}
          />
        )}

        {view === 'FEEDBACK' && (
          <FeedbackPage
            onBack={() => setView('TRACKER')}
          />
        )}
      </main>
      {renderFloatingMenu(true)}
      
      {/* Floating Activity Button - Only show on TRACKER view and NOT in Android WebView */}
      {view === 'TRACKER' && !(window as any).AndroidInterface && (
        <FloatingActivityButton
          onClick={() => setActivitySelectorOpen(true)}
          isTracking={isTracking}
        />
      )}
      
      {/* Activity Selector Modal */}
      <ActivitySelector
        isOpen={activitySelectorOpen}
        onClose={() => setActivitySelectorOpen(false)}
        onSelectActivity={handleActivityModeSelect}
        selectedMode={manualActivityMode}
        isTracking={isTracking}
      />
      
      {/* Stop Confirmation Modal */}
      <ConfirmationModal
        isOpen={showStopConfirmation}
        onClose={() => !isSavingSession && setShowStopConfirmation(false)}
        onConfirm={handleSaveSession}
        onDiscard={handleDiscardSession}
        isSaving={isSavingSession}
        title="Stop Tracking?"
        message="Your activity has been recorded. Would you like to save this session to your profile or discard the data?"
      />
    </div>
  );
};

export default App;
