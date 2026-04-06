/**
 * Device Detection Utilities
 * Detect Android WebView, device capabilities, and optimize accordingly
 */

/**
 * Check if running in Android WebView
 */
export const isAndroidWebView = (): boolean => {
  if (typeof window === 'undefined') return false;
  
  // Check for Android interface
  if ((window as any).AndroidInterface) {
    return true;
  }
  
  // Check user agent
  const ua = navigator.userAgent.toLowerCase();
  const isAndroid = ua.indexOf('android') > -1;
  const isWebView = ua.indexOf('wv') > -1 || ua.indexOf('webview') > -1;
  
  return isAndroid && isWebView;
};

/**
 * Check if running on Android (any browser)
 */
export const isAndroid = (): boolean => {
  if (typeof window === 'undefined') return false;
  return /android/i.test(navigator.userAgent);
};

/**
 * Check if running on iOS
 */
export const isIOS = (): boolean => {
  if (typeof window === 'undefined') return false;
  return /iPad|iPhone|iPod/.test(navigator.userAgent);
};

/**
 * Check if running on mobile device
 */
export const isMobile = (): boolean => {
  if (typeof window === 'undefined') return false;
  return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
};

/**
 * Get device memory (if available)
 * Returns memory in GB
 */
export const getDeviceMemory = (): number | null => {
  if (typeof window === 'undefined') return null;
  
  // @ts-ignore - deviceMemory is not in all browsers
  const memory = (navigator as any).deviceMemory;
  return memory || null;
};

/**
 * Check if device is low-end (< 2GB RAM)
 */
export const isLowEndDevice = (): boolean => {
  const memory = getDeviceMemory();
  if (memory === null) return false; // Can't determine, assume not low-end
  return memory < 2;
};

/**
 * Get hardware concurrency (CPU cores)
 */
export const getCPUCores = (): number => {
  if (typeof window === 'undefined') return 4; // Default
  return navigator.hardwareConcurrency || 4;
};

/**
 * Check if device has good performance
 */
export const hasGoodPerformance = (): boolean => {
  const memory = getDeviceMemory();
  const cores = getCPUCores();
  
  // Good if has 4+ GB RAM or 4+ CPU cores
  return (memory !== null && memory >= 4) || cores >= 4;
};

/**
 * Determine if we should use simplified UI
 */
export const shouldUseSimplifiedUI = (): boolean => {
  return isLowEndDevice() || (isAndroidWebView() && !hasGoodPerformance());
};

/**
 * Get recommended chart animation duration
 * Low-end devices get faster/no animations
 */
export const getRecommendedAnimationDuration = (): number => {
  if (shouldUseSimplifiedUI()) return 0; // No animations
  if (isLowEndDevice()) return 200; // Fast animations
  return 500; // Normal animations
};

/**
 * Check if device supports hardware acceleration
 */
export const supportsHardwareAcceleration = (): boolean => {
  if (typeof window === 'undefined') return false;
  
  const canvas = document.createElement('canvas');
  const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
  
  return !!gl;
};

/**
 * Log device info (for debugging)
 */
export const logDeviceInfo = () => {
  console.group('🔍 Device Information');
  console.log('Android WebView:', isAndroidWebView());
  console.log('Android:', isAndroid());
  console.log('iOS:', isIOS());
  console.log('Mobile:', isMobile());
  console.log('Device Memory:', getDeviceMemory(), 'GB');
  console.log('CPU Cores:', getCPUCores());
  console.log('Low-End Device:', isLowEndDevice());
  console.log('Good Performance:', hasGoodPerformance());
  console.log('Hardware Acceleration:', supportsHardwareAcceleration());
  console.log('User Agent:', navigator.userAgent);
  console.groupEnd();
};
