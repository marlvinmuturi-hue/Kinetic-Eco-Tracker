import React, { useMemo } from 'react';
import { UserProfile } from '../types';
import { ActivityIcon } from './ActivityIcon';
import { ActivityType } from '../types';

interface ProfileProps {
  profile: UserProfile;
  onLogout: () => void;
}

const formatDate = (iso: string) => new Date(iso).toLocaleDateString();

export const Profile: React.FC<ProfileProps> = ({ profile, onLogout }) => {
  const aggregated = useMemo(() => {
    return profile.sessions.reduce(
      (acc, session) => {
        acc.totalSessions += 1;
        acc.totalDistance += session.stats.totalDistance;
        acc.totalDuration += session.stats.totalDuration;
        acc.totalEmissions += session.stats.co2Emissions;
        acc.totalCalories += session.stats.caloriesBurned;
        return acc;
      },
      {
        totalSessions: 0,
        totalDistance: 0,
        totalDuration: 0,
        totalEmissions: 0,
        totalCalories: 0,
      }
    );
  }, [profile.sessions]);

  return (
    <div className="w-full max-w-5xl mx-auto p-6">
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 mb-8">
        <div>
          <h2 className="text-3xl font-bold text-white">Your Profile</h2>
          <p className="text-slate-400 text-sm mt-2">Signed in as {profile.email}</p>
          <p className="text-slate-500 text-xs">Member since {formatDate(profile.createdAt)}</p>
        </div>
        <button
          onClick={onLogout}
          className="self-start md:self-auto bg-slate-800 px-4 py-2 rounded-lg border border-slate-700 text-sm text-slate-300 hover:bg-slate-700 transition"
        >
          Logout
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-8">
        <StatCard label="Trips logged" value={aggregated.totalSessions.toString()} />
        <StatCard label="Distance tracked" value={`${(aggregated.totalDistance / 1000).toFixed(1)} km`} />
        <StatCard label="CO₂ prevented" value={`${aggregated.totalEmissions.toFixed(2)} kg`} />
        <StatCard label="Active minutes" value={`${Math.round(aggregated.totalDuration / 60)} min`} />
        <StatCard label="Calories burned" value={`${Math.round(aggregated.totalCalories)} kcal`} />
      </div>

      <section>
        <h3 className="text-xl text-white font-semibold mb-4">Recent activity</h3>
        {profile.sessions.length === 0 ? (
          <div className="bg-slate-900 border border-slate-800 rounded-xl p-6 text-slate-400 text-center">
            No activity logged yet. Start tracking to see your trends.
          </div>
        ) : (
          <ul className="space-y-4">
            {profile.sessions.slice(0, 5).map((session) => (
              <li key={session.id} className="bg-slate-900 border border-slate-800 rounded-xl p-4">
                <div className="flex justify-between items-center mb-3">
                  <div>
                    <p className="text-white font-semibold">{session.date}</p>
                    <p className="text-xs text-slate-500">Tracked impact overview</p>
                  </div>
                  <div className="text-right text-sm text-slate-300">
                    <p>{(session.stats.totalDistance / 1000).toFixed(2)} km</p>
                    <p>{(session.stats.totalDuration / 60).toFixed(1)} min</p>
                  </div>
                </div>
                <div className="flex gap-4 flex-wrap">
                  {(Object.entries(session.stats.breakdown) as [ActivityType, { time: number; distance: number }][])
                    .filter(([_, values]) => values.time > 0)
                    .map(([type, values]) => (
                      <div key={type} className="flex items-center space-x-2 bg-slate-800/60 rounded-lg px-3 py-2 text-xs text-slate-200">
                        <ActivityIcon type={type} size={16} />
                        <div className="flex flex-col">
                          <span className="font-semibold">{type.toLowerCase()}</span>
                          <span className="text-slate-400">{(values.time / 60).toFixed(1)} min</span>
                        </div>
                      </div>
                    ))}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
};

const StatCard = ({ label, value }: { label: string; value: string }) => (
  <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
    <p className="text-xs uppercase text-slate-500 mb-2">{label}</p>
    <p className="text-2xl font-bold text-white">{value}</p>
  </div>
);







