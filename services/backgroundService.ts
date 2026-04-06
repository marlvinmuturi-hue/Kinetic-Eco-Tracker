/**
 * Background Service
 * Handles background execution optimizations for the tracking app
 * - Page Visibility API: Detects when tab is in background
 * - Wake Lock API: Prevents device from sleeping during tracking
 * - Background sync: Queues data when offline
 */

export interface BackgroundServiceCallbacks {
  onVisibilityChange?: (isVisible: boolean) => void;
  onWakeLockAcquired?: () => void;
  onWakeLockReleased?: () => void;
  onWakeLockError?: (error: Error) => void;
}

export class BackgroundService {
  private wakeLock: WakeLockSentinel | null = null;
  private isVisible: boolean = true;
  private callbacks: BackgroundServiceCallbacks = {};
  private visibilityChangeHandler: (() => void) | null = null;

  constructor(callbacks: BackgroundServiceCallbacks = {}) {
    this.callbacks = callbacks;
    this.init();
  }

  /**
   * Initialize background service
   */
  private init(): void {
    this.setupVisibilityListener();
  }

  /**
   * Setup Page Visibility API listener
   */
  private setupVisibilityListener(): void {
    if (typeof document === 'undefined') return;

    this.visibilityChangeHandler = () => {
      const wasVisible = this.isVisible;
      this.isVisible = !document.hidden;

      if (wasVisible !== this.isVisible) {
        console.log(`Page visibility changed: ${this.isVisible ? 'visible' : 'hidden'}`);
        this.callbacks.onVisibilityChange?.(this.isVisible);

        // Release wake lock when hidden to save battery (optional)
        // Or keep it active for continuous tracking
        // if (!this.isVisible && this.wakeLock) {
        //   this.releaseWakeLock();
        // }
      }
    };

    document.addEventListener('visibilitychange', this.visibilityChangeHandler);
    this.isVisible = !document.hidden;
  }

  /**
   * Request Wake Lock to prevent device from sleeping
   * This is crucial for continuous GPS tracking
   */
  async requestWakeLock(): Promise<boolean> {
    if (!('wakeLock' in navigator)) {
      console.warn('Wake Lock API is not supported in this browser');
      return false;
    }

    try {
      // Request wake lock
      this.wakeLock = await (navigator as any).wakeLock.request('screen');
      console.log('Wake Lock acquired - device will not sleep');

      // Handle wake lock release (e.g., user switches tabs, locks screen)
      this.wakeLock.addEventListener('release', () => {
        console.log('Wake Lock released');
        this.wakeLock = null;
        this.callbacks.onWakeLockReleased?.();
      });

      this.callbacks.onWakeLockAcquired?.();
      return true;
    } catch (error: any) {
      console.error('Error requesting Wake Lock:', error);
      this.callbacks.onWakeLockError?.(error);
      return false;
    }
  }

  /**
   * Release Wake Lock
   */
  async releaseWakeLock(): Promise<void> {
    if (this.wakeLock) {
      try {
        await this.wakeLock.release();
        this.wakeLock = null;
        console.log('Wake Lock manually released');
      } catch (error) {
        console.error('Error releasing Wake Lock:', error);
      }
    }
  }

  /**
   * Check if wake lock is active
   */
  isWakeLockActive(): boolean {
    return this.wakeLock !== null;
  }

  /**
   * Check if page is currently visible
   */
  isPageVisible(): boolean {
    return this.isVisible;
  }

  /**
   * Re-acquire wake lock if it was released
   * Useful when page becomes visible again
   */
  async reacquireWakeLockIfNeeded(): Promise<boolean> {
    if (!this.wakeLock && this.isVisible) {
      return await this.requestWakeLock();
    }
    return false;
  }

  /**
   * Cleanup - remove event listeners and release wake lock
   */
  cleanup(): void {
    if (this.visibilityChangeHandler) {
      document.removeEventListener('visibilitychange', this.visibilityChangeHandler);
    }
    this.releaseWakeLock();
  }

  /**
   * Update callbacks
   */
  updateCallbacks(callbacks: Partial<BackgroundServiceCallbacks>): void {
    this.callbacks = { ...this.callbacks, ...callbacks };
  }
}

// Export singleton instance
let backgroundServiceInstance: BackgroundService | null = null;

export const getBackgroundService = (callbacks?: BackgroundServiceCallbacks): BackgroundService => {
  if (!backgroundServiceInstance) {
    backgroundServiceInstance = new BackgroundService(callbacks);
  } else if (callbacks) {
    backgroundServiceInstance.updateCallbacks(callbacks);
  }
  return backgroundServiceInstance;
};















