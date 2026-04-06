import React, { useEffect, useState, useMemo, Suspense, lazy } from 'react';
import { SessionStats, ActivityType, UserProfile } from '../types';
import { ACTIVITY_COLORS } from '../constants';
import { generateEcoInsight } from '../services/geminiService';
import { analyzeActivity, ActivityAnalysis } from '../services/aiAnalysisService';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip as RechartsTooltip, BarChart, Bar, XAxis, YAxis, LineChart, Line, CartesianGrid, Legend } from 'recharts';
import { ActivityIcon } from './ActivityIcon';
import { Leaf, Flame, Timer, Info, TrendingUp, Award, Target, Sparkles, RefreshCw } from 'lucide-react';
import { getTopEquivalencies, formatCO2Value, getNetImpactEquivalencies } from '../services/co2EquivalencyService';
import { isAndroidWebView, shouldUseSimplifiedUI } from '../utils/deviceDetection';

interface AnalyticsProps {
  stats: SessionStats;
  profile?: UserProfile;
}

export const Analytics: React.FC<AnalyticsProps> = ({ stats, profile }) => {
  const [insight, setInsight] = useState<string>('Generating AI insight...');
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<'session' | 'trends' | 'equivalencies' | 'aianalysis'>('session');
  const [chartsReady, setChartsReady] = useState(false);
  
  // AI Analysis state
  const [aiAnalysis, setAiAnalysis] = useState<ActivityAnalysis | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiError, setAiError] = useState<string | null>(null);
  const [rollingDays, setRollingDays] = useState(30);
  
  // Detect if running on Android WebView for performance optimizations
  const isAndroid = useMemo(() => isAndroidWebView(), []);
  const useSimplified = useMemo(() => shouldUseSimplifiedUI(), []);
  
  // Handle AI Analysis request
  const handleAIAnalysis = async () => {
    setAiLoading(true);
    setAiError(null);
    
    try {
      const result = await analyzeActivity(rollingDays);
      setAiAnalysis(result);
    } catch (error: any) {
      setAiError(error.message);
    } finally {
      setAiLoading(false);
    }
  };

  useEffect(() => {
    let isMounted = true;
    const fetchInsight = async () => {
      const text = await generateEcoInsight(stats);
      if (isMounted) {
        setInsight(text);
        setLoading(false);
      }
    };
    fetchInsight();
    return () => { isMounted = false; };
  }, [stats]);

  // Lazy load charts on Android to prevent UI blocking
  useEffect(() => {
    if (activeTab === 'trends') {
      // Small delay on Android to let UI settle
      const delay = isAndroid ? 100 : 0;
      const timer = setTimeout(() => {
        setChartsReady(true);
      }, delay);
      return () => clearTimeout(timer);
    } else {
      setChartsReady(false);
    }
  }, [activeTab, isAndroid]);

  // Calculate CO2 equivalencies
  const emissionEquivalencies = useMemo(() => 
    stats.co2Emissions > 0 ? getTopEquivalencies(stats.co2Emissions, true) : [],
    [stats.co2Emissions]
  );

  const conservationEquivalencies = useMemo(() => 
    stats.co2Conserved > 0 ? getTopEquivalencies(stats.co2Conserved, false) : [],
    [stats.co2Conserved]
  );

  // Calculate trend data from profile sessions (last 7 days and last 30 days)
  const trendData = useMemo(() => {
    if (!profile?.sessions) return { daily: [], weekly: [] };

    const now = new Date();
    const last7Days = new Date(now);
    last7Days.setDate(last7Days.getDate() - 7);
    const last30Days = new Date(now);
    last30Days.setDate(last30Days.getDate() - 30);

    // Group sessions by date
    const dailyMap = new Map<string, { distance: number; co2Saved: number; co2Emitted: number; calories: number; duration: number }>();
    
    profile.sessions.forEach(session => {
      const sessionDate = new Date(session.date);
      if (sessionDate >= last7Days) {
        const dateKey = session.date;
        const existing = dailyMap.get(dateKey) || { distance: 0, co2Saved: 0, co2Emitted: 0, calories: 0, duration: 0 };
        dailyMap.set(dateKey, {
          distance: existing.distance + (session.stats.totalDistance / 1000),
          co2Saved: existing.co2Saved + session.stats.co2Conserved,
          co2Emitted: existing.co2Emitted + session.stats.co2Emissions,
          calories: existing.calories + session.stats.caloriesBurned,
          duration: existing.duration + (session.stats.totalDuration / 60),
        });
      }
    });

    // Fill in missing days with zeros
    const daily = [];
    for (let i = 6; i >= 0; i--) {
      const date = new Date(now);
      date.setDate(date.getDate() - i);
      const dateKey = date.toISOString().split('T')[0];
      const data = dailyMap.get(dateKey) || { distance: 0, co2Saved: 0, co2Emitted: 0, calories: 0, duration: 0 };
      daily.push({
        date: dateKey,
        shortDate: date.toLocaleDateString('en-US', { weekday: 'short' }),
        ...data,
      });
    }

    return { daily, weekly: daily };
  }, [profile?.sessions]);

  const pieData = (Object.entries(stats.breakdown) as [ActivityType, { time: number; distance: number }][])
    .filter(([_, data]) => data.time > 0)
    .map(([type, data]) => ({
      name: type,
      value: data.time,
      color: ACTIVITY_COLORS[type]
    }));
  
  const emissionData = [
    { name: 'CO2 Emitted', co2: stats.co2Emissions, fill: '#ef4444' }, // red-500
    { name: 'CO2 Conserved', co2: stats.co2Conserved, fill: '#22c55e' }, // green-500
    { name: 'Tree (Daily)', co2: 0.06, fill: '#10b981' }, // emerald-500 - Approx daily absorption of mature tree
  ];

  return (
    <div className="w-full max-w-4xl mx-auto p-3 sm:p-4 pb-20 sm:pb-24">
      <h2 className="text-2xl sm:text-3xl font-bold text-white mb-4 sm:mb-6">Session Summary</h2>
      
      {/* Tabs */}
      <div className="flex space-x-1 bg-slate-800/50 p-1 rounded-xl mb-6 overflow-x-auto">
        <button
          onClick={() => setActiveTab('session')}
          className={`flex-1 flex items-center justify-center space-x-2 py-2.5 px-3 text-sm font-medium rounded-lg transition whitespace-nowrap ${
            activeTab === 'session' 
              ? 'bg-slate-700 text-white shadow-lg' 
              : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
          }`}
        >
          <Info size={16} />
          <span>Session</span>
        </button>
        <button
          onClick={() => setActiveTab('trends')}
          className={`flex-1 flex items-center justify-center space-x-2 py-2.5 px-3 text-sm font-medium rounded-lg transition whitespace-nowrap ${
            activeTab === 'trends' 
              ? 'bg-slate-700 text-white shadow-lg' 
              : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
          }`}
        >
          <TrendingUp size={16} />
          <span>Trends</span>
        </button>
        <button
          onClick={() => setActiveTab('equivalencies')}
          className={`flex-1 flex items-center justify-center space-x-2 py-2.5 px-3 text-sm font-medium rounded-lg transition whitespace-nowrap ${
            activeTab === 'equivalencies' 
              ? 'bg-slate-700 text-white shadow-lg' 
              : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
          }`}
        >
          <Leaf size={16} />
          <span>Impact</span>
        </button>
        <button
          onClick={() => setActiveTab('aianalysis')}
          className={`flex-1 flex items-center justify-center space-x-2 py-2.5 px-3 text-sm font-medium rounded-lg transition whitespace-nowrap ${
            activeTab === 'aianalysis' 
              ? 'bg-gradient-to-r from-purple-600 to-indigo-600 text-white shadow-lg shadow-purple-500/30' 
              : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
          }`}
        >
          <Sparkles size={16} />
          <span>AI Analysis</span>
        </button>
      </div>
      
      {/* Tab Content */}
      {activeTab === 'session' && (
        <>
          {/* Top Cards */}
          <div className="grid grid-cols-1 md:grid-cols-4 gap-3 sm:gap-4 mb-6 sm:mb-8">
        <div className="bg-slate-800 p-5 rounded-xl border border-red-700/50 relative overflow-hidden">
          <div className="absolute top-0 right-0 p-4 opacity-10">
            <Flame size={64} />
          </div>
          <div className="flex items-center space-x-2 text-red-400 mb-2">
            <Flame size={20} />
            <span className="font-semibold uppercase text-xs">CO2 Emitted</span>
          </div>
          <p className="text-3xl font-bold text-white whitespace-nowrap tabular-nums">{stats.co2Emissions.toFixed(3)} <span className="text-lg text-slate-400 font-normal">kg</span></p>
        </div>

        <div className="bg-slate-800 p-5 rounded-xl border border-green-700/50 relative overflow-hidden">
          <div className="absolute top-0 right-0 p-4 opacity-10">
            <Leaf size={64} />
          </div>
          <div className="flex items-center space-x-2 text-green-400 mb-2">
            <Leaf size={20} />
            <span className="font-semibold uppercase text-xs">CO2 Conserved</span>
          </div>
          <p className="text-3xl font-bold text-white whitespace-nowrap tabular-nums">{stats.co2Conserved.toFixed(3)} <span className="text-lg text-slate-400 font-normal">kg</span></p>
        </div>

        <div className="bg-slate-800 p-5 rounded-xl border border-slate-700 relative overflow-hidden">
          <div className="absolute top-0 right-0 p-4 opacity-10">
            <Flame size={64} />
          </div>
          <div className="flex items-center space-x-2 text-orange-400 mb-2">
            <Flame size={20} />
            <span className="font-semibold uppercase text-xs">Calories Burned</span>
          </div>
          <p className="text-3xl font-bold text-white whitespace-nowrap tabular-nums">{Math.round(stats.caloriesBurned)} <span className="text-lg text-slate-400 font-normal">kcal</span></p>
        </div>

        <div className="bg-slate-800 p-5 rounded-xl border border-slate-700 relative overflow-hidden">
          <div className="absolute top-0 right-0 p-4 opacity-10">
            <Timer size={64} />
          </div>
          <div className="flex items-center space-x-2 text-blue-400 mb-2">
            <Timer size={20} />
            <span className="font-semibold uppercase text-xs">Total Time</span>
          </div>
          <p className="text-3xl font-bold text-white whitespace-nowrap tabular-nums">{Math.round(stats.totalDuration)} <span className="text-lg text-slate-400 font-normal">sec</span></p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-8">
        {/* Activity Breakdown Chart */}
        <div className="bg-slate-800 p-6 rounded-xl border border-slate-700">
          <h3 className="text-white font-semibold mb-4">Activity Time Distribution</h3>
          <div className="h-64 w-full">
            {pieData.length > 0 ? (
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={pieData}
                    cx="50%"
                    cy="50%"
                    innerRadius={60}
                    outerRadius={80}
                    paddingAngle={5}
                    dataKey="value"
                    stroke="none"
                  >
                    {pieData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={entry.color} />
                    ))}
                  </Pie>
                  <RechartsTooltip 
                    contentStyle={{ backgroundColor: '#1e293b', border: 'none', borderRadius: '8px', color: '#fff' }}
                    itemStyle={{ color: '#fff' }}
                    formatter={(value: number) => [`${Math.round(value)} sec`, 'Time']}
                  />
                </PieChart>
              </ResponsiveContainer>
            ) : (
              <div className="flex items-center justify-center h-full text-slate-500">No activity data</div>
            )}
          </div>
          <div className="flex flex-wrap justify-center gap-4 mt-4">
            {pieData.map((entry) => (
              <div key={entry.name} className="flex items-center space-x-2">
                <div className="w-3 h-3 rounded-full" style={{ backgroundColor: entry.color }} />
                <span className="text-sm text-slate-300 capitalize">{entry.name.toLowerCase()}</span>
              </div>
            ))}
          </div>
        </div>

        {/* AI Insight Card */}
        <div className="bg-gradient-to-br from-indigo-900 to-slate-900 p-6 rounded-xl border border-indigo-700/50 flex flex-col">
          <div className="flex items-center space-x-2 mb-4 text-indigo-300">
            <Info size={20} />
            <h3 className="font-semibold uppercase text-sm tracking-wider">AI Coach Insight</h3>
          </div>
          <div className="flex-grow">
             {loading ? (
               <div className="flex flex-col space-y-2 animate-pulse">
                 <div className="h-4 bg-indigo-800/50 rounded w-3/4"></div>
                 <div className="h-4 bg-indigo-800/50 rounded w-full"></div>
                 <div className="h-4 bg-indigo-800/50 rounded w-5/6"></div>
               </div>
             ) : (
               <div className="prose prose-invert prose-sm">
                 <p className="text-slate-200 leading-relaxed whitespace-pre-wrap">{insight}</p>
               </div>
             )}
          </div>
        </div>
      </div>

      {/* Comparison Chart */}
      <div className="bg-slate-800 p-6 rounded-xl border border-slate-700 mb-8">
        <h3 className="text-white font-semibold mb-4">CO2 Impact Breakdown</h3>
        <p className="text-slate-400 text-sm mb-4">Compare your emissions and conservation against what a mature tree absorbs daily.</p>
        <div className="h-48 w-full">
           <ResponsiveContainer width="100%" height="100%">
              <BarChart data={emissionData} layout="vertical">
                <XAxis type="number" hide />
                <YAxis dataKey="name" type="category" width={150} tick={{fill: '#94a3b8', fontSize: 12}} axisLine={false} tickLine={false} />
                <RechartsTooltip 
                  cursor={{fill: 'transparent'}} 
                  contentStyle={{ backgroundColor: '#1e293b', border: 'none', color: '#fff' }}
                  formatter={(value: number) => [`${value.toFixed(3)} kg`, 'CO2']}
                />
                <Bar dataKey="co2" radius={[0, 4, 4, 0]} barSize={20}>
                  {emissionData.map((entry, index) => (
                    <Cell key={`cell-${index}`} fill={entry.fill} />
                  ))}
                </Bar>
              </BarChart>
           </ResponsiveContainer>
        </div>
        <div className="mt-4 p-4 bg-slate-900 rounded-lg">
          <p className="text-slate-300 text-sm">
            <span className="font-semibold text-white">Net Impact: </span>
            {(stats.co2Conserved - stats.co2Emissions) >= 0 ? (
              <span className="text-green-400">+{(stats.co2Conserved - stats.co2Emissions).toFixed(3)} kg CO2 conserved</span>
            ) : (
              <span className="text-red-400">{(stats.co2Conserved - stats.co2Emissions).toFixed(3)} kg CO2 emitted</span>
            )}
          </p>
        </div>
      </div>
        </>
      )}

      {/* Trends Tab */}
      {activeTab === 'trends' && (
        <>
          <h3 className="text-xl font-bold text-white mb-4">7-Day Trends</h3>
          
          {/* Loading indicator for charts on Android */}
          {!chartsReady && isAndroid && (
            <div className="bg-slate-800 p-8 rounded-xl border border-slate-700 text-center mb-4">
              <div className="w-12 h-12 border-4 border-indigo-400 border-t-transparent rounded-full animate-spin mx-auto mb-4"></div>
              <p className="text-slate-400">Loading charts...</p>
            </div>
          )}
          
          {trendData.daily.length > 0 && trendData.daily.some(d => d.distance > 0 || d.calories > 0) && (!isAndroid || chartsReady) ? (
            <>
              {/* Distance Trend */}
              <div className="bg-slate-800 p-6 rounded-xl border border-slate-700 mb-6">
                <h4 className="text-white font-semibold mb-4 flex items-center gap-2">
                  <TrendingUp size={18} className="text-blue-400" />
                  Distance Traveled
                </h4>
                <div className="h-48 w-full">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={trendData.daily}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
                      <XAxis dataKey="shortDate" stroke="#94a3b8" fontSize={12} />
                      <YAxis stroke="#94a3b8" fontSize={12} />
                      <RechartsTooltip 
                        contentStyle={{ backgroundColor: '#1e293b', border: 'none', borderRadius: '8px', color: '#fff' }}
                        formatter={(value: number) => [`${value.toFixed(2)} km`, 'Distance']}
                      />
                      <Line type="monotone" dataKey="distance" stroke="#3b82f6" strokeWidth={2} dot={{ fill: '#3b82f6', r: 4 }} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </div>

              {/* CO2 Impact Trend */}
              <div className="bg-slate-800 p-6 rounded-xl border border-slate-700 mb-6">
                <h4 className="text-white font-semibold mb-4 flex items-center gap-2">
                  <Leaf size={18} className="text-green-400" />
                  CO2 Impact
                </h4>
                <div className="h-48 w-full">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={trendData.daily}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
                      <XAxis dataKey="shortDate" stroke="#94a3b8" fontSize={12} />
                      <YAxis stroke="#94a3b8" fontSize={12} />
                      <RechartsTooltip 
                        contentStyle={{ backgroundColor: '#1e293b', border: 'none', borderRadius: '8px', color: '#fff' }}
                        formatter={(value: number, name: string) => [
                          `${value.toFixed(3)} kg`,
                          name === 'co2Saved' ? 'Saved' : 'Emitted'
                        ]}
                      />
                      <Legend />
                      <Line type="monotone" dataKey="co2Saved" stroke="#22c55e" strokeWidth={2} dot={{ fill: '#22c55e', r: 4 }} name="CO2 Saved" />
                      <Line type="monotone" dataKey="co2Emitted" stroke="#ef4444" strokeWidth={2} dot={{ fill: '#ef4444', r: 4 }} name="CO2 Emitted" />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </div>

              {/* Calories Trend */}
              <div className="bg-slate-800 p-6 rounded-xl border border-slate-700 mb-6">
                <h4 className="text-white font-semibold mb-4 flex items-center gap-2">
                  <Flame size={18} className="text-orange-400" />
                  Calories Burned
                </h4>
                <div className="h-48 w-full">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={trendData.daily}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
                      <XAxis dataKey="shortDate" stroke="#94a3b8" fontSize={12} />
                      <YAxis stroke="#94a3b8" fontSize={12} />
                      <RechartsTooltip 
                        contentStyle={{ backgroundColor: '#1e293b', border: 'none', borderRadius: '8px', color: '#fff' }}
                        formatter={(value: number) => [`${Math.round(value)} kcal`, 'Calories']}
                      />
                      <Bar dataKey="calories" fill="#fb923c" radius={[4, 4, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </div>
            </>
          ) : (
            <div className="bg-slate-800 p-8 rounded-xl border border-slate-700 text-center">
              <p className="text-slate-400">No trend data available yet. Complete more sessions to see your trends!</p>
            </div>
          )}
        </>
      )}

      {/* AI Analysis Tab */}
      {activeTab === 'aianalysis' && (
        <>
          <div className="bg-gradient-to-br from-purple-900/50 to-slate-800 p-6 rounded-xl border border-purple-700/50 mb-6">
            <div className="flex items-center gap-3 mb-4">
              <Sparkles size={28} className="text-purple-400" />
              <div>
                <h3 className="text-2xl font-bold text-white">AI-Powered Analysis</h3>
                <p className="text-slate-300 text-sm">Get personalized insights from your activity data</p>
              </div>
            </div>
            
            {/* Rolling window (days) */}
            <div className="mb-4 space-y-2">
              <label className="block text-sm text-slate-300" htmlFor="ai-rolling-days">
                Rolling window (days)
              </label>
              <input
                id="ai-rolling-days"
                type="number"
                min={1}
                max={366}
                value={rollingDays}
                onChange={(e) => {
                  const v = parseInt(e.target.value, 10);
                  if (Number.isNaN(v)) return;
                  setRollingDays(Math.min(366, Math.max(1, v)));
                }}
                className="w-full bg-slate-900 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:ring-2 focus:ring-purple-500 focus:border-transparent"
              />
              <p className="text-xs text-slate-500">1–366 days of activity data for the analysis.</p>
              <div className="flex flex-wrap gap-2 pt-1">
                {[7, 14, 30, 60, 90].map((d) => (
                  <button
                    key={d}
                    type="button"
                    onClick={() => setRollingDays(d)}
                    className={`py-1.5 px-3 rounded-lg text-xs font-medium transition ${
                      rollingDays === d
                        ? 'bg-purple-600 text-white'
                        : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
                    }`}
                  >
                    {d}
                  </button>
                ))}
              </div>
            </div>
            
            {/* Analyze Button */}
            <button
              onClick={handleAIAnalysis}
              disabled={aiLoading}
              className="w-full bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-700 hover:to-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed text-white font-bold py-4 rounded-xl transition-all flex items-center justify-center gap-2 shadow-lg shadow-purple-500/30"
            >
              {aiLoading ? (
                <>
                  <RefreshCw size={20} className="animate-spin" />
                  <span>Analyzing...</span>
                </>
              ) : (
                <>
                  <Sparkles size={20} />
                  <span>Analyze My Activity</span>
                </>
              )}
            </button>
          </div>
          
          {/* Error Message */}
          {aiError && (
            <div className="bg-red-900/50 border border-red-500/50 text-red-200 p-4 rounded-xl mb-6">
              <p className="font-semibold mb-1">Analysis Error</p>
              <p className="text-sm">{aiError}</p>
              {(aiError.includes('No activity data') || aiError.includes('track some activities')) && (
                <div className="mt-3 pt-3 border-t border-red-500/30 text-sm text-red-100">
                  <p className="font-medium mb-1">To fix this:</p>
                  <ul className="list-disc list-inside space-y-0.5 text-red-200/90">
                    <li>Track at least one activity while <strong>signed in</strong> (stop the session and save), or</li>
                    <li>Go to <strong>Profile</strong> → <strong>Cloud Sync</strong> and tap &quot;Sync Session(s) to Cloud&quot; if you have local sessions.</li>
                  </ul>
                  <p className="mt-2 text-red-200/80">AI Analysis uses your sessions stored in the cloud.</p>
                </div>
              )}
            </div>
          )}
          
          {/* Analysis Results */}
          {aiAnalysis && !aiLoading && (
            <div className="space-y-6">
              {/* Cache Indicator */}
              {aiAnalysis.cached && (
                <div className="bg-blue-900/30 border border-blue-500/30 text-blue-200 p-3 rounded-lg text-sm flex items-center gap-2">
                  <Info size={16} />
                  <span>Showing cached analysis from {aiAnalysis.cacheAge} minutes ago</span>
                </div>
              )}
              
              {/* Score Card */}
              <div className="bg-gradient-to-br from-purple-900/50 to-slate-800 p-6 rounded-xl border border-purple-700/50">
                <div className="flex flex-col items-center text-center mb-4">
                  <h4 className="text-xl font-bold text-white mb-2">Activity Score</h4>
                  <div className="text-6xl font-bold text-purple-400 mb-2">{aiAnalysis.score.toFixed(1)}</div>
                  <div className="text-slate-300 text-sm">out of 10</div>
                </div>
                <div className="w-full bg-slate-700 rounded-full h-3 mb-4">
                  <div
                    className="bg-gradient-to-r from-purple-500 to-indigo-500 h-3 rounded-full transition-all duration-500"
                    style={{ width: `${(aiAnalysis.score / 10) * 100}%` }}
                  />
                </div>
                <p className="text-slate-300 text-center text-sm">{aiAnalysis.scoreReasoning}</p>
              </div>
              
              {/* Motivation */}
              {aiAnalysis.motivation && (
                <div className="bg-gradient-to-br from-orange-900/30 to-slate-800 p-6 rounded-xl border border-orange-500/30">
                  <div className="flex items-start gap-3">
                    <Award size={32} className="text-orange-400 flex-shrink-0" />
                    <div>
                      <h4 className="text-lg font-bold text-white mb-2">Motivation</h4>
                      <p className="text-slate-200">{aiAnalysis.motivation}</p>
                    </div>
                  </div>
                </div>
              )}
              
              {/* Key Insights */}
              {aiAnalysis.insights.length > 0 && (
                <div className="bg-slate-800 p-6 rounded-xl border border-slate-700">
                  <h4 className="text-lg font-bold text-white mb-4 flex items-center gap-2">
                    <TrendingUp className="text-blue-400" />
                    Key Insights
                  </h4>
                  <ul className="space-y-3">
                    {aiAnalysis.insights.map((insight, idx) => (
                      <li key={idx} className="flex items-start gap-3">
                        <span className="text-purple-400 font-bold">•</span>
                        <span className="text-slate-200 flex-1">{insight}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              
              {/* Recommendations */}
              {aiAnalysis.recommendations.length > 0 && (
                <div className="bg-green-900/20 p-6 rounded-xl border border-green-500/30">
                  <h4 className="text-lg font-bold text-white mb-4 flex items-center gap-2">
                    <Target className="text-green-400" />
                    Recommendations
                  </h4>
                  <ul className="space-y-3">
                    {aiAnalysis.recommendations.map((rec, idx) => (
                      <li key={idx} className="flex items-start gap-3">
                        <span className="text-green-400 font-bold">✓</span>
                        <span className="text-slate-200 flex-1">{rec}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              
              {/* Environmental Impact */}
              {aiAnalysis.environmentalImpact && (
                <div className="bg-slate-800 p-6 rounded-xl border border-slate-700">
                  <h4 className="text-lg font-bold text-white mb-3 flex items-center gap-2">
                    <Leaf className="text-green-400" />
                    Environmental Impact
                  </h4>
                  <p className="text-slate-200">{aiAnalysis.environmentalImpact}</p>
                </div>
              )}
              
              {/* Highlights */}
              {aiAnalysis.highlights && (
                <div className="bg-slate-800 p-6 rounded-xl border border-slate-700">
                  <h4 className="text-lg font-bold text-white mb-4">Highlights</h4>
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    {aiAnalysis.highlights.bestDay && (
                      <div>
                        <p className="text-slate-400 text-sm mb-1">Best Day</p>
                        <p className="text-white font-semibold">{aiAnalysis.highlights.bestDay}</p>
                      </div>
                    )}
                    {aiAnalysis.highlights.topActivity && (
                      <div>
                        <p className="text-slate-400 text-sm mb-1">Top Activity</p>
                        <p className="text-white font-semibold">{aiAnalysis.highlights.topActivity}</p>
                      </div>
                    )}
                    {aiAnalysis.highlights.improvement && (
                      <div>
                        <p className="text-slate-400 text-sm mb-1">Area to Improve</p>
                        <p className="text-white font-semibold">{aiAnalysis.highlights.improvement}</p>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}
          
          {/* Initial State */}
          {!aiAnalysis && !aiLoading && !aiError && (
            <div className="bg-slate-800 p-12 rounded-xl border border-slate-700 text-center">
              <Sparkles size={48} className="text-purple-400 mx-auto mb-4" />
              <p className="text-slate-300 text-lg">Click &quot;Analyze My Activity&quot; to get AI-powered insights!</p>
              <p className="text-slate-500 text-sm mt-2">Track activities while signed in, or sync existing sessions from Profile → Cloud Sync.</p>
            </div>
          )}
        </>
      )}
      
      {/* CO2 Equivalencies Tab */}
      {activeTab === 'equivalencies' && (
        <>
          <h3 className="text-xl font-bold text-white mb-4">Environmental Impact</h3>
          
          {/* CO2 Conserved Equivalencies */}
          {stats.co2Conserved > 0 && (
            <div className="bg-gradient-to-br from-green-900/50 to-slate-800 p-6 rounded-xl border border-green-700/50 mb-6">
              <div className="flex items-center gap-2 mb-4">
                <Leaf size={24} className="text-green-400" />
                <h4 className="text-xl font-bold text-white">CO2 Conserved</h4>
              </div>
              <p className="text-3xl font-bold text-green-400 mb-2">{formatCO2Value(stats.co2Conserved)}</p>
              <p className="text-slate-300 text-sm mb-6">By choosing eco-friendly transportation, you saved:</p>
              
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {conservationEquivalencies.map((eq, idx) => (
                  <div key={idx} className="bg-slate-800/50 p-4 rounded-lg border border-green-700/30">
                    <div className="flex items-start gap-3">
                      <span className="text-3xl">{eq.icon}</span>
                      <div className="flex-1">
                        <p className="text-sm text-slate-400 mb-1">{eq.label}</p>
                        <p className="text-lg font-bold text-white">{eq.value.toFixed(2)} {eq.unit}</p>
                        <p className="text-xs text-slate-400 mt-1">{eq.description}</p>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* CO2 Emitted Equivalencies */}
          {stats.co2Emissions > 0 && (
            <div className="bg-gradient-to-br from-red-900/50 to-slate-800 p-6 rounded-xl border border-red-700/50 mb-6">
              <div className="flex items-center gap-2 mb-4">
                <Flame size={24} className="text-red-400" />
                <h4 className="text-xl font-bold text-white">CO2 Emitted</h4>
              </div>
              <p className="text-3xl font-bold text-red-400 mb-2">{formatCO2Value(stats.co2Emissions)}</p>
              <p className="text-slate-300 text-sm mb-6">Your carbon-emitting activities are equivalent to:</p>
              
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {emissionEquivalencies.map((eq, idx) => (
                  <div key={idx} className="bg-slate-800/50 p-4 rounded-lg border border-red-700/30">
                    <div className="flex items-start gap-3">
                      <span className="text-3xl">{eq.icon}</span>
                      <div className="flex-1">
                        <p className="text-sm text-slate-400 mb-1">{eq.label}</p>
                        <p className="text-lg font-bold text-white">{eq.value.toFixed(2)} {eq.unit}</p>
                        <p className="text-xs text-slate-400 mt-1">{eq.description}</p>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Net Impact Summary */}
          <div className="bg-slate-800 p-6 rounded-xl border border-slate-700">
            <h4 className="text-white font-semibold mb-4">Net Environmental Impact</h4>
            <div className="flex items-center justify-between mb-2">
              <span className="text-slate-300">CO2 Conserved:</span>
              <span className="text-green-400 font-bold">{formatCO2Value(stats.co2Conserved)}</span>
            </div>
            <div className="flex items-center justify-between mb-2">
              <span className="text-slate-300">CO2 Emitted:</span>
              <span className="text-red-400 font-bold">{formatCO2Value(stats.co2Emissions)}</span>
            </div>
            <div className="border-t border-slate-700 pt-2 mt-2">
              <div className="flex items-center justify-between">
                <span className="text-white font-bold">Net Impact:</span>
                <span className={`font-bold text-xl ${(stats.co2Conserved - stats.co2Emissions) >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                  {(stats.co2Conserved - stats.co2Emissions) >= 0 ? '+' : ''}{formatCO2Value(stats.co2Conserved - stats.co2Emissions)}
                </span>
              </div>
              {(() => {
                const netImpact = stats.co2Conserved - stats.co2Emissions;
                const equivs = getNetImpactEquivalencies(netImpact);
                return equivs.length > 0 ? (
                  <div className="mt-3 space-y-2">
                    {equivs.map((equiv, idx) => (
                      <div key={idx} className="p-3 bg-slate-700/50 rounded-lg border border-slate-600">
                        <p className="text-sm text-slate-300">
                          <span className="text-xl mr-2">{equiv.icon}</span>
                          {equiv.description}
                        </p>
                      </div>
                    ))}
                    <p className="text-xs text-slate-400">Data from Impact CO₂ / ADEME</p>
                  </div>
                ) : null;
              })()}
              <p className="text-xs text-slate-400 mt-2">
                {(stats.co2Conserved - stats.co2Emissions) >= 0 
                  ? '✓ Great job! You had a positive environmental impact this session.' 
                  : 'Consider eco-friendly alternatives to reduce your carbon footprint.'}
              </p>
            </div>
          </div>
        </>
      )}

    </div>
  );
};