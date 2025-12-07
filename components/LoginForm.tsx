import React, { useState } from 'react';

interface LoginFormProps {
  onSubmit: (email: string, password: string) => Promise<void> | void;
  error?: string | null;
}

export const LoginForm: React.FC<LoginFormProps> = ({ onSubmit, error }) => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setLoading(true);
    await onSubmit(email, password);
    setLoading(false);
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-950 px-4">
      <div className="w-full max-w-md bg-slate-900 border border-slate-800 rounded-2xl p-8 shadow-2xl shadow-slate-900/40">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-bold text-white">Kinetic Eco Tracker</h1>
          <p className="text-slate-400 text-sm mt-2">Sign in to track your daily impact</p>
        </div>

        <form className="space-y-4" onSubmit={handleSubmit}>
          <div>
            <label className="text-slate-300 text-sm font-medium">Email</label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="w-full mt-1 rounded-lg bg-slate-800 border border-slate-700 px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-green-400"
              placeholder="you@example.com"
              required
            />
          </div>

          <div>
            <label className="text-slate-300 text-sm font-medium">Password</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full mt-1 rounded-lg bg-slate-800 border border-slate-700 px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-green-400"
              placeholder="********"
              required
            />
          </div>

          {error && (
            <div className="text-sm text-red-400 bg-red-500/10 border border-red-500/40 rounded-lg px-4 py-2">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-gradient-to-r from-green-400 to-blue-500 text-white font-semibold py-3 rounded-lg hover:opacity-90 transition disabled:opacity-60"
          >
            {loading ? 'Signing in...' : 'Sign in'}
          </button>
        </form>
        <p className="text-xs text-slate-500 text-center mt-4">
          First time here? Enter your email and password to create a profile.
        </p>
      </div>
    </div>
  );
};










