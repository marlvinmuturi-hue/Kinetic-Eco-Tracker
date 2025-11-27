import React from 'react';
import { Settings, User, LogOut, Menu, X } from 'lucide-react';
import { UnitSystem } from '../types';

interface FloatingMenuProps {
  open: boolean;
  onToggle: () => void;
  onShowSettings: () => void;
  onShowProfile: () => void;
  onLogout: () => void;
  unitSystem: UnitSystem;
  onUnitQuickToggle: () => void;
  isAuthenticated: boolean;
  onLoginClick?: () => void;
}

export const FloatingMenu: React.FC<FloatingMenuProps> = ({
  open,
  onToggle,
  onShowSettings,
  onShowProfile,
  onLogout,
  unitSystem,
  onUnitQuickToggle,
  isAuthenticated,
  onLoginClick
}) => {
  return (
    <div className="fixed bottom-6 right-6 z-[60]">
      <div
        className={`origin-bottom-right transform transition-all duration-200 ${
          open ? 'scale-100 opacity-100 translate-y-0' : 'scale-95 opacity-0 translate-y-2 pointer-events-none'
        }`}
      >
        <div className="bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl shadow-slate-900/60 p-4 w-64 space-y-3">
          <p className="text-xs text-slate-500 uppercase tracking-wide">Quick actions</p>
          {isAuthenticated ? (
            <>
              <ActionButton icon={<Settings size={18} />} label="Open settings" onClick={onShowSettings} />
              <ActionButton icon={<User size={18} />} label="View profile" onClick={onShowProfile} />
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
        className="mt-3 w-14 h-14 rounded-full bg-gradient-to-br from-green-400 to-blue-500 text-white shadow-xl shadow-green-500/30 flex items-center justify-center hover:scale-105 transition"
        aria-label="Open quick settings"
      >
        {open ? <X /> : <Menu />}
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
    className={`w-full flex items-center space-x-3 rounded-xl px-3 py-2 text-sm font-semibold transition border ${
      variant === 'danger'
        ? 'border-red-500/40 bg-red-500/10 text-red-300 hover:bg-red-500/20'
        : 'border-slate-800 bg-slate-800/60 text-slate-200 hover:border-slate-700'
    }`}
  >
    {icon}
    <span>{label}</span>
  </button>
);

