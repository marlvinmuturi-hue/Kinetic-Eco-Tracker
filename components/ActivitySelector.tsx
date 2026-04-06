import React, { useState } from 'react';
import { ActivityType } from '../types';
import { Activity, Footprints, Zap, Car, Plane, X } from 'lucide-react';

interface ActivitySelectorProps {
  isOpen: boolean;
  onClose: () => void;
  onSelectActivity: (activity: ActivityType | 'AUTO') => void;
  selectedMode: ActivityType | 'AUTO';
  isTracking: boolean;
}

export const ActivitySelector: React.FC<ActivitySelectorProps> = ({
  isOpen,
  onClose,
  onSelectActivity,
  selectedMode,
  isTracking,
}) => {
  const activities: Array<{ type: ActivityType | 'AUTO'; label: string; icon: React.ReactNode; color: string; description: string }> = [
    {
      type: 'AUTO',
      label: 'Auto Detect',
      icon: <Activity size={24} />,
      color: 'from-blue-500 to-cyan-500',
      description: 'Automatically detect activity based on speed'
    },
    {
      type: ActivityType.WALKING,
      label: 'Walking',
      icon: <Footprints size={24} />,
      color: 'from-green-500 to-emerald-500',
      description: 'Track walking activity (saves CO2)'
    },
    {
      type: ActivityType.RUNNING,
      label: 'Running',
      icon: <Activity size={24} />,
      color: 'from-emerald-500 to-green-600',
      description: 'Track running activity (saves CO2)'
    },
    {
      type: ActivityType.CYCLING,
      label: 'Cycling',
      icon: (
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="18.5" cy="17.5" r="3.5"/>
          <circle cx="5.5" cy="17.5" r="3.5"/>
          <circle cx="15" cy="5" r="1"/>
          <path d="M12 17.5V14l-3-3 4-3 2 3h2"/>
        </svg>
      ),
      color: 'from-cyan-500 to-blue-500',
      description: 'Track cycling activity (saves CO2)'
    },
    {
      type: ActivityType.DRIVING,
      label: 'Driving',
      icon: <Car size={24} />,
      color: 'from-amber-500 to-orange-500',
      description: 'Track gas/diesel vehicle (emits CO2)'
    },
    {
      type: ActivityType.ELECTRIC_VEHICLE,
      label: 'Electric Vehicle',
      icon: <Zap size={24} />,
      color: 'from-violet-500 to-purple-500',
      description: 'Track EV (70% less CO2 than gas)'
    },
    {
      type: ActivityType.FLYING,
      label: 'Flying',
      icon: <Plane size={24} />,
      color: 'from-blue-500 to-indigo-500',
      description: 'Track air travel (high CO2)'
    },
  ];

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm animate-fadeIn">
      <div className="bg-slate-800 rounded-2xl shadow-2xl max-w-md w-full max-h-[80vh] overflow-y-auto border border-slate-700 animate-slideUp">
        {/* Header */}
        <div className="sticky top-0 bg-slate-800 border-b border-slate-700 p-4 flex items-center justify-between z-10">
          <h2 className="text-xl font-bold text-white">Select Activity Mode</h2>
          <button
            onClick={onClose}
            className="p-2 hover:bg-slate-700 rounded-lg transition-colors touch-manipulation"
            aria-label="Close"
          >
            <X size={24} className="text-slate-400" />
          </button>
        </div>

        {/* Info Banner */}
        <div className="p-4 bg-blue-900/30 border-b border-blue-800/50">
          <p className="text-sm text-blue-200">
            {isTracking 
              ? '🎯 Select an activity to lock in manual tracking mode'
              : '💡 Choose how you want to track your movement'}
          </p>
        </div>

        {/* Activity Grid */}
        <div className="p-4 space-y-3">
          {activities.map((activity) => {
            const isSelected = selectedMode === activity.type;
            
            return (
              <button
                key={activity.type}
                onClick={() => {
                  onSelectActivity(activity.type);
                  onClose();
                }}
                className={`w-full p-4 rounded-xl border-2 transition-all duration-200 touch-manipulation ${
                  isSelected
                    ? 'border-white bg-slate-700 shadow-lg scale-[1.02]'
                    : 'border-slate-700 bg-slate-800/50 hover:bg-slate-700/50 hover:border-slate-600'
                }`}
              >
                <div className="flex items-start space-x-4">
                  {/* Icon */}
                  <div className={`flex-shrink-0 w-14 h-14 rounded-xl bg-gradient-to-br ${activity.color} flex items-center justify-center text-white shadow-lg`}>
                    {activity.icon}
                  </div>
                  
                  {/* Content */}
                  <div className="flex-grow text-left">
                    <div className="flex items-center space-x-2">
                      <h3 className="font-bold text-white">{activity.label}</h3>
                      {isSelected && (
                        <span className="px-2 py-0.5 bg-green-500/20 border border-green-500/40 rounded-full text-xs text-green-300 font-semibold">
                          ACTIVE
                        </span>
                      )}
                    </div>
                    <p className="text-sm text-slate-400 mt-1">{activity.description}</p>
                  </div>
                </div>
              </button>
            );
          })}
        </div>

        {/* Footer */}
        <div className="sticky bottom-0 bg-slate-800 border-t border-slate-700 p-4">
          <button
            onClick={onClose}
            className="w-full py-3 bg-slate-700 hover:bg-slate-600 text-white font-semibold rounded-xl transition-colors touch-manipulation"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
};



