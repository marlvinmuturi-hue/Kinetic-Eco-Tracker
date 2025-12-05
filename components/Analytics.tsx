import React, { useEffect, useState } from 'react';
import { SessionStats, ActivityType } from '../types';
import { ACTIVITY_COLORS } from '../constants';
import { generateEcoInsight } from '../services/geminiService';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip as RechartsTooltip, BarChart, Bar, XAxis, YAxis } from 'recharts';
import { ActivityIcon } from './ActivityIcon';
import { Leaf, Flame, Timer, Info } from 'lucide-react';

interface AnalyticsProps {
  stats: SessionStats;
  onReset: () => void;
}

export const Analytics: React.FC<AnalyticsProps> = ({ stats, onReset }) => {
  const [insight, setInsight] = useState<string>('Generating AI insight...');
  const [loading, setLoading] = useState(true);

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

  const pieData = (Object.entries(stats.breakdown) as [ActivityType, { time: number; distance: number }][])
    .filter(([_, data]) => data.time > 0)
    .map(([type, data]) => ({
      name: type,
      value: data.time,
      color: ACTIVITY_COLORS[type]
    }));
  
  const emissionData = [
    { name: 'Your Trip', co2: stats.co2Emissions },
    { name: 'Tree Absorption (Daily)', co2: 0.06 }, // Approx daily absorption of mature tree
  ];

  return (
    <div className="w-full max-w-4xl mx-auto p-4 pb-24">
      <h2 className="text-3xl font-bold text-white mb-6">Session Summary</h2>
      
      {/* Top Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-8">
        <div className="bg-slate-800 p-5 rounded-xl border border-slate-700 relative overflow-hidden">
          <div className="absolute top-0 right-0 p-4 opacity-10">
            <Leaf size={64} />
          </div>
          <div className="flex items-center space-x-2 text-green-400 mb-2">
            <Leaf size={20} />
            <span className="font-semibold uppercase text-xs">CO2 Emissions</span>
          </div>
          <p className="text-3xl font-bold text-white">{stats.co2Emissions.toFixed(3)} <span className="text-lg text-slate-400 font-normal">kg</span></p>
        </div>

        <div className="bg-slate-800 p-5 rounded-xl border border-slate-700 relative overflow-hidden">
          <div className="absolute top-0 right-0 p-4 opacity-10">
            <Flame size={64} />
          </div>
          <div className="flex items-center space-x-2 text-orange-400 mb-2">
            <Flame size={20} />
            <span className="font-semibold uppercase text-xs">Calories Burned</span>
          </div>
          <p className="text-3xl font-bold text-white">{Math.round(stats.caloriesBurned)} <span className="text-lg text-slate-400 font-normal">kcal</span></p>
        </div>

        <div className="bg-slate-800 p-5 rounded-xl border border-slate-700 relative overflow-hidden">
          <div className="absolute top-0 right-0 p-4 opacity-10">
            <Timer size={64} />
          </div>
          <div className="flex items-center space-x-2 text-blue-400 mb-2">
            <Timer size={20} />
            <span className="font-semibold uppercase text-xs">Total Time</span>
          </div>
          <p className="text-3xl font-bold text-white">{(stats.totalDuration / 60).toFixed(1)} <span className="text-lg text-slate-400 font-normal">min</span></p>
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
                    formatter={(value: number) => [`${(value / 60).toFixed(1)} min`, 'Time']}
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
        <h3 className="text-white font-semibold mb-4">Environmental Impact Context</h3>
        <p className="text-slate-400 text-sm mb-4">Your trip's emissions vs what a mature tree absorbs daily.</p>
        <div className="h-48 w-full">
           <ResponsiveContainer width="100%" height="100%">
              <BarChart data={emissionData} layout="vertical">
                <XAxis type="number" hide />
                <YAxis dataKey="name" type="category" width={150} tick={{fill: '#94a3b8', fontSize: 12}} axisLine={false} tickLine={false} />
                <RechartsTooltip cursor={{fill: 'transparent'}} contentStyle={{ backgroundColor: '#1e293b', border: 'none', color: '#fff' }} />
                <Bar dataKey="co2" fill="#22c55e" radius={[0, 4, 4, 0]} barSize={20} />
              </BarChart>
           </ResponsiveContainer>
        </div>
      </div>

      <button
        onClick={onReset}
        className="w-full bg-slate-700 hover:bg-slate-600 text-white font-bold py-4 rounded-xl transition-colors"
      >
        Start New Session
      </button>
    </div>
  );
};