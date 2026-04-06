/**
 * Android-specific service utilities
 * Provides Android detection and optimizations
 */

class AndroidService {
  /**
   * Check if running on Android device
   */
  public isAndroid(): boolean {
    if (typeof window === 'undefined') return false;
    return /Android/i.test(navigator.userAgent);
  }

  /**
   * Check if running in standalone mode (PWA installed)
   */
  public isStandalone(): boolean {
    if (typeof window === 'undefined') return false;
    return (
      (window.matchMedia('(display-mode: standalone)').matches) ||
      ((window.navigator as any).standalone === true) ||
      document.referrer.includes('android-app://')
    );
  }

  /**
   * Get Android version (if available)
   */
  public getAndroidVersion(): number | null {
    if (!this.isAndroid()) return null;
    
    const match = navigator.userAgent.match(/Android (\d+)/);
    return match ? parseInt(match[1], 10) : null;
  }

  /**
   * Check if Chrome browser on Android
   */
  public isChrome(): boolean {
    if (typeof window === 'undefined') return false;
    return /Chrome/i.test(navigator.userAgent) && this.isAndroid();
  }

  /**
   * Request fullscreen (for better Android experience)
   */
  public async requestFullscreen(): Promise<void> {
    if (typeof document === 'undefined') return;
    
    const doc = document.documentElement as any;
    if (doc.requestFullscreen) {
      await doc.requestFullscreen();
    } else if (doc.webkitRequestFullscreen) {
      await doc.webkitRequestFullscreen();
    } else if (doc.mozRequestFullScreen) {
      await doc.mozRequestFullScreen();
    } else if (doc.msRequestFullscreen) {
      await doc.msRequestFullscreen();
    }
  }

  /**
   * Exit fullscreen
   */
  public exitFullscreen(): void {
    if (typeof document === 'undefined') return;
    
    const doc = document as any;
    if (doc.exitFullscreen) {
      doc.exitFullscreen();
    } else if (doc.webkitExitFullscreen) {
      doc.webkitExitFullscreen();
    } else if (doc.mozCancelFullScreen) {
      doc.mozCancelFullScreen();
    } else if (doc.msExitFullscreen) {
      doc.msExitFullscreen();
    }
  }

  /**
   * Check if currently in fullscreen
   */
  public isFullscreen(): boolean {
    if (typeof document === 'undefined') return false;
    
    const doc = document as any;
    return !!(
      doc.fullscreenElement ||
      doc.webkitFullscreenElement ||
      doc.mozFullScreenElement ||
      doc.msFullscreenElement
    );
  }

  /**
   * Vibrate (haptic feedback) - Android feature
   */
  public vibrate(pattern: number | number[]): boolean {
    if (typeof navigator === 'undefined' || !navigator.vibrate) {
      return false;
    }
    try {
      navigator.vibrate(pattern);
      return true;
    } catch (e) {
      return false;
    }
  }

  /**
   * Prevent Android default behaviors
   */
  public preventAndroidDefaults(): void {
    if (!this.isAndroid()) return;

    // Prevent pull-to-refresh
    document.addEventListener('touchmove', (e) => {
      if (e.touches.length > 1) {
        e.preventDefault();
      }
    }, { passive: false });

    // Prevent zoom on double tap
    let lastTouchEnd = 0;
    document.addEventListener('touchend', (e) => {
      const now = Date.now();
      if (now - lastTouchEnd <= 300) {
        e.preventDefault();
      }
      lastTouchEnd = now;
    }, false);
  }
}

export const androidService = new AndroidService();














