# Background Execution Optimization

This document describes the background execution optimizations implemented for the Kinetic Eco Tracker app.

## Features Implemented

### 1. **Wake Lock API Integration**
- Prevents the device from sleeping during active tracking
- Automatically requests wake lock when tracking starts
- Releases wake lock when tracking stops
- Handles wake lock release events (e.g., when user locks screen)
- Automatically re-acquires wake lock when possible

**Location**: `services/backgroundService.ts`

### 2. **Page Visibility API**
- Detects when the browser tab is in the background
- Maintains tracking even when the tab is not visible
- Optimizes behavior based on visibility state
- Logs visibility changes for debugging

**Location**: `services/backgroundService.ts`

### 3. **Service Worker**
- Enables background processing capabilities
- Handles offline data caching
- Implements background sync for data persistence
- Supports push notifications (ready for future use)
- Provides offline fallback

**Location**: `public/sw.js`

### 4. **Background Sync Service**
- Stores tracking data in IndexedDB when offline
- Syncs data when connection is restored
- Automatically cleans up old synced data
- Handles data persistence across sessions

**Location**: `services/backgroundSyncService.ts`

### 5. **GPS Background Optimization**
- Enhanced GPS options for background execution
- Longer timeouts for background scenarios
- Always uses fresh position data (maximumAge: 0)
- High accuracy mode for reliable tracking

**Location**: `services/mobileGPSService.ts`

## Browser Compatibility

### Wake Lock API
- ✅ Chrome/Edge 84+
- ✅ Firefox 96+
- ✅ Safari 15.4+ (iOS 15.4+)
- ⚠️ Not supported in older browsers (gracefully degrades)

### Page Visibility API
- ✅ All modern browsers
- ✅ Mobile browsers (iOS Safari, Chrome Mobile)

### Service Workers
- ✅ Chrome/Edge
- ✅ Firefox
- ✅ Safari 11.1+
- ✅ Mobile browsers

### Background Sync API
- ✅ Chrome/Edge
- ✅ Firefox
- ⚠️ Safari (limited support)

## How It Works

### When Tracking Starts
1. Wake Lock is requested to prevent device sleep
2. GPS tracking begins with background-optimized settings
3. Service Worker is ready for background processing
4. Visibility monitoring starts

### When Tab Goes to Background
1. Page Visibility API detects the change
2. GPS tracking continues (browsers may throttle but GPS continues)
3. Wake Lock may be released by browser (handled gracefully)
4. Data continues to be collected and stored

### When Tab Returns to Foreground
1. Page Visibility API detects the change
2. Wake Lock is re-acquired if needed
3. UI updates with latest tracking data
4. Any pending sync operations are triggered

### When Offline
1. Tracking data is stored in IndexedDB
2. Service Worker queues data for sync
3. When connection is restored, background sync triggers
4. Data is automatically synced to server

## Usage

The background optimizations are automatically enabled when you start tracking. No additional configuration is needed.

### Manual Control (if needed)

```typescript
import { getBackgroundService } from './services/backgroundService';

const bgService = getBackgroundService();

// Request wake lock manually
await bgService.requestWakeLock();

// Release wake lock
await bgService.releaseWakeLock();

// Check if page is visible
const isVisible = bgService.isPageVisible();
```

## Testing

### Test Wake Lock
1. Start tracking
2. Lock your device screen
3. Unlock after a few seconds
4. Check console logs - wake lock should be re-acquired

### Test Background Tracking
1. Start tracking
2. Switch to another app or minimize browser
3. Wait 30 seconds
4. Return to the app
5. Check that distance/duration continued to update

### Test Service Worker
1. Open DevTools > Application > Service Workers
2. Verify service worker is registered
3. Check "Offline" checkbox
4. Start tracking - data should be cached
5. Go back online - data should sync

## Limitations

1. **Browser Throttling**: Some browsers throttle background tabs, which may affect update frequency
2. **Wake Lock Restrictions**: 
   - May be released when device is locked
   - Requires user interaction to acquire
   - Not available in all browsers
3. **GPS Accuracy**: Background GPS may have slightly reduced accuracy in some scenarios
4. **Battery Usage**: Continuous GPS tracking will consume battery (expected behavior)

## Best Practices

1. **Always request wake lock when tracking starts** - Already implemented
2. **Handle wake lock release gracefully** - Already implemented
3. **Use background sync for offline data** - Ready for use when backend is implemented
4. **Monitor visibility changes** - Already implemented
5. **Test on real devices** - Background behavior may differ from desktop

## Future Enhancements

- [ ] Implement full IndexedDB integration for offline storage
- [ ] Add push notifications for tracking milestones
- [ ] Implement periodic background sync
- [ ] Add battery level monitoring
- [ ] Optimize GPS update frequency based on activity type















