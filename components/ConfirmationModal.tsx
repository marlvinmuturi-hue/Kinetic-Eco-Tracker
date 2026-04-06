import React from 'react';
import { Save, Trash2, X } from 'lucide-react';

interface ConfirmationModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  onDiscard: () => void;
  title: string;
  message: string;
  isSaving?: boolean;
}

export const ConfirmationModal: React.FC<ConfirmationModalProps> = ({
  isOpen,
  onClose,
  onConfirm,
  onDiscard,
  title,
  message,
  isSaving = false,
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-in fade-in duration-200">
      <div className="w-full max-w-sm bg-slate-900 border border-slate-800 rounded-2xl shadow-2xl p-6 animate-in zoom-in-95 duration-200">
        <div className="flex justify-between items-start mb-4">
          <h3 className="text-xl font-bold text-white">{title}</h3>
          <button 
            onClick={onClose}
            className="text-slate-500 hover:text-white transition"
          >
            <X size={20} />
          </button>
        </div>
        
        <p className="text-slate-400 text-sm mb-8">
          {message}
        </p>
        
        <div className="flex flex-col space-y-3">
          <button
            onClick={onConfirm}
            disabled={isSaving}
            className="w-full bg-gradient-to-r from-green-400 to-blue-500 text-white font-semibold py-3 rounded-xl hover:opacity-90 active:opacity-80 transition flex items-center justify-center space-x-2 disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {isSaving ? (
              <>
                <span className="inline-block w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin" />
                <span>Saving...</span>
              </>
            ) : (
              <>
                <Save size={18} />
                <span>Save Session</span>
              </>
            )}
          </button>
          
          <button
            onClick={onDiscard}
            disabled={isSaving}
            className="w-full bg-slate-800 text-red-400 border border-red-500/20 font-semibold py-3 rounded-xl hover:bg-red-500/10 active:opacity-80 transition flex items-center justify-center space-x-2 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Trash2 size={18} />
            <span>Discard Data</span>
          </button>
          
          <button
            onClick={onClose}
            disabled={isSaving}
            className="w-full bg-transparent text-slate-500 font-semibold py-2 rounded-xl hover:text-slate-300 transition disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
};
