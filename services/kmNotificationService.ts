/**
 * Kilometer milestone notification service for web app.
 * Shows a notification when the user completes each kilometer during tracking.
 */

const KM_NOTIFICATIONS_KEY = 'kinetic_km_notifications';

export function getKmNotificationsEnabled(): boolean {
  try {
    return localStorage.getItem(KM_NOTIFICATIONS_KEY) === 'true';
  } catch {
    return false;
  }
}

export function setKmNotificationsEnabled(enabled: boolean): void {
  try {
    localStorage.setItem(KM_NOTIFICATIONS_KEY, String(enabled));
  } catch {
    // ignore storage errors
  }
}

/**
 * Request notification permission from the browser.
 * Must be called in response to user gesture (e.g. click).
 */
export async function requestNotificationPermission(): Promise<NotificationPermission> {
  if (!('Notification' in window)) return 'denied';
  if (Notification.permission === 'granted') return 'granted';
  if (Notification.permission === 'denied') return 'denied';
  return await Notification.requestPermission();
}

/**
 * Show a notification for a completed kilometer.
 */
export function showKmNotification(km: number, secondsForKm: number): void {
  if (!('Notification' in window)) return;
  if (Notification.permission !== 'granted') return;
  if (!getKmNotificationsEnabled()) return;

  const minutes = Math.floor(secondsForKm / 60);
  const secs = Math.floor(secondsForKm % 60);
  const timeStr = `${minutes}:${secs.toString().padStart(2, '0')}`;
  const title = `${km} km completed`;
  const body = `This kilometer in ${timeStr}`;

  try {
    new Notification(title, {
      body,
      icon: '/icon-192x192.png',
      tag: `km-${km}`,
      requireInteraction: false,
    });
  } catch {
    // Ignore notification errors
  }
}
