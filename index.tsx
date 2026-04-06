import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';

// Mount React app immediately - don't wait for service worker
const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error("Could not find root element to mount to");
}

const root = ReactDOM.createRoot(rootElement);
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

// Register Service Worker for background processing (non-blocking)
if ('serviceWorker' in navigator) {
  // Use setTimeout to ensure it doesn't block app initialization
  setTimeout(() => {
    navigator.serviceWorker.register('/sw.js')
      .then((registration) => {
        console.log('Service Worker registered successfully:', registration.scope);
        
        // Check for updates
        registration.addEventListener('updatefound', () => {
          const newWorker = registration.installing;
          if (newWorker) {
            newWorker.addEventListener('statechange', () => {
              if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
                console.log('New service worker available. Reload to update.');
              }
            });
          }
        });
      })
      .catch((error) => {
        console.warn('Service Worker registration failed (non-critical):', error);
        // Don't block app if service worker fails
      });
    
    // Listen for service worker messages
    navigator.serviceWorker.addEventListener('message', (event) => {
      console.log('Message from service worker:', event.data);
      if (event.data && event.data.type === 'SW_UPDATED') {
        console.log('Service worker updated to version:', event.data.version);
        // Optionally reload page to get latest version immediately
        // Or let user refresh manually
      }
    });
    
    // Handle service worker controller changes
    let refreshing = false;
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (!refreshing) {
        refreshing = true;
        console.log('Service worker updated. Reloading page...');
        window.location.reload();
      }
    });

    // Periodically check for service worker updates
    setInterval(() => {
      navigator.serviceWorker.getRegistration().then((registration) => {
        if (registration) {
          registration.update();
        }
      });
    }, 60000); // Check every minute
  }, 100); // Small delay to ensure React app mounts first
}