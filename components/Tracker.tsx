import React, { useCallback, useRef } from 'react';
import { ActivityType, GeoPosition, UnitSystem } from '../types';
import { ActivityIcon } from './ActivityIcon';
import { ACTIVITY_COLORS } from '../constants';
import { Play, Square, Navigation } from 'lucide-react';

interface TrackerProps {
  isTracking: boolean;
  onToggleTracking: () => void;
  currentActivity: ActivityType;
  currentSpeed: number; // m/s
  currentCoords: GeoPosition | null;
  duration: number; // seconds
  distance: number; // meters
  unitSystem: UnitSystem;
  manualActivityMode?: ActivityType | 'AUTO';
}

export const Tracker: React.FC<TrackerProps> = ({
  isTracking,
  onToggleTracking,
  currentActivity,
  currentSpeed,
  currentCoords,
  duration,
  distance,
  unitSystem,
  manualActivityMode = 'AUTO',
}) => {
  const speed =
    unitSystem === 'METRIC'
      ? { value: (currentSpeed * 3.6).toFixed(1), label: 'km/h' }
      : { value: (currentSpeed * 2.23694).toFixed(1), label: 'mph' };

  const distanceDisplay =
    unitSystem === 'METRIC'
      ? { value: (distance / 1000).toFixed(2), label: 'km' }
      : { value: (distance / 1609.34).toFixed(2), label: 'mi' };
  
  const altitudeDisplay = currentCoords?.altitude !== null && currentCoords?.altitude !== undefined
    ? unitSystem === 'METRIC'
      ? { value: Math.round(currentCoords.altitude), label: 'm' }
      : { value: Math.round(currentCoords.altitude * 3.28084), label: 'ft' }
    : null;

  const formatTime = (secs: number) => {
    // Round to whole seconds
    const totalSeconds = Math.floor(secs);
    const h = Math.floor(totalSeconds / 3600);
    const m = Math.floor((totalSeconds % 3600) / 60);
    const s = totalSeconds % 60;
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const currentColor = ACTIVITY_COLORS[currentActivity];

  const handleButtonPress = useCallback((e?: React.MouseEvent<HTMLButtonElement> | React.TouchEvent<HTMLButtonElement>) => {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }
    console.log("Play button pressed, isTracking:", isTracking); // Debug log
    onToggleTracking();
  }, [isTracking, onToggleTracking]);

  // Use ref to track if touch started on this button and prevent ghost clicks
  const touchStartedRef = useRef(false);
  const buttonRef = useRef<HTMLButtonElement>(null);

  return (
    <div className="flex flex-col items-center justify-center w-full max-w-md mx-auto p-4 sm:p-6 relative" style={{ pointerEvents: 'auto', zIndex: 10 }}>
      
      {/* Speedometer Circle */}
      <div className="relative w-56 h-56 sm:w-64 sm:h-64 mb-6 sm:mb-8 flex items-center justify-center">
        {/* Animated pulse ring if tracking */}
        {isTracking && (
          <div 
            className="absolute inset-0 rounded-full opacity-20 animate-ping"
            style={{ backgroundColor: currentColor }}
          />
        )}
        
        <div 
          className="w-full h-full rounded-full border-8 border-slate-800 flex flex-col items-center justify-center bg-slate-900 shadow-2xl relative overflow-hidden transition-colors duration-500"
          style={{ borderColor: isTracking ? currentColor : '#1e293b' }}
        >
          <div className="z-10 flex flex-col items-center">
            <span className="text-4xl sm:text-5xl font-bold font-mono tracking-tighter text-white whitespace-nowrap tabular-nums">
              {speed.value}
            </span>
            <span className="text-slate-400 text-xs sm:text-sm font-semibold uppercase mt-1 whitespace-nowrap">{speed.label}</span>
            
            <div className="mt-4 flex items-center space-x-2 text-slate-300 bg-slate-800/50 px-3 py-1 rounded-full">
              <ActivityIcon type={currentActivity} size={16} />
              <span className="text-xs font-bold tracking-wide">{currentActivity}</span>
              {manualActivityMode !== 'AUTO' && (
                <span className="ml-1 px-2 py-0.5 bg-indigo-500/20 border border-indigo-500/40 rounded-full text-[10px] text-indigo-300 font-semibold">
                  MANUAL
                </span>
              )}
            </div>
          </div>

          {/* Background fill based on speed/activity intensity */}
          <div 
            className="absolute bottom-0 w-full bg-gradient-to-t from-white/10 to-transparent transition-all duration-300"
            style={{ height: `${Math.min((currentSpeed / 30) * 100, 100)}%` }} // Cap visual at 30m/s (~100kmh) for effect
          />
        </div>
      </div>

      {/* Stats Grid */}
      <div className={`grid ${altitudeDisplay && currentActivity === ActivityType.FLYING ? 'grid-cols-1 sm:grid-cols-3' : 'grid-cols-2'} gap-3 sm:gap-4 w-full mb-6 sm:mb-8`}>
        <div className="bg-slate-800 rounded-xl p-3 sm:p-4 flex flex-col items-center border border-slate-700">
          <span className="text-slate-400 text-xs uppercase mb-1">Duration</span>
          <span className="text-xl sm:text-2xl font-bold text-white font-mono whitespace-nowrap tabular-nums">{formatTime(duration)}</span>
        </div>
        <div className="bg-slate-800 rounded-xl p-3 sm:p-4 flex flex-col items-center border border-slate-700">
          <span className="text-slate-400 text-xs uppercase mb-1">Distance</span>
          <span className="text-xl sm:text-2xl font-bold text-white font-mono whitespace-nowrap tabular-nums">
            {distanceDisplay.value} <span className="text-xs sm:text-sm">{distanceDisplay.label}</span>
          </span>
        </div>
        {altitudeDisplay && currentActivity === ActivityType.FLYING && (
          <div className="bg-slate-800 rounded-xl p-3 sm:p-4 flex flex-col items-center border border-blue-500/30 shadow-lg shadow-blue-500/10">
            <span className="text-blue-400 text-xs uppercase mb-1">Altitude</span>
            <span className="text-xl sm:text-2xl font-bold text-white font-mono whitespace-nowrap tabular-nums">
              {altitudeDisplay.value} <span className="text-xs sm:text-sm">{altitudeDisplay.label}</span>
            </span>
          </div>
        )}
      </div>

      {/* Location Badge */}
      {currentCoords && (
         <div className="flex flex-col items-center space-y-1 mb-8 bg-slate-900/50 px-4 py-2 rounded-lg border border-slate-800">
            <div className="flex items-center space-x-2 text-slate-500 text-[10px] sm:text-xs">
              <Navigation size={12} />
              <span>
                LAT: {currentCoords.latitude.toFixed(4)} • LON: {currentCoords.longitude.toFixed(4)}
              </span>
            </div>
            {currentCoords.altitude !== null && (
              <div className="text-[10px] sm:text-xs text-slate-400 font-medium">
                ALT: {altitudeDisplay?.value} {altitudeDisplay?.label}
              </div>
            )}
         </div>
      )}

      {/* Controls with Pulsating Rings */}
      <div className="relative flex items-center justify-center" style={{ zIndex: 10000, pointerEvents: 'auto' }}>
        {/* Pulsating Rings - Only show when NOT tracking */}
        {!isTracking && (
          <>
            {/* Ring 1 - Fastest, smallest expansion */}
            <div 
              className="absolute rounded-full border-2 animate-pulse-ring"
              style={{
                width: '80px',
                height: '80px',
                borderColor: 'rgba(99, 102, 241, 0.4)',
                animation: 'pulseRing1 2s cubic-bezier(0.4, 0, 0.6, 1) infinite',
              }}
            />
            
            {/* Ring 2 - Medium speed and expansion */}
            <div 
              className="absolute rounded-full border-2"
              style={{
                width: '80px',
                height: '80px',
                borderColor: 'rgba(99, 102, 241, 0.3)',
                animation: 'pulseRing2 2s cubic-bezier(0.4, 0, 0.6, 1) infinite 0.5s',
              }}
            />
            
            {/* Ring 3 - Slowest, largest expansion */}
            <div 
              className="absolute rounded-full border-2"
              style={{
                width: '80px',
                height: '80px',
                borderColor: 'rgba(99, 102, 241, 0.2)',
                animation: 'pulseRing3 2s cubic-bezier(0.4, 0, 0.6, 1) infinite 1s',
              }}
            />
          </>
        )}

        {/* Tracking Active Pulse Ring */}
        {isTracking && (
          <div 
            className="absolute rounded-full"
            style={{
              width: '120px',
              height: '120px',
              background: 'radial-gradient(circle, rgba(239, 68, 68, 0.2) 0%, rgba(239, 68, 68, 0) 70%)',
              animation: 'activeTrackingPulse 1.5s ease-in-out infinite',
            }}
          />
        )}
        
        {/* Main Button */}
        <button
          ref={buttonRef}
          onClick={(e) => {
            // Only handle click if not from a touch event (prevents double-firing)
            if (!touchStartedRef.current) {
              e.preventDefault();
              e.stopPropagation();
              console.log("onClick triggered (mouse)");
              handleButtonPress(e);
            }
          }}
          onTouchStart={(e) => {
            // Prevent default to stop scrolling and other default behaviors
            e.preventDefault();
            e.stopPropagation();
            touchStartedRef.current = true;
            console.log("onTouchStart triggered");
            
            // Visual feedback
            const target = e.currentTarget;
            target.style.transform = 'scale(0.95)';
            target.style.opacity = '0.9';
          }}
          onTouchEnd={(e) => {
            e.preventDefault();
            e.stopPropagation();
            
            console.log("onTouchEnd triggered, touchStarted:", touchStartedRef.current);
            
            const target = e.currentTarget;
            target.style.transform = '';
            target.style.opacity = '';
            
            // Only trigger if touch started and ended on this button
            if (touchStartedRef.current) {
              touchStartedRef.current = false;
              console.log("Calling onToggleTracking from touch");
              
              // Small delay to prevent ghost clicks
              setTimeout(() => {
                onToggleTracking();
              }, 0);
            }
          }}
          onTouchCancel={(e) => {
            e.preventDefault();
            e.stopPropagation();
            touchStartedRef.current = false;
            console.log("onTouchCancel triggered");
            const target = e.currentTarget;
            target.style.transform = '';
            target.style.opacity = '';
          }}
          onMouseDown={(e) => {
            // For desktop, reset touch flag
            touchStartedRef.current = false;
          }}
          className={`
            w-20 h-20 sm:w-24 sm:h-24 rounded-full flex items-center justify-center text-white shadow-2xl transition-all transform
            relative cursor-pointer select-none touch-manipulation z-10
            ${isTracking 
              ? 'bg-red-500 hover:bg-red-600 active:bg-red-700 shadow-red-500/50' 
              : 'bg-indigo-500 hover:bg-indigo-600 active:bg-indigo-700 shadow-indigo-500/50'
            }
          `}
          style={{
            WebkitTapHighlightColor: 'transparent',
            touchAction: 'manipulation',
            userSelect: 'none',
            WebkitUserSelect: 'none',
            WebkitTouchCallout: 'none',
            position: 'relative',
            zIndex: 10,
            pointerEvents: 'auto',
            minWidth: '80px',
            minHeight: '80px',
            WebkitAppearance: 'none',
            appearance: 'none',
          }}
          aria-label={isTracking ? "Stop tracking" : "Start tracking"}
          type="button"
        >
          {isTracking ? <Square size={32} fill="currentColor" /> : <Play size={32} fill="currentColor" className="ml-1" />}
        </button>
      </div>
      <p className="mt-4 text-slate-400 text-sm">
        {isTracking ? 'Tracking active...' : 'Tap to start tracking'}
      </p>
      
      {/* Manual Mode Indicator */}
      {manualActivityMode !== 'AUTO' && (
        <div className="mt-3 bg-indigo-900/30 border border-indigo-700/50 rounded-lg px-4 py-2 text-center">
          <p className="text-xs text-indigo-300">
            🎯 <strong>Manual Mode:</strong> Tracking as <strong>{manualActivityMode}</strong>
          </p>
          <p className="text-[10px] text-indigo-400 mt-1">
            Tap the activity button to change mode
          </p>
        </div>
      )}
      
      {/* CSS Animations */}
      <style jsx>{`
        @keyframes pulseRing1 {
          0% {
            transform: scale(1);
            opacity: 0.8;
          }
          100% {
            transform: scale(1.8);
            opacity: 0;
          }
        }
        
        @keyframes pulseRing2 {
          0% {
            transform: scale(1);
            opacity: 0.6;
          }
          100% {
            transform: scale(2.2);
            opacity: 0;
          }
        }
        
        @keyframes pulseRing3 {
          0% {
            transform: scale(1);
            opacity: 0.4;
          }
          100% {
            transform: scale(2.6);
            opacity: 0;
          }
        }
        
        @keyframes activeTrackingPulse {
          0%, 100% {
            transform: scale(0.95);
            opacity: 0.5;
          }
          50% {
            transform: scale(1.1);
            opacity: 0.8;
          }
        }
      `}</style>
    </div>
  );
};
