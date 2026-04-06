import React, { useMemo } from 'react';
import { UserProfile, Achievement, AggregatedStats } from '../types';
import { checkAchievements, getAchievementProgress } from '../services/achievementService';
import { Award, Lock, TrendingUp } from 'lucide-react';

interface AchievementsDisplayProps {
  profile: UserProfile;
  aggregatedStats: AggregatedStats;
}

export const AchievementsDisplay: React.FC<AchievementsDisplayProps> = ({ profile, aggregatedStats }) => {
  const achievements = useMemo(() => {
    return checkAchievements(profile.achievements, aggregatedStats);
  }, [profile.achievements, aggregatedStats]);

  const progress = useMemo(() => {
    return getAchievementProgress(aggregatedStats);
  }, [aggregatedStats]);

  const categorizedAchievements = useMemo(() => {
    const categories = {
      distance: achievements.filter(a => a.category === 'distance'),
      carbon: achievements.filter(a => a.category === 'carbon'),
      health: achievements.filter(a => a.category === 'health'),
      time: achievements.filter(a => a.category === 'time'),
      streak: achievements.filter(a => a.category === 'streak'),
    };
    return categories;
  }, [achievements]);

  const categoryLabels = {
    distance: 'Distance',
    carbon: 'Environmental',
    health: 'Health & Fitness',
    time: 'Time',
    streak: 'Consistency',
  };

  const categoryColors = {
    distance: 'blue',
    carbon: 'green',
    health: 'orange',
    time: 'purple',
    streak: 'yellow',
  };

  return (
    <div className="w-full max-w-5xl mx-auto p-4 sm:p-6 pb-24">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h2 className="text-3xl font-bold text-white flex items-center gap-2">
            <Award size={32} className="text-yellow-400" />
            Achievements
          </h2>
          <p className="text-slate-400 text-sm mt-2">Track your progress and unlock rewards</p>
        </div>
      </div>

      {/* Progress Overview */}
      <div className="bg-gradient-to-br from-indigo-900/50 to-slate-800 p-6 rounded-xl border border-indigo-700/50 mb-6">
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-white font-semibold">Overall Progress</h3>
          <span className="text-indigo-300 text-2xl font-bold">{progress.percentage}%</span>
        </div>
        <div className="w-full bg-slate-700 rounded-full h-3 mb-2">
          <div 
            className="bg-gradient-to-r from-indigo-500 to-purple-500 h-3 rounded-full transition-all duration-500"
            style={{ width: `${progress.percentage}%` }}
          />
        </div>
        <p className="text-slate-300 text-sm">
          {progress.unlocked} of {progress.total} achievements unlocked
        </p>
      </div>

      {/* Achievement Categories */}
      {Object.entries(categorizedAchievements).map(([category, categoryAchievements]) => (
        <div key={category} className="mb-6">
          <h3 className="text-xl font-semibold text-white mb-4 flex items-center gap-2">
            <TrendingUp size={20} className={`text-${categoryColors[category as keyof typeof categoryColors]}-400`} />
            {categoryLabels[category as keyof typeof categoryLabels]}
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {categoryAchievements.map((achievement) => (
              <AchievementCard key={achievement.id} achievement={achievement} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
};

const AchievementCard: React.FC<{ achievement: Achievement }> = ({ achievement }) => {
  const isUnlocked = achievement.unlocked;

  return (
    <div 
      className={`relative overflow-hidden rounded-xl p-5 border transition-all ${
        isUnlocked 
          ? 'bg-gradient-to-br from-slate-800 to-slate-900 border-yellow-500/50 shadow-lg shadow-yellow-500/20' 
          : 'bg-slate-900 border-slate-700 opacity-60'
      }`}
    >
      {/* Unlock Badge */}
      {isUnlocked && (
        <div className="absolute top-2 right-2 bg-yellow-500 text-slate-900 text-xs font-bold px-2 py-1 rounded-full">
          ✓ UNLOCKED
        </div>
      )}

      {/* Icon */}
      <div className={`text-5xl mb-3 ${isUnlocked ? '' : 'grayscale opacity-50'}`}>
        {achievement.icon}
      </div>

      {/* Name and Description */}
      <h4 className={`font-bold mb-2 ${isUnlocked ? 'text-white' : 'text-slate-500'}`}>
        {achievement.name}
      </h4>
      <p className={`text-sm mb-3 ${isUnlocked ? 'text-slate-300' : 'text-slate-600'}`}>
        {achievement.description}
      </p>

      {/* Unlock Date */}
      {isUnlocked && achievement.unlockedAt && (
        <p className="text-xs text-yellow-400 font-medium">
          Unlocked {new Date(achievement.unlockedAt).toLocaleDateString()}
        </p>
      )}

      {/* Locked Icon */}
      {!isUnlocked && (
        <div className="flex items-center gap-1 text-slate-600 text-xs">
          <Lock size={12} />
          <span>Locked</span>
        </div>
      )}
    </div>
  );
};
