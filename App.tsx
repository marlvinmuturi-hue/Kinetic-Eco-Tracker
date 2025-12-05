import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Tracker } from './components/Tracker';
import { Analytics } from './components/Analytics';
import { Profile } from './components/Profile';
import { LoginForm } from './components/LoginForm';
import { SettingsPage } from './components/SettingsPage';
import { FloatingMenu } from './components/FloatingMenu';
import { FeedbackPage } from './components/FeedbackPage';
import { ActivityType, SessionStats, GeoPosition, UserProfile, UnitSystem } from './types';
import { SPEED_THRESHOLDS, CO2_FACTORS, CALORIE_FACTORS_PER_HOUR } from './constants';
import { MapPin } from 'lucide-react';
import { authenticateOrCreateProfile, getActiveUserEmail, loadProfile, logoutUser, saveSessionForUser } from './services/profileService';
import { APP_CONFIG } from './appConfig';

const UNIT_STORAGE_KEY = 'kinetic_unit_preference';

const App: React.FC = () => {
  // State
  const [view, setView] = useState<'TRACKER' | 'ANALYTICS' | 'PROFILE' | 'SETTINGS' | 'FEEDBACK'>('TRACKER');
  const [isTracking, setIsTracking] = useState(false);
  const [permissionGranted, setPermissionGranted] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [activeUser, setActiveUser] = useState<UserProfile | null>(null);
  const [authError, setAuthError] = useState<string | null>(null);
  const [unitSystem, setUnitSystem] = useState<UnitSystem>('METRIC');
  const [menuOpen, setMenuOpen] = useState(false);

  // Real-time tracking data
  const [currentSpeed, setCurrentSpeed] = useState(0); // m/s
  const [currentCoords, setCurrentCoords] = useState<GeoPosition | null>(null);
  const [currentActivity, setCurrentActivity] = useState<ActivityType>(ActivityType.IDLE);
  
  // Session accumulation
  const [sessionDuration, setSessionDuration] = useState(0); // seconds
  const [sessionDistance, setSessionDistance] = useState(0); // meters
  
  // Detailed stats for analytics
  const statsRef = useRef<SessionStats>({
    totalDuration: 0,
    totalDistance: 0,
    caloriesBurned: 0,
    co2Emissions: 0,
    segments: [],
    breakdown: {
      [ActivityType.IDLE]: { time: 0, distance: 0 },
      [ActivityType.WALKING]: { time: 0, distance: 0 },
      [ActivityType.DRIVING]: { time: 0, distance: 0 },
      [ActivityType.FLYING]: { time: 0, distance: 0 },
    }
  });

  // Watch ID for geolocation
  const watchId = useRef<number | null>(null);
  const lastUpdateRef = useRef<number>(Date.now());

  // Determine Activity based on Speed (m/s)
  const classifyActivity = (speed: number): ActivityType => {
    if (speed < SPEED_THRESHOLDS.WALKING_MIN) return ActivityType.IDLE;
    if (speed < SPEED_THRESHOLDS.DRIVING_MIN) return ActivityType.WALKING;
    if (speed < SPEED_THRESHOLDS.FLYING_MIN) return ActivityType.DRIVING;
    return ActivityType.FLYING;
  };

  const handleGeoSuccess = useCallback((position: GeolocationPosition) => {
    const { latitude, longitude, speed: rawSpeed, accuracy } = position.coords;
    const now = Date.now();
    const timeDelta = (now - lastUpdateRef.current) / 1000; // in seconds
    lastUpdateRef.current = now;

    // Use speed from GPS or 0 if null. 
    // Sometimes GPS returns null speed if stationary or just starting.
    const speed = rawSpeed === null ? 0 : rawSpeed;
    
    // Update real-time state
    setCurrentSpeed(speed);
    setCurrentCoords({
      latitude,
      longitude,
      speed,
      timestamp: position.timestamp,
      accuracy
    });

    const activity = classifyActivity(speed);
    setCurrentActivity(activity);

    // Only accumulate if tracking
    if (isTracking && timeDelta > 0 && timeDelta < 60) { // Reject huge jumps > 60s (e.g. if device slept)
      const distanceDelta = speed * timeDelta; // meters
      
      // Update UI totals
      setSessionDuration(prev => prev + timeDelta);
      setSessionDistance(prev => prev + distanceDelta);

      // Update Analytics Refs
      const currentStats = statsRef.current;
      currentStats.totalDuration += timeDelta;
      currentStats.totalDistance += distanceDelta;
      
      // Breakdown update
      currentStats.breakdown[activity].time += timeDelta;
      currentStats.breakdown[activity].distance += distanceDelta;

      // Calories (kcal) = (kcal/hour / 3600) * seconds
      const kcalBurned = (CALORIE_FACTORS_PER_HOUR[activity] / 3600) * timeDelta;
      currentStats.caloriesBurned += kcalBurned;

      // Emissions (kg) = (kg/km * km) => (kg/km * (meters/1000))
      // Only count emissions for driving/flying
      const emissions = (CO2_FACTORS[activity] * (distanceDelta / 1000));
      currentStats.co2Emissions += emissions;
    }
  }, [isTracking]);

  const handleGeoError = (error: GeolocationPositionError) => {
    console.error("Geo error:", error);
    setErrorMsg(error.message);
  };

  const startTracking = () => {
    if (!navigator.geolocation) {
      setErrorMsg("Geolocation is not supported by this browser.");
      return;
    }
    setErrorMsg(null);
    setIsTracking(true);
    setPermissionGranted(true); // Assuming success if we are here
    
    // Reset session if it's a fresh start (could implement pause/resume later)
    if (sessionDuration === 0) {
      lastUpdateRef.current = Date.now();
    }
    
    // Start Watcher
    const options = {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 0
    };
    watchId.current = navigator.geolocation.watchPosition(handleGeoSuccess, handleGeoError, options);
  };

  const stopTracking = () => {
    setIsTracking(false);
    if (watchId.current !== null) {
      navigator.geolocation.clearWatch(watchId.current);
      watchId.current = null;
    }
    // Switch to Analytics view when stopped
    setView('ANALYTICS');
    if (activeUser) {
      // Deep clone stats before persisting so the stored snapshot is immutable
      const snapshot: SessionStats = JSON.parse(JSON.stringify(statsRef.current));
      const updatedProfile = saveSessionForUser(activeUser.email, snapshot);
      if (updatedProfile) {
        setActiveUser({ ...updatedProfile });
      }
    }
  };

  const resetSession = () => {
    // Reset all refs and state
    statsRef.current = {
      totalDuration: 0,
      totalDistance: 0,
      caloriesBurned: 0,
      co2Emissions: 0,
      segments: [],
      breakdown: {
        [ActivityType.IDLE]: { time: 0, distance: 0 },
        [ActivityType.WALKING]: { time: 0, distance: 0 },
        [ActivityType.DRIVING]: { time: 0, distance: 0 },
        [ActivityType.FLYING]: { time: 0, distance: 0 },
      }
    };
    setSessionDuration(0);
    setSessionDistance(0);
    setCurrentSpeed(0);
    setCurrentActivity(ActivityType.IDLE);
    setView('TRACKER');
  };

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (watchId.current !== null) {
        navigator.geolocation.clearWatch(watchId.current);
      }
    };
  }, []);

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

  const handleLogin = async (email: string, password: string) => {
    const result = authenticateOrCreateProfile(email, password);
    if (result.error) {
      setAuthError(result.error);
      return;
    }
    if (result.profile) {
      setActiveUser(result.profile);
      setAuthError(null);
    }
  };

  const handleLogout = () => {
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

  useEffect(() => {
    const email = getActiveUserEmail();
    if (email) {
      const profile = loadProfile(email);
      if (profile) {
        setActiveUser(profile);
      }
    }
    try {
      const storedUnit = localStorage.getItem(UNIT_STORAGE_KEY) as UnitSystem | null;
      if (storedUnit === 'METRIC' || storedUnit === 'IMPERIAL') {
        setUnitSystem(storedUnit);
      }
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => {
    if (!menuOpen) return;
    const onEsc = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    window.addEventListener('keydown', onEsc);
    return () => window.removeEventListener('keydown', onEsc);
  }, [menuOpen]);

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
      onShowProfile={() => {
        if (isAuthenticated) {
          setView('PROFILE');
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
      unitSystem={unitSystem}
      onUnitQuickToggle={toggleUnitQuickly}
      isAuthenticated={isAuthenticated}
      onLoginClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
    />
  );

  if (!activeUser) {
    return (
      <>
        <LoginForm onSubmit={handleLogin} error={authError} />
        {renderFloatingMenu(false)}
      </>
    );
  }

  return (
    <div className="min-h-screen bg-slate-900 flex flex-col">
      {/* Header */}
      <header className="bg-slate-900 border-b border-slate-800 p-4 sticky top-0 z-50 backdrop-blur-md bg-opacity-80">
        <div className="max-w-4xl mx-auto flex items-center justify-between">
          <button
            type="button"
            onClick={() => setView('TRACKER')}
            className="flex items-center space-x-2 focus:outline-none focus-visible:ring-2 focus-visible:ring-green-400 rounded-lg"
          >
            <div className="w-8 h-8 bg-gradient-to-tr from-green-400 to-blue-500 rounded-lg flex items-center justify-center shadow-lg shadow-blue-500/20">
               <MapPin size={20} className="text-white" />
            </div>
            <h1 className="text-xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-white to-slate-400">
              Kinetic
            </h1>
          </button>
          <div className="flex items-center space-x-4">
            <nav className="hidden md:flex space-x-2">
              {navigationOptions.map((option) => (
                <button
                  key={option.key}
                  onClick={() => setView(option.key)}
                  className={`px-3 py-1 rounded-full text-xs font-semibold transition ${
                    view === option.key ? 'bg-green-500/20 text-green-300 border border-green-500/40' : 'text-slate-400 hover:text-white'
                  }`}
                >
                  {option.label}
                </button>
              ))}
            </nav>
            <div className="text-xs text-slate-500 font-mono text-right">
              {APP_CONFIG.displayVersion} • {view}
              <p className="text-[10px] text-slate-500">
                {activeUser.email}
                {activeUser.isLegacy && ' • legacy'}
              </p>
            </div>
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-grow flex flex-col items-center justify-center p-4">
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
          />
        )}

        {view === 'ANALYTICS' && (
          <Analytics 
            stats={statsRef.current}
            onReset={resetSession}
          />
        )}

        {view === 'PROFILE' && (
          <Profile
            profile={activeUser}
            onLogout={handleLogout}
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
    </div>
  );
};

export default App;
