/**
 * Media Session Service
 * Provides Media Session API support for Android play/pause controls
 * This enables Android to detect the app as having play functionality
 */

interface MediaSessionState {
  isActive: boolean;
  duration: number;
  activity: string;
  speed: string;
}

class MediaSessionService {
  private state: MediaSessionState = {
    isActive: false,
    duration: 0,
    activity: 'IDLE',
    speed: '0 km/h'
  };

  /**
   * Initialize Media Session API
   */
  public initialize(
    onPlay: () => void,
    onPause: () => void
  ): void {
    if (!('mediaSession' in navigator)) {
      console.log('Media Session API not supported');
      return;
    }

    const mediaSession = navigator.mediaSession;

    // Set up play action
    try {
      mediaSession.setActionHandler('play', () => {
        console.log('Media Session: Play action triggered');
        onPlay();
      });

      // Set up pause action
      mediaSession.setActionHandler('pause', () => {
        console.log('Media Session: Pause action triggered');
        onPause();
      });

      // Set up stop action (also stops tracking)
      mediaSession.setActionHandler('stop', () => {
        console.log('Media Session: Stop action triggered');
        onPause();
      });

      console.log('Media Session API initialized successfully');
    } catch (error) {
      console.warn('Failed to set Media Session action handlers:', error);
    }
  }

  /**
   * Update metadata when tracking starts
   */
  public updateMetadata(state: Partial<MediaSessionState>): void {
    if (!('mediaSession' in navigator)) {
      return;
    }

    this.state = { ...this.state, ...state };

    const mediaSession = navigator.mediaSession;

    try {
      mediaSession.metadata = new MediaMetadata({
        title: 'Kinetic Eco Tracker',
        artist: state.isActive ? 'Tracking Active' : 'Tracking Stopped',
        album: `${state.activity || 'IDLE'} • ${state.speed || '0 km/h'}`,
        artwork: [
          {
            src: '/icon-192x192.png',
            sizes: '192x192',
            type: 'image/png'
          },
          {
            src: '/icon-512x512.png',
            sizes: '512x512',
            type: 'image/png'
          }
        ]
      });

      // Set playback state
      if (state.isActive) {
        mediaSession.playbackState = 'playing';
      } else {
        mediaSession.playbackState = 'paused';
      }

      console.log('Media Session metadata updated:', this.state);
    } catch (error) {
      console.warn('Failed to update Media Session metadata:', error);
    }
  }

  /**
   * Set playback state
   */
  public setPlaybackState(isActive: boolean): void {
    if (!('mediaSession' in navigator)) {
      return;
    }

    this.state.isActive = isActive;

    try {
      const mediaSession = navigator.mediaSession;
      mediaSession.playbackState = isActive ? 'playing' : 'paused';
      console.log('Media Session playback state:', mediaSession.playbackState);
    } catch (error) {
      console.warn('Failed to set Media Session playback state:', error);
    }
  }

  /**
   * Clear Media Session (when app closes)
   */
  public clear(): void {
    if (!('mediaSession' in navigator)) {
      return;
    }

    try {
      const mediaSession = navigator.mediaSession;
      mediaSession.metadata = null;
      mediaSession.playbackState = 'none';
      console.log('Media Session cleared');
    } catch (error) {
      console.warn('Failed to clear Media Session:', error);
    }
  }
}

export const mediaSessionService = new MediaSessionService();















