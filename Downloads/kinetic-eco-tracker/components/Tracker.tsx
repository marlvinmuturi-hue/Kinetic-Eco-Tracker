import React from 'react';
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
}) => {
  const speed =
    unitSystem === 'METRIC'
      ? { value: (currentSpeed * 3.6).toFixed(1), label: 'km/h' }
      : { value: (currentSpeed * 2.23694).toFixed(1), label: 'mph' };

  const distanceDisplay =
    unitSystem === 'METRIC'
      ? { value: (distance / 1000).toFixed(2), label: 'km' }
      : { value: (distance / 1609.34).toFixed(2), label: 'mi' };
  
  const formatTime = (secs: number) => {
    const h = Math.floor(secs / 3600);
    const m = Math.floor((secs % 3600) / 60);
    const s = secs % 60;
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const currentColor = ACTIVITY_COLORS[currentActivity];

  return (
    <div className="flex flex-col items-center justify-center w-full max-w-md mx-auto p-6">
      
      {/* Speedometer Circle */}
      <div className="relative w-64 h-64 mb-8 flex items-center justify-center">
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
            <span className="text-5xl font-bold font-mono tracking-tighter text-white">
              {speed.value}
            </span>
            <span className="text-slate-400 text-sm font-semibold uppercase mt-1">{speed.label}</span>
            
            <div className="mt-4 flex items-center space-x-2 text-slate-300 bg-slate-800/50 px-3 py-1 rounded-full">
              <ActivityIcon type={currentActivity} size={16} />
              <span className="text-xs font-bold tracking-wide">{currentActivity}</span>
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
      <div className="grid grid-cols-2 gap-4 w-full mb-8">
        <div className="bg-slate-800 rounded-xl p-4 flex flex-col items-center border border-slate-700">
          <span className="text-slate-400 text-xs uppercase mb-1">Duration</span>
          <span className="text-2xl font-bold text-white font-mono">{formatTime(duration)}</span>
        </div>
        <div className="bg-slate-800 rounded-xl p-4 flex flex-col items-center border border-slate-700">
          <span className="text-slate-400 text-xs uppercase mb-1">Distance</span>
          <span className="text-2xl font-bold text-white font-mono">
            {distanceDisplay.value} <span className="text-sm">{distanceDisplay.label}</span>
          </span>
        </div>
      </div>

      {/* Location Badge */}
      {currentCoords && (
         <div className="flex items-center space-x-2 text-slate-500 text-xs mb-8 bg-slate-900/50 px-3 py-1 rounded border border-slate-800">
            <Navigation size={12} />
            <span>
              LAT: {currentCoords.latitude.toFixed(4)} • LON: {currentCoords.longitude.toFixed(4)}
            </span>
         </div>
      )}

      {/* Controls */}
      <button
        onClick={onToggleTracking}
        className={`
          w-20 h-20 rounded-full flex items-center justify-center text-white shadow-lg transition-all transform active:scale-95
          ${isTracking 
            ? 'bg-red-500 hover:bg-red-600 shadow-red-500/20' 
            : 'bg-indigo-500 hover:bg-indigo-600 shadow-indigo-500/20'
          }
        `}
      >
        {isTracking ? <Square size={32} fill="currentColor" /> : <Play size={32} fill="currentColor" className="ml-1" />}
      </button>
      <p className="mt-4 text-slate-400 text-sm">
        {isTracking ? 'Tracking active...' : 'Ready to start'}
      </p>
    </div>
  );
};
