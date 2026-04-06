/**
 * Mobile GPS Service
 * Enhanced geolocation handling for mobile devices with GPS capabilities
 */

export interface GPSOptions {
  enableHighAccuracy: boolean;
  timeout: number;
  maximumAge: number;
  watch?: boolean;
}

export interface GPSPosition {
  latitude: number;
  longitude: number;
  accuracy: number;
  altitude: number | null;
  altitudeAccuracy: number | null;
  heading: number | null;
  speed: number | null;
  timestamp: number;
}

export class MobileGPSService {
  private watchId: number | null = null;
  private isMobile: boolean;
  private supportsGPS: boolean = false;

  constructor() {
    this.isMobile = this.detectMobile();
    this.supportsGPS = this.checkGPSSupport();
  }

  /**
   * Detect if running on a mobile device
   */
  private detectMobile(): boolean {
    return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(
      navigator.userAgent
    ) || 
    (window.matchMedia && window.matchMedia("(max-width: 768px)").matches) ||
    ('ontouchstart' in window);
  }

  /**
   * Check if device supports GPS/Geolocation
   */
  private checkGPSSupport(): boolean {
    return 'geolocation' in navigator;
  }

  /**
   * Get optimal GPS options for mobile devices
   * Optimized for fast activity detection and background execution
   */
  getMobileGPSOptions(): GPSOptions {
    if (this.isMobile) {
      // Mobile devices: Use high accuracy with faster updates for quick activity detection
      // Optimized for responsive activity detection (10-15 seconds)
      return {
        enableHighAccuracy: true, // Use GPS instead of network location
        timeout: 15000, // 15 seconds for GPS to get a fix (reduced from 20s)
        maximumAge: 0, // Always get fresh position (important for accurate activity detection)
        watch: true
      };
    } else {
      // Desktop: Standard options with faster updates
      return {
        enableHighAccuracy: true,
        timeout: 10000,
        maximumAge: 2000, // Accept cached position up to 2 seconds old (reduced from 5s)
        watch: true
      };
    }
  }
  
  /**
   * Get background-optimized GPS options
   * These settings help maintain tracking when tab is in background
   */
  getBackgroundGPSOptions(): GPSOptions {
    // For background, we want the most aggressive settings
    // to ensure GPS continues working even when throttled
    return {
      enableHighAccuracy: true, // Critical for background GPS
      timeout: 30000, // Longer timeout for background scenarios
      maximumAge: 0, // Never use stale data
      watch: true
    };
  }

  /**
   * Request current position with mobile-optimized settings
   */
  getCurrentPosition(
    onSuccess: (position: GeolocationPosition) => void,
    onError: (error: GeolocationPositionError) => void
  ): void {
    if (!this.supportsGPS) {
      onError({
        code: 0,
        message: 'GPS/Geolocation is not supported on this device',
        PERMISSION_DENIED: 1,
        POSITION_UNAVAILABLE: 2,
        TIMEOUT: 3
      } as GeolocationPositionError);
      return;
    }

    const options = this.getMobileGPSOptions();
    
    navigator.geolocation.getCurrentPosition(
      (position) => {
        console.log('GPS Position obtained:', {
          accuracy: position.coords.accuracy,
          altitude: position.coords.altitude,
          speed: position.coords.speed,
          heading: position.coords.heading
        });
        onSuccess(position);
      },
      onError,
      {
        enableHighAccuracy: options.enableHighAccuracy,
        timeout: options.timeout,
        maximumAge: options.maximumAge
      }
    );
  }

  /**
   * Start watching position with mobile-optimized settings
   * Returns watch ID for stopping later
   */
  watchPosition(
    onSuccess: (position: GeolocationPosition) => void,
    onError: (error: GeolocationPositionError) => void
  ): number | null {
    if (!this.supportsGPS) {
      onError({
        code: 0,
        message: 'GPS/Geolocation is not supported on this device',
        PERMISSION_DENIED: 1,
        POSITION_UNAVAILABLE: 2,
        TIMEOUT: 3
      } as GeolocationPositionError);
      return null;
    }

    const options = this.getMobileGPSOptions();

    this.watchId = navigator.geolocation.watchPosition(
      (position) => {
        console.log('GPS Update:', {
          accuracy: position.coords.accuracy,
          speed: position.coords.speed,
          timestamp: new Date(position.timestamp).toISOString()
        });
        onSuccess(position);
      },
      onError,
      {
        enableHighAccuracy: options.enableHighAccuracy,
        timeout: options.timeout,
        maximumAge: options.maximumAge
      }
    );

    return this.watchId;
  }

  /**
   * Stop watching position
   */
  stopWatching(): void {
    if (this.watchId !== null) {
      navigator.geolocation.clearWatch(this.watchId);
      this.watchId = null;
      console.log('GPS watching stopped');
    }
  }

  /**
   * Check GPS permission status
   */
  async checkPermission(): Promise<PermissionState | null> {
    if (!('permissions' in navigator)) {
      return null; // Permissions API not supported
    }

    try {
      const result = await navigator.permissions.query({ name: 'geolocation' });
      return result.state;
    } catch (error) {
      console.warn('Could not check geolocation permission:', error);
      return null;
    }
  }

  /**
   * Request GPS permission explicitly
   */
  async requestPermission(): Promise<boolean> {
    return new Promise((resolve) => {
      this.getCurrentPosition(
        () => {
          console.log('GPS permission granted');
          resolve(true);
        },
        (error) => {
          console.error('GPS permission denied:', error);
          resolve(false);
        }
      );
    });
  }

  /**
   * Get device capabilities info
   */
  getDeviceInfo(): {
    isMobile: boolean;
    supportsGPS: boolean;
    userAgent: string;
  } {
    return {
      isMobile: this.isMobile,
      supportsGPS: this.supportsGPS,
      userAgent: navigator.userAgent
    };
  }

  /**
   * Get enhanced position data with mobile-specific info
   */
  getEnhancedPosition(position: GeolocationPosition): GPSPosition {
    return {
      latitude: position.coords.latitude,
      longitude: position.coords.longitude,
      accuracy: position.coords.accuracy,
      altitude: position.coords.altitude,
      altitudeAccuracy: position.coords.altitudeAccuracy,
      heading: position.coords.heading,
      speed: position.coords.speed,
      timestamp: position.timestamp
    };
  }
}

// Export singleton instance
export const mobileGPS = new MobileGPSService();

