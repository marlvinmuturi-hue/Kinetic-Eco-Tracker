import React, { useState } from 'react';
import { Mail, Lock, ArrowRight } from 'lucide-react';

interface LoginFormProps {
  onSubmit: (email: string, password: string) => Promise<void> | void;
  onGoogleSignIn?: () => Promise<void> | void;
  onForgotPassword?: (email: string) => Promise<void> | void;
  error?: string | null;
}

export const LoginForm: React.FC<LoginFormProps> = ({ 
  onSubmit, 
  onGoogleSignIn,
  onForgotPassword,
  error 
}) => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [googleLoading, setGoogleLoading] = useState(false);
  const [showForgotPassword, setShowForgotPassword] = useState(false);
  const [forgotPasswordEmail, setForgotPasswordEmail] = useState('');
  const [forgotPasswordLoading, setForgotPasswordLoading] = useState(false);
  const [forgotPasswordMessage, setForgotPasswordMessage] = useState<string | null>(null);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setLoading(true);
    await onSubmit(email, password);
    setLoading(false);
  };

  const handleGoogleSignIn = async () => {
    if (!onGoogleSignIn) return;
    setGoogleLoading(true);
    try {
      await onGoogleSignIn();
    } catch (err) {
      console.error('Google sign-in error:', err);
    } finally {
      setGoogleLoading(false);
    }
  };

  const handleForgotPassword = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!onForgotPassword || !forgotPasswordEmail.trim()) {
      return;
    }
    
    setForgotPasswordLoading(true);
    setForgotPasswordMessage(null);
    
    try {
      await onForgotPassword(forgotPasswordEmail);
      setForgotPasswordMessage('If an account exists with this email, a password reset link has been sent! Check your inbox and follow the instructions.');
    } catch (err: any) {
      // Even on error, we show a generic message for security, 
      // unless it's a specific user-actionable error like "invalid-email"
      if (err.message?.includes('email')) {
        setForgotPasswordMessage('Invalid email address.');
      } else {
        setForgotPasswordMessage('If an account exists with this email, a password reset link has been sent!');
      }
    } finally {
      setForgotPasswordLoading(false);
    }
  };

  if (showForgotPassword) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-950 px-4 safe-area-inset">
        <div className="w-full max-w-md bg-slate-900 border border-slate-800 rounded-2xl p-6 sm:p-8 shadow-2xl shadow-slate-900/40">
          <div className="mb-6 text-center">
            <h1 className="text-2xl font-bold text-white">Reset Password</h1>
            <p className="text-slate-400 text-sm mt-2">Enter your email to receive password reset instructions</p>
          </div>

          <form className="space-y-4" onSubmit={handleForgotPassword}>
            <div>
              <label className="text-slate-300 text-sm font-medium flex items-center space-x-2">
                <Mail size={16} />
                <span>Email</span>
              </label>
              <input
                type="email"
                value={forgotPasswordEmail}
                onChange={(e) => setForgotPasswordEmail(e.target.value)}
                className="w-full mt-1 rounded-lg bg-slate-800 border border-slate-700 px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-green-400"
                placeholder="you@example.com"
                required
                autoFocus
              />
            </div>

            {forgotPasswordMessage && (
              <div className={`text-sm rounded-lg px-4 py-2 ${
                forgotPasswordMessage.includes('sent') 
                  ? 'text-green-400 bg-green-500/10 border border-green-500/40' 
                  : 'text-red-400 bg-red-500/10 border border-red-500/40'
              }`}>
                {forgotPasswordMessage}
              </div>
            )}

            <div className="flex space-x-3">
              <button
                type="button"
                onClick={() => {
                  setShowForgotPassword(false);
                  setForgotPasswordEmail('');
                  setForgotPasswordMessage(null);
                }}
                className="flex-1 bg-slate-700 hover:bg-slate-600 text-white font-semibold py-3 rounded-lg transition min-h-[44px] touch-manipulation"
              >
                Back
              </button>
              <button
                type="submit"
                disabled={forgotPasswordLoading || !forgotPasswordEmail.trim()}
                className="flex-1 bg-gradient-to-r from-green-400 to-blue-500 text-white font-semibold py-3 rounded-lg hover:opacity-90 active:opacity-80 transition disabled:opacity-60 min-h-[44px] touch-manipulation flex items-center justify-center space-x-2"
              >
                {forgotPasswordLoading ? (
                  <span>Sending...</span>
                ) : (
                  <>
                    <span>Send Reset Link</span>
                    <ArrowRight size={18} />
                  </>
                )}
              </button>
            </div>
          </form>

          <p className="text-xs text-slate-500 text-center mt-4">
            Remember your password?{' '}
            <button
              onClick={() => {
                setShowForgotPassword(false);
                setForgotPasswordEmail('');
                setForgotPasswordMessage(null);
              }}
              className="text-green-400 hover:text-green-300 underline"
            >
              Sign in
            </button>
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-950 px-4 safe-area-inset">
      <div className="w-full max-w-md bg-slate-900 border border-slate-800 rounded-2xl p-6 sm:p-8 shadow-2xl shadow-slate-900/40">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-bold text-white">Kinetic Eco Tracker</h1>
          <p className="text-slate-400 text-sm mt-2">Sign in to track your daily impact</p>
        </div>

        {/* Google Sign-In Button */}
        {onGoogleSignIn && (
          <button
            type="button"
            onClick={handleGoogleSignIn}
            disabled={googleLoading || loading}
            className="w-full mb-4 bg-white hover:bg-gray-100 text-gray-700 font-semibold py-3 rounded-lg transition disabled:opacity-60 min-h-[44px] touch-manipulation flex items-center justify-center space-x-3 border border-slate-700"
          >
            {googleLoading ? (
              <span>Signing in with Google...</span>
            ) : (
              <>
                <svg className="w-5 h-5" viewBox="0 0 24 24">
                  <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
                  <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
                  <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
                  <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
                </svg>
                <span>Continue with Google</span>
              </>
            )}
          </button>
        )}

        {/* Divider */}
        {onGoogleSignIn && (
          <div className="relative mb-4">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-slate-700"></div>
            </div>
            <div className="relative flex justify-center text-sm">
              <span className="px-2 bg-slate-900 text-slate-400">Or continue with email</span>
            </div>
          </div>
        )}

        <form className="space-y-4" onSubmit={handleSubmit}>
          <div>
            <label className="text-slate-300 text-sm font-medium flex items-center space-x-2">
              <Mail size={16} />
              <span>Email</span>
            </label>
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
            <div className="flex items-center justify-between mb-1">
              <label className="text-slate-300 text-sm font-medium flex items-center space-x-2">
                <Lock size={16} />
                <span>Password</span>
              </label>
              {onForgotPassword && (
                <button
                  type="button"
                  onClick={() => {
                    setForgotPasswordEmail(email);
                    setShowForgotPassword(true);
                  }}
                  className="text-xs text-green-400 hover:text-green-300 underline"
                >
                  Forgot password?
                </button>
              )}
            </div>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full rounded-lg bg-slate-800 border border-slate-700 px-4 py-3 text-white focus:outline-none focus:ring-2 focus:ring-green-400"
              placeholder="********"
              required
            />
          </div>

          {error && (
            <div className="text-sm text-red-400 bg-red-500/10 border border-red-500/40 rounded-lg px-4 py-2 flex flex-col">
              <span>{error}</span>
              {error.includes('Incorrect password') && onForgotPassword && (
                <button
                  type="button"
                  onClick={() => {
                    setForgotPasswordEmail(email);
                    setShowForgotPassword(true);
                  }}
                  className="text-left text-xs text-green-400 hover:text-green-300 underline mt-1"
                >
                  Forgot password? Reset it here.
                </button>
              )}
            </div>
          )}

          <button
            type="submit"
            disabled={loading || googleLoading}
            className="w-full bg-gradient-to-r from-green-400 to-blue-500 text-white font-semibold py-3 rounded-lg hover:opacity-90 active:opacity-80 transition disabled:opacity-60 min-h-[44px] touch-manipulation"
          >
            {loading ? 'Signing in...' : 'Sign in'}
          </button>
        </form>
        <p className="text-xs text-slate-500 text-center mt-4">
          First time here? Enter your email and password to create a profile.
        </p>
        <p className="text-[10px] text-slate-600 text-center mt-2">
          Note: Google sign-in may redirect if popups are blocked.
        </p>
      </div>
    </div>
  );
};










