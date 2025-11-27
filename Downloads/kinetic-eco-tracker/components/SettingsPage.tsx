import React from 'react';
import { UnitSystem, UserProfile } from '../types';
import { Settings, ThermometerSun, Footprints, User } from 'lucide-react';

interface SettingsPageProps {
  unitSystem: UnitSystem;
  onUnitChange: (unit: UnitSystem) => void;
  onNavigateProfile: () => void;
  onLogout: () => void;
  profile: UserProfile;
}

export const SettingsPage: React.FC<SettingsPageProps> = ({
  unitSystem,
  onUnitChange,
  onNavigateProfile,
  onLogout,
  profile,
}) => {
  return (
    <div className="w-full max-w-3xl mx-auto p-6 space-y-6">
      <header className="flex items-center space-x-3">
        <div className="bg-slate-800 text-green-300 p-3 rounded-xl border border-slate-700">
          <Settings size={24} />
        </div>
        <div>
          <h2 className="text-3xl font-bold text-white">Settings</h2>
          <p className="text-slate-400 text-sm">Customize how Kinetic feels for you.</p>
        </div>
      </header>

      <section className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
        <div className="flex items-center justify-between mb-4">
          <div>
            <p className="text-white font-semibold">Speed units</p>
            <p className="text-slate-400 text-sm">Switch between km/h and mph.</p>
          </div>
          <ThermometerSun className="text-slate-500" />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <UnitToggle
            label="Kilometers per hour"
            unit="METRIC"
            activeUnit={unitSystem}
            onClick={onUnitChange}
          />
          <UnitToggle
            label="Miles per hour"
            unit="IMPERIAL"
            activeUnit={unitSystem}
            onClick={onUnitChange}
          />
        </div>
      </section>

      <section className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
        <h3 className="text-lg font-semibold text-white mb-4">Profile & sessions</h3>
        <div className="flex items-center justify-between mb-4">
          <div>
            <p className="text-white">{profile.email}</p>
            <p className="text-slate-500 text-xs">Member since {new Date(profile.createdAt).toLocaleDateString()}</p>
          </div>
          <button
            onClick={onNavigateProfile}
            className="flex items-center space-x-2 text-sm bg-slate-800 px-4 py-2 rounded-lg border border-slate-700 hover:bg-slate-700 transition"
          >
            <User size={16} />
            <span>View profile</span>
          </button>
        </div>
        <button
          onClick={onLogout}
          className="w-full bg-red-500/20 text-red-300 border border-red-500/30 rounded-xl py-3 text-sm font-semibold hover:bg-red-500/30 transition"
        >
          Logout
        </button>
      </section>

      <section className="bg-slate-900 border border-slate-800 rounded-2xl p-5">
        <div className="flex items-center space-x-3 mb-3">
          <Footprints className="text-green-300" />
          <div>
            <p className="text-white font-semibold">Upcoming features</p>
            <p className="text-slate-400 text-xs">Stay tuned for cloud sync, reminders, and goal tracking.</p>
          </div>
        </div>
        <p className="text-slate-500 text-sm">
          Let us know what would make daily sustainability tracking effortless for you.
        </p>
      </section>
    </div>
  );
};

interface UnitToggleProps {
  label: string;
  unit: UnitSystem;
  activeUnit: UnitSystem;
  onClick: (unit: UnitSystem) => void;
}

const UnitToggle: React.FC<UnitToggleProps> = ({ label, unit, activeUnit, onClick }) => {
  const isActive = unit === activeUnit;
  return (
    <button
      onClick={() => onClick(unit)}
      className={`rounded-xl border px-4 py-3 text-sm font-semibold transition ${
        isActive
          ? 'border-green-400 bg-green-500/10 text-green-200'
          : 'border-slate-700 bg-slate-800 text-slate-300 hover:border-slate-600'
      }`}
    >
      {label}
    </button>
  );
};

