/**
 * Service Worker for Kinetic Eco Tracker
 * Handles background sync and offline data persistence
 */

const CACHE_NAME = 'kinetic-eco-tracker-v1.4';
const RUNTIME_CACHE = 'kinetic-runtime-v1.4';

// Assets to cache on install
const STATIC_ASSETS = [
  '/',
  '/index.html',
  '/manifest.json'
];

// Install event - cache static assets
self.addEventListener('install', (event) => {
  console.log('[Service Worker] Installing...');
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => {
        console.log('[Service Worker] Caching static assets');
        return cache.addAll(STATIC_ASSETS);
      })
      .then(() => self.skipWaiting())
  );
});

// Activate event - clean up old caches
self.addEventListener('activate', (event) => {
  console.log('[Service Worker] Activating...');
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      // Delete all old caches that don't match current version
      return Promise.all(
        cacheNames
          .filter((name) => {
            // Delete caches from old versions (v1.0, v1.1, v1.2, v1.3, etc.)
            return name !== CACHE_NAME && name !== RUNTIME_CACHE;
          })
          .map((name) => {
            console.log('[Service Worker] Deleting old cache:', name);
            return caches.delete(name);
          })
      );
    })
    .then(() => {
      // Immediately take control of all clients to apply new service worker
      return self.clients.claim();
    })
    .then(() => {
      // Notify all clients to reload
      return self.clients.matchAll().then((clients) => {
        clients.forEach((client) => {
          client.postMessage({ type: 'SW_UPDATED', version: 'v1.4' });
        });
      });
    })
  );
});

// Fetch event - network first for HTML/JS, cache first for static assets
self.addEventListener('fetch', (event) => {
  // Only handle GET requests
  if (event.request.method !== 'GET') {
    return;
  }

  // Skip cross-origin requests
  if (!event.request.url.startsWith(self.location.origin)) {
    return;
  }

  const url = new URL(event.request.url);
  const isHTML = event.request.destination === 'document' || url.pathname.endsWith('.html');
  const isJS = event.request.destination === 'script' || url.pathname.endsWith('.js');
  const isCSS = event.request.destination === 'style' || url.pathname.endsWith('.css');
  
  // For HTML, JS, and CSS files: Network First strategy (always check for updates)
  if (isHTML || isJS || isCSS) {
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          // If successful, update cache and return fresh response
          if (response && response.status === 200 && response.type === 'basic') {
            const responseToCache = response.clone();
            caches.open(RUNTIME_CACHE).then((cache) => {
              cache.put(event.request, responseToCache);
            });
          }
          return response;
        })
        .catch(() => {
          // Network failed, try cache
          return caches.match(event.request).then((cachedResponse) => {
            if (cachedResponse) {
              return cachedResponse;
            }
            // If HTML request fails, return cached index.html
            if (isHTML) {
              return caches.match('/index.html');
            }
          });
        })
    );
    return;
  }

  // For other assets (images, fonts, etc.): Cache First strategy
  event.respondWith(
    caches.match(event.request)
      .then((cachedResponse) => {
        if (cachedResponse) {
          return cachedResponse;
        }

        return fetch(event.request)
          .then((response) => {
            // Don't cache non-successful responses
            if (!response || response.status !== 200 || response.type !== 'basic') {
              return response;
            }

            // Clone the response
            const responseToCache = response.clone();

            // Cache the response
            caches.open(RUNTIME_CACHE)
              .then((cache) => {
                cache.put(event.request, responseToCache);
              });

            return response;
          });
      })
  );
});

// Background sync for offline data persistence
self.addEventListener('sync', (event) => {
  console.log('[Service Worker] Background sync:', event.tag);
  
  if (event.tag === 'sync-tracking-data') {
    event.waitUntil(syncTrackingData());
  }
});

// Sync tracking data when back online
async function syncTrackingData() {
  try {
    // Get pending data from IndexedDB
    const pendingData = await getPendingTrackingData();
    
    if (pendingData && pendingData.length > 0) {
      console.log('[Service Worker] Syncing', pendingData.length, 'pending records');
      
      // Send data to server (when backend is implemented)
      // For now, just log it
      for (const data of pendingData) {
        console.log('[Service Worker] Syncing:', data);
        // await fetch('/api/tracking', { method: 'POST', body: JSON.stringify(data) });
      }
      
      // Clear pending data after successful sync
      await clearPendingTrackingData();
    }
  } catch (error) {
    console.error('[Service Worker] Sync error:', error);
    throw error; // Retry sync
  }
}

// Helper functions for IndexedDB (simplified - would need full IndexedDB implementation)
async function getPendingTrackingData() {
  // TODO: Implement IndexedDB retrieval
  return [];
}

async function clearPendingTrackingData() {
  // TODO: Implement IndexedDB cleanup
}

// Push notification handler (for future use)
self.addEventListener('push', (event) => {
  console.log('[Service Worker] Push notification received');
  
  const options = {
    body: event.data ? event.data.text() : 'New update available',
    icon: '/icon-192x192.png',
    badge: '/icon-96x96.png',
    vibrate: [200, 100, 200],
    tag: 'kinetic-notification',
    requireInteraction: false
  };

  event.waitUntil(
    self.registration.showNotification('Kinetic Eco Tracker', options)
  );
});

// Notification click handler
self.addEventListener('notificationclick', (event) => {
  console.log('[Service Worker] Notification clicked');
  event.notification.close();

  event.waitUntil(
    clients.openWindow('/')
  );
});

// Message handler for communication with main app
self.addEventListener('message', (event) => {
  console.log('[Service Worker] Message received:', event.data);
  
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
  
  if (event.data && event.data.type === 'CACHE_TRACKING_DATA') {
    // Store tracking data for offline sync
    event.waitUntil(cacheTrackingData(event.data.payload));
  }
});

async function cacheTrackingData(data) {
  // TODO: Store in IndexedDB for later sync
  console.log('[Service Worker] Caching tracking data:', data);
}

