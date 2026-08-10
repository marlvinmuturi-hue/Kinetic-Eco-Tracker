/**
 * Shared FCM delivery.
 *
 * Lifted out of index.js so playBilling.js can send too. Requiring index.js from
 * playBilling.js would be a cycle — index.js already requires playBilling.js to
 * re-export its handlers — so the helper lives here and both sides require it.
 */
const admin = require('firebase-admin');

/**
 * Send one message to one token, retrying transient failures and pruning dead ones.
 *
 * @param tokenDoc Firestore doc whose id is the FCM token; deleted if Play/FCM says
 *                 the token is gone, so a reinstalled device stops costing sends.
 */
async function sendFcmWithRetry(tokenDoc, message, userId, maxAttempts = 3) {
  const token = tokenDoc.id;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      // High priority so Android wakes the app to deliver immediately,
      // bypassing Doze/battery-optimization deferral of normal-priority data messages.
      await admin.messaging().send({ token, android: { priority: 'high' }, ...message });
      return;
    } catch (err) {
      if (
        err.code === 'messaging/registration-token-not-registered' ||
        err.code === 'messaging/invalid-registration-token'
      ) {
        console.warn(`Stale FCM token removed for ${userId}/${token}`);
        tokenDoc.ref.delete().catch(() => {});
        return;
      }
      if (attempt < maxAttempts) {
        const delayMs = 500 * attempt;
        console.warn(`FCM send attempt ${attempt} failed for ${userId}/${token}: ${err.message} — retrying in ${delayMs}ms`);
        await new Promise(r => setTimeout(r, delayMs));
      } else {
        console.error(`FCM send failed after ${maxAttempts} attempts for ${userId}/${token}: ${err.message}`);
      }
    }
  }
}

/** Send the same message to every device a user has registered. */
async function sendToUser(db, userId, message) {
  const tokensSnap = await db.collection('users').doc(userId).collection('fcmTokens').get();
  if (tokensSnap.empty) return 0;
  await Promise.allSettled(
    tokensSnap.docs.map((tokenDoc) => sendFcmWithRetry(tokenDoc, message, userId))
  );
  return tokensSnap.size;
}

module.exports = { sendFcmWithRetry, sendToUser };
