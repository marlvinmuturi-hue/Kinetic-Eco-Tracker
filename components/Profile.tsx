import React, { useMemo, useState } from 'react';
import { UserProfile, ActivityType, UserPhysicalProfile, AggregatedStats, Timeframe } from '../types';
import { ActivityIcon } from './ActivityIcon';
import { History, TrendingUp, Calendar, Clock, Award, Settings, User, Target, Cloud } from 'lucide-react';
import { updatePhysicalProfile } from '../services/profileService';
import { batchSaveSessionsToFirestore } from '../services/firestoreSessionService';
import { AchievementsDisplay } from './AchievementsDisplay';
import { GoalsDisplay } from './GoalsDisplay';

interface ProfileProps {
  profile: UserProfile;
  onLogout: () => void;
  onUpdateProfile: (profile: UserProfile) => void;
}

const formatDate = (iso: string) => new Date(iso).toLocaleDateString();

export const Profile: React.FC<ProfileProps> = ({ profile, onLogout, onUpdateProfile }) => {
  const [activeTab, setActiveTab] = useState<'history' | 'tracking' | 'achievements' | 'goals' | 'settings'>('history');
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);

  const getParsedDate = (dateStr: string) => {
    const [y, m, d] = dateStr.split('-').map(Number);
    return new Date(y, m - 1, d); // Local date
  };

  const now = new Date();
  const todayStr = now.toISOString().split('T')[0];

  const isSameDay = (dateStr: string) => {
    const d = getParsedDate(dateStr);
    return d.getDate() === now.getDate() && 
           d.getMonth() === now.getMonth() && 
           d.getFullYear() === now.getFullYear();
  };

  const isSameWeek = (dateStr: string) => {
    const d = getParsedDate(dateStr);
    const startOfWeek = new Date(now);
    startOfWeek.setDate(now.getDate() - now.getDay()); // Sunday
    startOfWeek.setHours(0, 0, 0, 0);
    
    const endOfWeek = new Date(startOfWeek);
    endOfWeek.setDate(startOfWeek.getDate() + 7);
    
    return d >= startOfWeek && d < endOfWeek;
  };

  const isSameMonth = (dateStr: string) => {
    const d = getParsedDate(dateStr);
    return d.getMonth() === now.getMonth() && d.getFullYear() === now.getFullYear();
  };

  const isSameYear = (dateStr: string) => {
    const d = getParsedDate(dateStr);
    return d.getFullYear() === now.getFullYear();
  };

  const aggregatedStats = useMemo(() => {
    const initStats = () => ({
      totalSessions: 0,
      totalDistance: 0,
      totalDuration: 0,
      totalEmissions: 0,
      totalConserved: 0,
      totalCalories: 0,
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

    const stats: Record<Timeframe, ReturnType<typeof initStats>> = {
      'Daily': initStats(),
      'Weekly': initStats(),
      'Monthly': initStats(),
      'Yearly': initStats(),
      'Since Beginning': initStats(),
    };

    profile.sessions.forEach(session => {
      const s = session.stats;
      const date = session.date;

      const addTo = (tf: Timeframe) => {
        stats[tf].totalSessions += 1;
        stats[tf].totalDistance += s.totalDistance;
        stats[tf].totalDuration += s.totalDuration;
        stats[tf].totalEmissions += s.co2Emissions;
        stats[tf].totalConserved += s.co2Conserved;
        stats[tf].totalCalories += s.caloriesBurned;
        
        // Accumulate breakdown
        Object.entries(s.breakdown).forEach(([type, data]) => {
          stats[tf].breakdown[type as ActivityType].time += data.time;
          stats[tf].breakdown[type as ActivityType].distance += data.distance;
        });
      };

      addTo('Since Beginning');
      if (isSameDay(date)) addTo('Daily');
      if (isSameWeek(date)) addTo('Weekly');
      if (isSameMonth(date)) addTo('Monthly');
      if (isSameYear(date)) addTo('Yearly');
    });

    return stats;
  }, [profile.sessions]);

  const currentStats = aggregatedStats['Since Beginning'];
  
  // Get selected session if viewing individual session
  const selectedSession = selectedSessionId 
    ? profile.sessions.find(s => s.id === selectedSessionId)
    : null;

  // Display stats: individual session or accumulated
  const displayStats = selectedSession 
    ? {
        totalSessions: 1,
        totalDistance: selectedSession.stats.totalDistance,
        totalDuration: selectedSession.stats.totalDuration,
        totalEmissions: selectedSession.stats.co2Emissions,
        totalConserved: selectedSession.stats.co2Conserved,
        totalCalories: selectedSession.stats.caloriesBurned,
        breakdown: selectedSession.stats.breakdown
      }
    : currentStats;

  return (
    <div className="w-full max-w-5xl mx-auto p-4 sm:p-6 pb-24">
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 mb-8">
        <div>
          <h2 className="text-3xl font-bold text-white">
            {selectedSession ? 'Session Details' : 'Your Profile'}
          </h2>
          {selectedSession ? (
            <>
              <p className="text-slate-400 text-sm mt-2">
                {new Date(selectedSession.date).toLocaleDateString('en-US', { 
                  weekday: 'long', 
                  year: 'numeric', 
                  month: 'long', 
                  day: 'numeric' 
                })}
              </p>
              <button
                onClick={() => setSelectedSessionId(null)}
                className="text-blue-400 hover:text-blue-300 text-xs mt-1 flex items-center gap-1"
              >
                ← Back to All Sessions
              </button>
            </>
          ) : (
            <>
              <p className="text-slate-400 text-sm mt-2">Signed in as {profile.email}</p>
              <p className="text-slate-500 text-xs">Member since {formatDate(profile.createdAt)}</p>
            </>
          )}
        </div>
        <button
          onClick={onLogout}
          className="self-start md:self-auto bg-slate-800 px-4 py-2 rounded-lg border border-slate-700 text-sm text-slate-300 hover:bg-slate-700 transition"
        >
          Logout
        </button>
      </div>

      {/* Tabs - Hide when viewing individual session */}
      {!selectedSession && (
        <div className="flex flex-wrap gap-1 bg-slate-800/50 p-1 rounded-xl mb-6">
          <button
            onClick={() => setActiveTab('history')}
            className={`flex-1 min-w-[100px] flex items-center justify-center space-x-1 py-2.5 px-2 text-xs sm:text-sm font-medium rounded-lg transition ${
              activeTab === 'history' 
                ? 'bg-slate-700 text-white shadow-lg' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
            }`}
          >
            <History size={16} />
            <span className="hidden sm:inline">Sessions</span>
          </button>
          <button
            onClick={() => setActiveTab('tracking')}
            className={`flex-1 min-w-[100px] flex items-center justify-center space-x-1 py-2.5 px-2 text-xs sm:text-sm font-medium rounded-lg transition ${
              activeTab === 'tracking' 
                ? 'bg-slate-700 text-white shadow-lg' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
            }`}
          >
            <TrendingUp size={16} />
            <span className="hidden sm:inline">Data</span>
          </button>
          <button
            onClick={() => setActiveTab('achievements')}
            className={`flex-1 min-w-[100px] flex items-center justify-center space-x-1 py-2.5 px-2 text-xs sm:text-sm font-medium rounded-lg transition ${
              activeTab === 'achievements' 
                ? 'bg-slate-700 text-white shadow-lg' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
            }`}
          >
            <Award size={16} />
            <span className="hidden sm:inline">Achievements</span>
          </button>
          <button
            onClick={() => setActiveTab('goals')}
            className={`flex-1 min-w-[100px] flex items-center justify-center space-x-1 py-2.5 px-2 text-xs sm:text-sm font-medium rounded-lg transition ${
              activeTab === 'goals' 
                ? 'bg-slate-700 text-white shadow-lg' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
            }`}
          >
            <Target size={16} />
            <span className="hidden sm:inline">Goals</span>
          </button>
          <button
            onClick={() => setActiveTab('settings')}
            className={`flex-1 min-w-[100px] flex items-center justify-center space-x-1 py-2.5 px-2 text-xs sm:text-sm font-medium rounded-lg transition ${
              activeTab === 'settings' 
                ? 'bg-slate-700 text-white shadow-lg' 
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
            }`}
          >
            <Settings size={16} />
            <span className="hidden sm:inline">Settings</span>
          </button>
        </div>
      )}


      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3 sm:gap-4 mb-8">
        <StatCard 
          label={selectedSession ? "Session" : "Trips"} 
          value={selectedSession ? "Single Trip" : displayStats.totalSessions.toString()} 
          icon={<Calendar size={16} className="text-blue-400" />}
        />
        <StatCard 
          label="Distance" 
          value={`${(displayStats.totalDistance / 1000).toFixed(1)} km`} 
          icon={<Award size={16} className="text-yellow-400" />}
        />
        <StatCard 
          label="CO₂ Saved" 
          value={`${displayStats.totalConserved.toFixed(2)} kg`} 
          icon={<Award size={16} className="text-green-400" />}
        />
        <StatCard 
          label="Time" 
          value={`${Math.round(displayStats.totalDuration / 60)} min`} 
          icon={<Clock size={16} className="text-purple-400" />}
        />
        <StatCard 
          label="Calories" 
          value={`${Math.round(displayStats.totalCalories)} kcal`} 
          icon={<Award size={16} className="text-orange-400" />}
        />
      </div>

      <section>
        {selectedSession ? (
          // Individual session detail view
          <>
            <h3 className="text-xl text-white font-semibold mb-4">Session Overview</h3>
            <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 sm:p-6">
              <div className="space-y-6">
                <div>
                  <div className="flex justify-between items-end mb-4">
                    <div>
                      <p className="text-sm text-slate-400">Activity Distribution</p>
                      <p className="text-xs text-slate-500">This session only</p>
                    </div>
                  </div>
                  
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    {(Object.entries(displayStats.breakdown) as [ActivityType, { time: number; distance: number }][])
                      .filter(([_, data]) => data.time > 0)
                      .sort((a, b) => b[1].distance - a[1].distance)
                      .map(([type, data]) => (
                        <div key={type} className="flex items-center justify-between bg-slate-800/40 p-3 rounded-lg border border-slate-700/50">
                          <div className="flex items-center space-x-3">
                            <div className="bg-slate-700 p-2 rounded-md">
                              <ActivityIcon type={type} size={16} />
                            </div>
                            <div>
                              <p className="text-xs font-semibold text-white capitalize">{type.toLowerCase()}</p>
                              <p className="text-[10px] text-slate-500">{Math.round(data.time / 60)} min</p>
                            </div>
                          </div>
                          <div className="text-right">
                            <p className="text-xs font-bold text-slate-200">{(data.distance / 1000).toFixed(2)} km</p>
                            <p className="text-[10px] text-slate-500">
                              {displayStats.totalDistance > 0 
                                ? Math.round((data.distance / displayStats.totalDistance) * 100)
                                : 0}%
                            </p>
                          </div>
                        </div>
                      ))}
                  </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mt-6">
                  <div className="bg-slate-800/50 p-4 rounded-lg">
                    <p className="text-xs text-slate-500 uppercase mb-1">CO₂ Balance</p>
                    <div className="flex justify-between">
                      <span className="text-sm text-slate-300">Emitted:</span>
                      <span className="text-sm text-red-400 font-mono whitespace-nowrap">{displayStats.totalEmissions.toFixed(3)} kg</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-sm text-slate-300">Conserved:</span>
                      <span className="text-sm text-green-400 font-mono whitespace-nowrap">{displayStats.totalConserved.toFixed(3)} kg</span>
                    </div>
                    <div className="mt-2 pt-2 border-t border-slate-700 flex justify-between">
                      <span className="text-sm font-semibold text-white">Net Impact:</span>
                      <span className={`text-sm font-bold ${(displayStats.totalConserved - displayStats.totalEmissions) >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                        {(displayStats.totalConserved - displayStats.totalEmissions).toFixed(3)} kg
                      </span>
                    </div>
                  </div>

                  <div className="bg-slate-800/50 p-4 rounded-lg">
                    <p className="text-xs text-slate-500 uppercase mb-1">Session Stats</p>
                    <div className="flex justify-between">
                      <span className="text-sm text-slate-300">Total distance:</span>
                      <span className="text-sm text-white font-mono whitespace-nowrap">
                        {(displayStats.totalDistance / 1000).toFixed(2)} km
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-sm text-slate-300">Total duration:</span>
                      <span className="text-sm text-white font-mono whitespace-nowrap">
                        {Math.round(displayStats.totalDuration / 60)} min
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-sm text-slate-300">Calories burned:</span>
                      <span className="text-sm text-orange-400 font-mono whitespace-nowrap">
                        {Math.round(displayStats.totalCalories)} kcal
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </>
        ) : activeTab === 'history' ? (
          <>
            <h3 className="text-xl text-white font-semibold mb-4">All Sessions</h3>
            {profile.sessions.length === 0 ? (
              <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 text-slate-400 text-center">
                No activity logged yet. Start tracking to see your trends.
              </div>
            ) : (
              <ul className="space-y-4">
                {profile.sessions.map((session) => (
                  <li 
                    key={session.id} 
                    onClick={() => setSelectedSessionId(session.id)}
                    className="bg-slate-900 border border-slate-800 rounded-xl p-4 cursor-pointer hover:border-slate-700 hover:bg-slate-800/50 transition-all"
                  >
                    <div className="flex justify-between items-center mb-3">
                      <div>
                        <p className="text-white font-semibold">{new Date(session.date).toLocaleDateString('en-US', { 
                          weekday: 'short', 
                          year: 'numeric', 
                          month: 'short', 
                          day: 'numeric' 
                        })}</p>
                        <p className="text-xs text-slate-500">Tap to view details</p>
                      </div>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <div className="bg-slate-800/40 rounded-lg p-2.5">
                        <p className="text-[10px] text-slate-500 uppercase mb-0.5">Distance</p>
                        <p className="text-sm font-semibold text-white">{(session.stats.totalDistance / 1000).toFixed(2)} km</p>
                      </div>
                      <div className="bg-slate-800/40 rounded-lg p-2.5">
                        <p className="text-[10px] text-slate-500 uppercase mb-0.5">Duration</p>
                        <p className="text-sm font-semibold text-white">{Math.round(session.stats.totalDuration / 60)} min</p>
                      </div>
                      <div className="bg-slate-800/40 rounded-lg p-2.5">
                        <p className="text-[10px] text-slate-500 uppercase mb-0.5">Calories</p>
                        <p className="text-sm font-semibold text-orange-400">{Math.round(session.stats.caloriesBurned)} kcal</p>
                      </div>
                      <div className="bg-slate-800/40 rounded-lg p-2.5">
                        <p className="text-[10px] text-slate-500 uppercase mb-0.5">CO₂ Saved</p>
                        <p className="text-sm font-semibold text-green-400">{session.stats.co2Conserved.toFixed(2)} kg</p>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : activeTab === 'tracking' ? (
          <>
            <h3 className="text-xl text-white font-semibold mb-4">Tracking Insights</h3>
            <div className="bg-slate-900 border border-slate-800 rounded-xl p-4 sm:p-6">
              <div className="space-y-6">
                <div>
                  <div className="flex justify-between items-end mb-4">
                    <div>
                      <p className="text-sm text-slate-400">Activity Distribution</p>
                      <p className="text-xs text-slate-500">Based on {displayStats.totalSessions} sessions</p>
                    </div>
                  </div>
                  
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    {(Object.entries(displayStats.breakdown) as [ActivityType, { time: number; distance: number }][])
                      .filter(([_, data]) => data.time > 0)
                      .sort((a, b) => b[1].distance - a[1].distance)
                      .map(([type, data]) => (
                        <div key={type} className="flex items-center justify-between bg-slate-800/40 p-3 rounded-lg border border-slate-700/50">
                          <div className="flex items-center space-x-3">
                            <div className="bg-slate-700 p-2 rounded-md">
                              <ActivityIcon type={type} size={16} />
                            </div>
                            <div>
                              <p className="text-xs font-semibold text-white capitalize">{type.toLowerCase()}</p>
                              <p className="text-[10px] text-slate-500">{Math.round(data.time / 60)} min</p>
                            </div>
                          </div>
                          <div className="text-right">
                            <p className="text-xs font-bold text-slate-200">{(data.distance / 1000).toFixed(2)} km</p>
                            <p className="text-[10px] text-slate-500">
                              {displayStats.totalDistance > 0 
                                ? Math.round((data.distance / displayStats.totalDistance) * 100)
                                : 0}%
                            </p>
                          </div>
                        </div>
                      ))}
                    {displayStats.totalSessions === 0 && (
                      <div className="col-span-full py-8 text-center text-slate-500 text-sm italic">
                        No data recorded for this period.
                      </div>
                    )}
                  </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mt-6">
                  <div className="bg-slate-800/50 p-4 rounded-lg">
                    <p className="text-xs text-slate-500 uppercase mb-1">CO₂ Balance</p>
                    <div className="flex justify-between">
                      <span className="text-sm text-slate-300">Emitted:</span>
                      <span className="text-sm text-red-400 font-mono whitespace-nowrap">{displayStats.totalEmissions.toFixed(3)} kg</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-sm text-slate-300">Conserved:</span>
                      <span className="text-sm text-green-400 font-mono whitespace-nowrap">{displayStats.totalConserved.toFixed(3)} kg</span>
                    </div>
                    <div className="mt-2 pt-2 border-t border-slate-700 flex justify-between">
                      <span className="text-sm font-semibold text-white">Net Impact:</span>
                      <span className={`text-sm font-bold ${(displayStats.totalConserved - displayStats.totalEmissions) >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                        {(displayStats.totalConserved - displayStats.totalEmissions).toFixed(3)} kg
                      </span>
                    </div>
                  </div>

                  <div className="bg-slate-800/50 p-4 rounded-lg">
                    <p className="text-xs text-slate-500 uppercase mb-1">Averages</p>
                    <div className="flex justify-between">
                      <span className="text-sm text-slate-300">Per session:</span>
                      <span className="text-sm text-white font-mono whitespace-nowrap">
                        {displayStats.totalSessions > 0 
                          ? ((displayStats.totalDistance / 1000) / displayStats.totalSessions).toFixed(2)
                          : '0.00'} km
                      </span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-sm text-slate-300">CO₂ per km:</span>
                      <span className="text-sm text-white font-mono whitespace-nowrap">
                        {displayStats.totalDistance > 0
                          ? (displayStats.totalEmissions / (displayStats.totalDistance / 1000)).toFixed(3)
                          : '0.000'} kg
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </>
        ) : activeTab === 'achievements' ? (
          <AchievementsDisplay 
            profile={profile} 
            aggregatedStats={currentStats} 
          />
        ) : activeTab === 'goals' ? (
          <GoalsDisplay 
            profile={profile} 
            aggregatedStats={aggregatedStats}
            onUpdateGoals={(goals) => {
              const updatedProfile = { ...profile, goals };
              onUpdateProfile(updatedProfile);
            }}
          />
        ) : (
          <PhysicalProfileSettings profile={profile} onUpdateProfile={onUpdateProfile} />
        )}
      </section>
    </div>
  );
};

const PhysicalProfileSettings = ({ profile, onUpdateProfile }: { profile: UserProfile; onUpdateProfile: (profile: UserProfile) => void }) => {
  const [formData, setFormData] = useState<UserPhysicalProfile>({
    weight: profile.physicalProfile?.weight || 70,
    height: profile.physicalProfile?.height || 170,
    age: profile.physicalProfile?.age || 30,
    gender: profile.physicalProfile?.gender || 'male'
  });
  
  const [isSaving, setIsSaving] = useState(false);
  const [saveMessage, setSaveMessage] = useState<string | null>(null);
  
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsSaving(true);
    setSaveMessage(null);
    
    try {
      const updatedProfile = await updatePhysicalProfile(profile.email, formData);
      if (updatedProfile) {
        onUpdateProfile(updatedProfile);
      }
      setSaveMessage('✓ Physical profile saved successfully!');
      setTimeout(() => setSaveMessage(null), 3000);
    } catch (error) {
      setSaveMessage('✗ Failed to save physical profile');
    } finally {
      setIsSaving(false);
    }
  };
  
  return (
    <>
      <h3 className="text-xl text-white font-semibold mb-4 flex items-center gap-2">
        <User size={20} />
        Physical Profile Settings
      </h3>
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-6">
        <p className="text-sm text-slate-400 mb-6">
          Enter your physical details for accurate calorie calculations. Your data is stored locally and never shared.
        </p>
        
        <form onSubmit={handleSubmit} className="space-y-6">
          {/* Weight */}
          <div>
            <label htmlFor="weight" className="block text-sm font-medium text-slate-300 mb-2">
              Weight (kg)
            </label>
            <input
              type="number"
              id="weight"
              min="30"
              max="300"
              step="0.1"
              value={formData.weight}
              onChange={(e) => setFormData({ ...formData, weight: parseFloat(e.target.value) })}
              className="w-full bg-slate-800 border border-slate-700 rounded-lg px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-green-500"
              required
            />
            <p className="text-xs text-slate-500 mt-1">Your body weight in kilograms</p>
          </div>
          
          {/* Height */}
          <div>
            <label htmlFor="height" className="block text-sm font-medium text-slate-300 mb-2">
              Height (cm)
            </label>
            <input
              type="number"
              id="height"
              min="100"
              max="250"
              step="1"
              value={formData.height}
              onChange={(e) => setFormData({ ...formData, height: parseFloat(e.target.value) })}
              className="w-full bg-slate-800 border border-slate-700 rounded-lg px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-green-500"
              required
            />
            <p className="text-xs text-slate-500 mt-1">Your height in centimeters</p>
          </div>
          
          {/* Age */}
          <div>
            <label htmlFor="age" className="block text-sm font-medium text-slate-300 mb-2">
              Age (years)
            </label>
            <input
              type="number"
              id="age"
              min="10"
              max="120"
              step="1"
              value={formData.age}
              onChange={(e) => setFormData({ ...formData, age: parseInt(e.target.value) })}
              className="w-full bg-slate-800 border border-slate-700 rounded-lg px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-green-500"
              required
            />
            <p className="text-xs text-slate-500 mt-1">Your age in years</p>
          </div>
          
          {/* Gender */}
          <div>
            <label htmlFor="gender" className="block text-sm font-medium text-slate-300 mb-2">
              Gender
            </label>
            <select
              id="gender"
              value={formData.gender}
              onChange={(e) => setFormData({ ...formData, gender: e.target.value as 'male' | 'female' | 'other' })}
              className="w-full bg-slate-800 border border-slate-700 rounded-lg px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-green-500"
              required
            >
              <option value="male">Male</option>
              <option value="female">Female</option>
              <option value="other">Other</option>
            </select>
            <p className="text-xs text-slate-500 mt-1">Used for metabolic rate calculations</p>
          </div>
          
          {/* Save Button */}
          <button
            type="submit"
            disabled={isSaving}
            className="w-full bg-green-600 hover:bg-green-700 disabled:bg-slate-700 disabled:text-slate-500 text-white font-semibold py-3 px-6 rounded-lg transition flex items-center justify-center gap-2"
          >
            {isSaving ? 'Saving...' : 'Save Physical Profile'}
          </button>
          
          {/* Save Message */}
          {saveMessage && (
            <div className={`text-center text-sm font-medium ${saveMessage.startsWith('✓') ? 'text-green-400' : 'text-red-400'}`}>
              {saveMessage}
            </div>
          )}
        </form>
        
        {/* Info Box */}
        <div className="mt-6 bg-blue-500/10 border border-blue-500/30 rounded-lg p-4">
          <h4 className="text-sm font-semibold text-blue-400 mb-2">Why do we need this?</h4>
          <p className="text-xs text-slate-400 leading-relaxed">
            Your physical profile allows us to calculate calories burned with 80-95% accuracy using MET (Metabolic Equivalent of Task) values. 
            Without this data, we use default values which are only 40-60% accurate. Your data is stored locally on your device and is never uploaded to any server.
          </p>
        </div>
        
        {/* Cloud Sync Section */}
        <CloudSyncSection profile={profile} />
      </div>
    </>
  );
};

const CloudSyncSection = ({ profile }: { profile: UserProfile }) => {
  const [syncing, setSyncing] = useState(false);
  const [syncMessage, setSyncMessage] = useState('');

  const handleSync = async () => {
    setSyncing(true);
    setSyncMessage('');
    
    try {
      const sessionsToSync = profile.sessions.map(session => ({
        id: session.id,
        date: session.date,
        stats: session.stats
      }));
      
      await batchSaveSessionsToFirestore(sessionsToSync);
      setSyncMessage(`✓ Successfully synced ${sessionsToSync.length} sessions to cloud!`);
    } catch (error) {
      setSyncMessage(`✗ Sync failed: ${error instanceof Error ? error.message : 'Unknown error'}`);
    } finally {
      setSyncing(false);
    }
  };

  return (
    <div className="mt-6 bg-slate-800 border border-slate-700 rounded-lg p-6">
      <div className="flex items-center gap-3 mb-4">
        <Cloud className="w-6 h-6 text-blue-400" />
        <h3 className="text-lg font-semibold text-white">Cloud Sync</h3>
      </div>
      
      <p className="text-sm text-slate-400 mb-4">
        Sync your activity sessions to the cloud to enable AI analysis. 
        You have <span className="font-semibold text-white">{profile.sessions.length}</span> session(s) stored locally.
      </p>
      
      <button
        onClick={handleSync}
        disabled={syncing || profile.sessions.length === 0}
        className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-slate-700 disabled:text-slate-500 text-white font-semibold py-3 px-6 rounded-lg transition flex items-center justify-center gap-2"
      >
        <Cloud size={18} />
        {syncing ? 'Syncing...' : `Sync ${profile.sessions.length} Session(s) to Cloud`}
      </button>
      
      {syncMessage && (
        <div className={`mt-3 text-center text-sm font-medium ${syncMessage.startsWith('✓') ? 'text-green-400' : 'text-red-400'}`}>
          {syncMessage}
        </div>
      )}
      
      <div className="mt-4 bg-yellow-500/10 border border-yellow-500/30 rounded-lg p-3">
        <p className="text-xs text-yellow-400">
          <strong>Note:</strong> Future sessions will automatically sync to the cloud. This button is for syncing existing sessions.
        </p>
      </div>
    </div>
  );
};

const StatCard = ({ label, value, icon }: { label: string; value: string; icon?: React.ReactNode }) => (
  <div className="bg-slate-900 border border-slate-800 rounded-xl p-3 sm:p-5 flex flex-col justify-between">
    <div className="flex items-center justify-between mb-2">
      <p className="text-[10px] sm:text-xs uppercase text-slate-500">{label}</p>
      {icon}
    </div>
    <p className="text-lg sm:text-2xl font-bold text-white whitespace-nowrap truncate min-w-0">{value}</p>
  </div>
);










