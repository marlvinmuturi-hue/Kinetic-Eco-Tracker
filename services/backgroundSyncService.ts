/**
 * Background Sync Service
 * Handles offline data persistence and sync when back online
 */

export interface TrackingData {
  timestamp: number;
  latitude: number;
  longitude: number;
  speed: number;
  activity: string;
  distance: number;
  duration: number;
  userId?: string;
}

export class BackgroundSyncService {
  private dbName = 'kinetic-tracker-db';
  private dbVersion = 1;
  private storeName = 'tracking-data';
  private db: IDBDatabase | null = null;

  /**
   * Initialize IndexedDB
   */
  async init(): Promise<void> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, this.dbVersion);

      request.onerror = () => {
        console.error('IndexedDB error:', request.error);
        reject(request.error);
      };

      request.onsuccess = () => {
        this.db = request.result;
        console.log('IndexedDB initialized');
        resolve();
      };

      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;
        
        // Create object store if it doesn't exist
        if (!db.objectStoreNames.contains(this.storeName)) {
          const objectStore = db.createObjectStore(this.storeName, { keyPath: 'id', autoIncrement: true });
          objectStore.createIndex('timestamp', 'timestamp', { unique: false });
          objectStore.createIndex('userId', 'userId', { unique: false });
          objectStore.createIndex('synced', 'synced', { unique: false });
        }
      };
    });
  }

  /**
   * Store tracking data for offline sync
   */
  async storeTrackingData(data: TrackingData): Promise<void> {
    if (!this.db) {
      await this.init();
    }

    return new Promise((resolve, reject) => {
      if (!this.db) {
        reject(new Error('Database not initialized'));
        return;
      }

      const transaction = this.db.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      
      const record = {
        ...data,
        synced: false,
        createdAt: Date.now()
      };

      const request = store.add(record);

      request.onsuccess = () => {
        console.log('Tracking data stored for sync:', record);
        resolve();
      };

      request.onerror = () => {
        console.error('Error storing tracking data:', request.error);
        reject(request.error);
      };
    });
  }

  /**
   * Get pending (unsynced) tracking data
   */
  async getPendingData(): Promise<TrackingData[]> {
    if (!this.db) {
      await this.init();
    }

    return new Promise((resolve, reject) => {
      if (!this.db) {
        reject(new Error('Database not initialized'));
        return;
      }

      const transaction = this.db.transaction([this.storeName], 'readonly');
      const store = transaction.objectStore(this.storeName);
      const index = store.index('synced');
      const request = index.getAll(false); // Get all unsynced records

      request.onsuccess = () => {
        resolve(request.result);
      };

      request.onerror = () => {
        reject(request.error);
      };
    });
  }

  /**
   * Mark data as synced
   */
  async markAsSynced(ids: number[]): Promise<void> {
    if (!this.db) {
      await this.init();
    }

    return new Promise((resolve, reject) => {
      if (!this.db) {
        reject(new Error('Database not initialized'));
        return;
      }

      const transaction = this.db.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);

      let completed = 0;
      const total = ids.length;

      if (total === 0) {
        resolve();
        return;
      }

      ids.forEach((id) => {
        const getRequest = store.get(id);
        getRequest.onsuccess = () => {
          const data = getRequest.result;
          if (data) {
            data.synced = true;
            const updateRequest = store.put(data);
            updateRequest.onsuccess = () => {
              completed++;
              if (completed === total) {
                resolve();
              }
            };
            updateRequest.onerror = () => {
              reject(updateRequest.error);
            };
          } else {
            completed++;
            if (completed === total) {
              resolve();
            }
          }
        };
        getRequest.onerror = () => {
          reject(getRequest.error);
        };
      });
    });
  }

  /**
   * Register background sync
   */
  async registerBackgroundSync(): Promise<void> {
    if (!('serviceWorker' in navigator) || !('sync' in (ServiceWorkerRegistration.prototype as any))) {
      console.warn('Background Sync API not supported');
      return;
    }

    try {
      const registration = await navigator.serviceWorker.ready;
      await (registration as any).sync.register('sync-tracking-data');
      console.log('Background sync registered');
    } catch (error) {
      console.error('Error registering background sync:', error);
    }
  }

  /**
   * Clear old synced data (cleanup)
   */
  async clearOldData(olderThanDays: number = 7): Promise<void> {
    if (!this.db) {
      await this.init();
    }

    return new Promise((resolve, reject) => {
      if (!this.db) {
        reject(new Error('Database not initialized'));
        return;
      }

      const transaction = this.db.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const index = store.index('synced');
      const request = index.openCursor(true); // Get all synced records

      const cutoffTime = Date.now() - (olderThanDays * 24 * 60 * 60 * 1000);
      let deleted = 0;

      request.onsuccess = (event) => {
        const cursor = (event.target as IDBRequest<IDBCursorWithValue>).result;
        if (cursor) {
          const data = cursor.value;
          if (data.synced && data.createdAt < cutoffTime) {
            cursor.delete();
            deleted++;
          }
          cursor.continue();
        } else {
          console.log(`Cleaned up ${deleted} old records`);
          resolve();
        }
      };

      request.onerror = () => {
        reject(request.error);
      };
    });
  }
}

// Export singleton instance
let backgroundSyncInstance: BackgroundSyncService | null = null;

export const getBackgroundSyncService = (): BackgroundSyncService => {
  if (!backgroundSyncInstance) {
    backgroundSyncInstance = new BackgroundSyncService();
  }
  return backgroundSyncInstance;
};















