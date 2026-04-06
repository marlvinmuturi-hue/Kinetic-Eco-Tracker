import React from 'react';
import { Activity } from 'lucide-react';

interface FloatingActivityButtonProps {
  onClick: () => void;
  isTracking: boolean;
}

export const FloatingActivityButton: React.FC<FloatingActivityButtonProps> = ({
  onClick,
  isTracking,
}) => {
  return (
    <button
      onClick={onClick}
      className="fixed bottom-24 right-6 z-40 w-16 h-16 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-full shadow-2xl flex items-center justify-center text-white hover:scale-110 active:scale-95 transition-transform touch-manipulation"
      aria-label="Select Activity"
      style={{ pointerEvents: 'auto' }}
    >
      {/* Pulse animation ring */}
      <div className="absolute inset-0 rounded-full bg-indigo-400 opacity-30 animate-ping" />
      
      {/* Icon */}
      <Activity size={28} className="relative z-10" />
      
      {/* Badge if tracking */}
      {isTracking && (
        <div className="absolute -top-1 -right-1 w-5 h-5 bg-green-500 rounded-full border-2 border-slate-900 flex items-center justify-center">
          <div className="w-2 h-2 bg-white rounded-full animate-pulse" />
        </div>
      )}
    </button>
  );
};



