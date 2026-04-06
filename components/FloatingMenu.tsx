import React from 'react';
import { Settings, User, LogOut, Menu, X, Play, Pause, MessageCircle } from 'lucide-react';
import { UnitSystem } from '../types';

interface FloatingMenuProps {
  open: boolean;
  onToggle: () => void;
  onShowSettings: () => void;
  onShowFeedback: () => void;
  onLogout: () => void;
  onPlayPause: () => void;
  isTracking: boolean;
  unitSystem: UnitSystem;
  onUnitQuickToggle: () => void;
  isAuthenticated: boolean;
  onLoginClick?: () => void;
}

export const FloatingMenu: React.FC<FloatingMenuProps> = ({
  open,
  onToggle,
  onShowSettings,
  onShowFeedback,
  onLogout,
  onPlayPause,
  isTracking,
  unitSystem,
  onUnitQuickToggle,
  isAuthenticated,
  onLoginClick
}) => {
  return (
    <div className="fixed bottom-4 left-4 sm:bottom-6 sm:left-6 z-[60] safe-area-bottom">
      <div
        className={`origin-bottom-left transform transition-all duration-200 ${
          open ? 'scale-100 opacity-100 translate-y-0' : 'scale-95 opacity-0 translate-y-2 pointer-events-none'
        }`}
      >
        <div className="bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl shadow-slate-900/60 p-3 sm:p-4 w-56 sm:w-64 space-y-2 sm:space-y-3">
          <p className="text-xs text-slate-500 uppercase tracking-wide">Quick actions</p>
          {isAuthenticated ? (
            <>
              <ActionButton
                icon={isTracking ? <Pause size={18} /> : <Play size={18} />}
                label={isTracking ? 'Pause activity' : 'Start activity'}
                onClick={onPlayPause}
              />
              <ActionButton icon={<Settings size={18} />} label="Open settings" onClick={onShowSettings} />
              <ActionButton icon={<MessageCircle size={18} />} label="Send feedback" onClick={onShowFeedback} />
              <ActionButton
                icon={<Settings size={18} />}
                label={`Switch to ${unitSystem === 'METRIC' ? 'mph' : 'km/h'}`}
                onClick={onUnitQuickToggle}
              />
              <ActionButton icon={<LogOut size={18} />} label="Logout" onClick={onLogout} variant="danger" />
            </>
          ) : (
            <ActionButton icon={<User size={18} />} label="Login" onClick={onLoginClick ?? (() => {})} />
          )}
        </div>
      </div>
      <button
        onClick={onToggle}
        className="mt-3 w-14 h-14 sm:w-16 sm:h-16 rounded-full bg-gradient-to-br from-green-400 to-blue-500 text-white shadow-xl shadow-green-500/30 flex items-center justify-center hover:scale-105 active:scale-95 transition touch-manipulation min-h-[56px] min-w-[56px]"
        aria-label="Open quick settings"
      >
        {open ? <X size={24} /> : <Menu size={24} />}
      </button>
    </div>
  );
};

interface ActionButtonProps {
  icon: React.ReactNode;
  label: string;
  onClick: () => void;
  variant?: 'default' | 'danger';
}

const ActionButton: React.FC<ActionButtonProps> = ({ icon, label, onClick, variant = 'default' }) => (
  <button
    onClick={onClick}
    className={`w-full flex items-center space-x-3 rounded-xl px-3 py-3 sm:py-2 text-sm font-semibold transition border min-h-[44px] touch-manipulation active:opacity-80 ${
      variant === 'danger'
        ? 'border-red-500/40 bg-red-500/10 text-red-300 hover:bg-red-500/20 active:bg-red-500/30'
        : 'border-slate-800 bg-slate-800/60 text-slate-200 hover:border-slate-700 active:bg-slate-700/60'
    }`}
  >
    {icon}
    <span>{label}</span>
  </button>
);

