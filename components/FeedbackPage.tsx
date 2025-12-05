import React, { useState } from 'react';
import { MessageCircle } from 'lucide-react';

interface FeedbackPageProps {
  onBack: () => void;
}

export const FeedbackPage: React.FC<FeedbackPageProps> = ({ onBack }) => {
  const [text, setText] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!text.trim()) return;

    // For now just log locally; this can later be wired to Firebase/Email/etc.
    console.log('User feedback:', text);
    setSubmitted(true);
    setText('');
  };

  return (
    <div className="w-full max-w-3xl mx-auto p-6 space-y-6">
      <header className="flex items-center space-x-3">
        <div className="bg-slate-800 text-green-300 p-3 rounded-xl border border-slate-700">
          <MessageCircle size={24} />
        </div>
        <div>
          <h2 className="text-3xl font-bold text-white">Share your feedback</h2>
          <p className="text-slate-400 text-sm">
            Tell us what’s working well and what would make Kinetic even better.
          </p>
        </div>
      </header>

      <form
        onSubmit={handleSubmit}
        className="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4"
      >
        <label className="block text-sm font-medium text-slate-200 mb-1">
          Your thoughts
        </label>
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={6}
          className="w-full rounded-xl bg-slate-950 border border-slate-800 px-4 py-3 text-sm text-white placeholder:text-slate-600 focus:outline-none focus:ring-2 focus:ring-green-400"
          placeholder="Share ideas, bugs, or anything on your mind..."
        />

        {submitted && (
          <div className="text-xs text-green-300 bg-green-500/10 border border-green-500/40 rounded-lg px-3 py-2">
            Thank you for your feedback. We’ve recorded it for future improvements.
          </div>
        )}

        <div className="flex justify-between items-center pt-2">
          <button
            type="button"
            onClick={onBack}
            className="text-xs text-slate-400 hover:text-slate-200 underline"
          >
            Back
          </button>
          <button
            type="submit"
            disabled={!text.trim()}
            className="px-5 py-2 rounded-lg text-sm font-semibold bg-green-500 text-white hover:bg-green-400 disabled:opacity-50 disabled:cursor-not-allowed transition"
          >
            Submit feedback
          </button>
        </div>
      </form>
    </div>
  );
};



