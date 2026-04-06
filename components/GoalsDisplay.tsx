import React, { useState, useMemo } from 'react';
import { UserProfile, Goal, AggregatedStats, Timeframe } from '../types';
import { calculateGoalProgress, getGoalProgressPercentage, createGoal, SUGGESTED_GOALS, updateGoals, removeExpiredGoals } from '../services/goalService';
import { Target, Plus, X, CheckCircle, Clock, TrendingUp } from 'lucide-react';

interface GoalsDisplayProps {
  profile: UserProfile;
  aggregatedStats: Record<Timeframe, AggregatedStats>;
  onUpdateGoals: (goals: Goal[]) => void;
}

export const GoalsDisplay: React.FC<GoalsDisplayProps> = ({ profile, aggregatedStats, onUpdateGoals }) => {
  const [showAddGoal, setShowAddGoal] = useState(false);
  const [selectedTimeframe, setSelectedTimeframe] = useState<Timeframe>('Weekly');

  // Get stats for the selected timeframe
  const currentStats = aggregatedStats[selectedTimeframe];

  // Update goals with current progress
  const updatedGoals = useMemo(() => {
    const goals = profile.goals || [];
    const activeGoals = removeExpiredGoals(goals);
    return updateGoals(activeGoals, currentStats);
  }, [profile.goals, currentStats]);

  // Separate active and completed goals
  const activeGoals = updatedGoals.filter(g => !g.completed);
  const completedGoals = updatedGoals.filter(g => g.completed);

  const handleAddGoal = (goal: Goal) => {
    const newGoals = [...(profile.goals || []), goal];
    onUpdateGoals(newGoals);
    setShowAddGoal(false);
  };

  const handleRemoveGoal = (goalId: string) => {
    const newGoals = (profile.goals || []).filter(g => g.id !== goalId);
    onUpdateGoals(newGoals);
  };

  return (
    <div className="w-full max-w-5xl mx-auto p-4 sm:p-6 pb-24">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h2 className="text-3xl font-bold text-white flex items-center gap-2">
            <Target size={32} className="text-indigo-400" />
            Goals
          </h2>
          <p className="text-slate-400 text-sm mt-2">Set targets and track your progress</p>
        </div>
        <button
          onClick={() => setShowAddGoal(true)}
          className="flex items-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white px-4 py-2 rounded-lg font-semibold transition"
        >
          <Plus size={20} />
          Add Goal
        </button>
      </div>

      {/* Timeframe Selector */}
      <div className="flex space-x-2 mb-6 bg-slate-800/50 p-1 rounded-xl">
        {(['Daily', 'Weekly', 'Monthly'] as Timeframe[]).map((tf) => (
          <button
            key={tf}
            onClick={() => setSelectedTimeframe(tf)}
            className={`flex-1 py-2 text-sm font-medium rounded-lg transition ${
              selectedTimeframe === tf
                ? 'bg-slate-700 text-white shadow-lg'
                : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
            }`}
          >
            {tf}
          </button>
        ))}
      </div>

      {/* Active Goals */}
      {activeGoals.length > 0 && (
        <div className="mb-6">
          <h3 className="text-xl font-semibold text-white mb-4 flex items-center gap-2">
            <TrendingUp size={20} className="text-indigo-400" />
            Active Goals
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {activeGoals.map((goal) => (
              <GoalCard 
                key={goal.id} 
                goal={goal} 
                stats={currentStats} 
                onRemove={handleRemoveGoal}
              />
            ))}
          </div>
        </div>
      )}

      {/* Completed Goals */}
      {completedGoals.length > 0 && (
        <div className="mb-6">
          <h3 className="text-xl font-semibold text-white mb-4 flex items-center gap-2">
            <CheckCircle size={20} className="text-green-400" />
            Completed Goals
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {completedGoals.map((goal) => (
              <GoalCard 
                key={goal.id} 
                goal={goal} 
                stats={currentStats} 
                onRemove={handleRemoveGoal}
                isCompleted
              />
            ))}
          </div>
        </div>
      )}

      {/* Empty State */}
      {activeGoals.length === 0 && completedGoals.length === 0 && (
        <div className="bg-slate-800 p-8 rounded-xl border border-slate-700 text-center">
          <Target size={48} className="mx-auto text-slate-600 mb-4" />
          <p className="text-slate-400 mb-4">No goals set yet. Start by adding your first goal!</p>
          <button
            onClick={() => setShowAddGoal(true)}
            className="bg-indigo-600 hover:bg-indigo-700 text-white px-6 py-3 rounded-lg font-semibold transition"
          >
            Create Your First Goal
          </button>
        </div>
      )}

      {/* Add Goal Modal */}
      {showAddGoal && (
        <AddGoalModal 
          onAdd={handleAddGoal} 
          onClose={() => setShowAddGoal(false)} 
        />
      )}
    </div>
  );
};

const GoalCard: React.FC<{ 
  goal: Goal; 
  stats: AggregatedStats; 
  onRemove: (id: string) => void;
  isCompleted?: boolean;
}> = ({ goal, stats, onRemove, isCompleted }) => {
  const progress = calculateGoalProgress(goal, stats);
  const percentage = getGoalProgressPercentage(goal, stats);

  const typeIcons = {
    distance: '🚴',
    carbon: '🌱',
    calories: '🔥',
    sessions: '📊',
  };

  const typeColors = {
    distance: 'blue',
    carbon: 'green',
    calories: 'orange',
    sessions: 'purple',
  };

  const color = typeColors[goal.type];

  return (
    <div className={`relative bg-slate-900 border rounded-xl p-5 ${
      isCompleted 
        ? 'border-green-500/50 bg-green-900/20' 
        : 'border-slate-700'
    }`}>
      {/* Remove Button */}
      <button
        onClick={() => onRemove(goal.id)}
        className="absolute top-2 right-2 text-slate-500 hover:text-slate-300 transition"
      >
        <X size={16} />
      </button>

      {/* Icon and Title */}
      <div className="flex items-center gap-3 mb-3">
        <span className="text-3xl">{typeIcons[goal.type]}</span>
        <div>
          <h4 className="font-bold text-white">{goal.name}</h4>
          <p className="text-xs text-slate-400">{goal.description}</p>
        </div>
      </div>

      {/* Progress Bar */}
      <div className="mb-3">
        <div className="flex justify-between text-sm mb-1">
          <span className="text-slate-400">Progress</span>
          <span className={`font-bold text-${color}-400`}>{percentage}%</span>
        </div>
        <div className="w-full bg-slate-700 rounded-full h-2">
          <div 
            className={`bg-${color}-500 h-2 rounded-full transition-all duration-500`}
            style={{ width: `${percentage}%` }}
          />
        </div>
        <div className="flex justify-between text-xs mt-1">
          <span className="text-slate-500">{progress.toFixed(1)} / {goal.target}</span>
          {goal.endDate && (
            <span className="text-slate-500 flex items-center gap-1">
              <Clock size={12} />
              {new Date(goal.endDate).toLocaleDateString()}
            </span>
          )}
        </div>
      </div>

      {/* Completion Badge */}
      {isCompleted && (
        <div className="bg-green-500/20 border border-green-500/50 rounded-lg p-2 text-center">
          <p className="text-green-400 text-sm font-bold flex items-center justify-center gap-2">
            <CheckCircle size={16} />
            Goal Achieved!
          </p>
        </div>
      )}
    </div>
  );
};

const AddGoalModal: React.FC<{ onAdd: (goal: Goal) => void; onClose: () => void }> = ({ onAdd, onClose }) => {
  const [customMode, setCustomMode] = useState(false);
  const [customName, setCustomName] = useState('');
  const [customType, setCustomType] = useState<Goal['type']>('distance');
  const [customTarget, setCustomTarget] = useState(10);
  const [customTimeframe, setCustomTimeframe] = useState<Goal['timeframe']>('weekly');

  const handleSuggestedGoalClick = (suggested: typeof SUGGESTED_GOALS[0]) => {
    const goal = createGoal(suggested.name, suggested.type, suggested.target, suggested.timeframe);
    onAdd(goal);
  };

  const handleCustomGoalSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const goal = createGoal(customName, customType, customTarget, customTimeframe);
    onAdd(goal);
  };

  return (
    <div className="fixed inset-0 bg-black/80 flex items-center justify-center z-50 p-4">
      <div className="bg-slate-900 rounded-xl border border-slate-700 max-w-2xl w-full max-h-[90vh] overflow-y-auto">
        <div className="sticky top-0 bg-slate-900 border-b border-slate-700 p-6 flex items-center justify-between">
          <h3 className="text-2xl font-bold text-white">Add New Goal</h3>
          <button onClick={onClose} className="text-slate-400 hover:text-white transition">
            <X size={24} />
          </button>
        </div>

        <div className="p-6">
          {/* Toggle between suggested and custom */}
          <div className="flex space-x-2 mb-6 bg-slate-800/50 p-1 rounded-xl">
            <button
              onClick={() => setCustomMode(false)}
              className={`flex-1 py-2 text-sm font-medium rounded-lg transition ${
                !customMode ? 'bg-slate-700 text-white' : 'text-slate-400'
              }`}
            >
              Suggested Goals
            </button>
            <button
              onClick={() => setCustomMode(true)}
              className={`flex-1 py-2 text-sm font-medium rounded-lg transition ${
                customMode ? 'bg-slate-700 text-white' : 'text-slate-400'
              }`}
            >
              Custom Goal
            </button>
          </div>

          {!customMode ? (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {SUGGESTED_GOALS.map((suggested, idx) => (
                <button
                  key={idx}
                  onClick={() => handleSuggestedGoalClick(suggested)}
                  className="bg-slate-800 hover:bg-slate-700 border border-slate-700 hover:border-indigo-500/50 rounded-lg p-4 text-left transition"
                >
                  <p className="font-bold text-white mb-1">{suggested.name}</p>
                  <p className="text-xs text-slate-400">
                    {suggested.target} {suggested.type} • {suggested.timeframe}
                  </p>
                </button>
              ))}
            </div>
          ) : (
            <form onSubmit={handleCustomGoalSubmit} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-300 mb-2">Goal Name</label>
                <input
                  type="text"
                  value={customName}
                  onChange={(e) => setCustomName(e.target.value)}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  placeholder="e.g., Cycle 50km"
                  required
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-300 mb-2">Type</label>
                <select
                  value={customType}
                  onChange={(e) => setCustomType(e.target.value as Goal['type'])}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="distance">Distance (km)</option>
                  <option value="carbon">CO2 Saved (kg)</option>
                  <option value="calories">Calories (kcal)</option>
                  <option value="sessions">Sessions</option>
                </select>
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-300 mb-2">Target</label>
                <input
                  type="number"
                  value={customTarget}
                  onChange={(e) => setCustomTarget(Number(e.target.value))}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  min="1"
                  required
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-slate-300 mb-2">Timeframe</label>
                <select
                  value={customTimeframe}
                  onChange={(e) => setCustomTimeframe(e.target.value as Goal['timeframe'])}
                  className="w-full bg-slate-800 border border-slate-700 rounded-lg px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="daily">Daily</option>
                  <option value="weekly">Weekly</option>
                  <option value="monthly">Monthly</option>
                </select>
              </div>

              <button
                type="submit"
                className="w-full bg-indigo-600 hover:bg-indigo-700 text-white font-semibold py-3 rounded-lg transition"
              >
                Create Goal
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  );
};
