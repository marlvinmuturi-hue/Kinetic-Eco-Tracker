/**
 * Google Play subscription verification and lifecycle handling.
 *
 * The client never decides whether it is premium. It sends a purchase token here;
 * this module asks the Play Developer API what that token actually is, and writes
 * the answer to `users/{uid}/entitlements/premium`, which `firestore.rules` makes
 * read-only to clients. EntitlementRepository on the device just mirrors that doc.
 *
 * Two entry points:
 *   - `verifyPlayPurchase` — called by the app right after a purchase, and again on
 *     every launch for any token Play still reports as owned (that second path is
 *     what makes reinstall/restore work without any extra UI).
 *   - `playBillingRtdn` — Pub/Sub push from Play for renewals, cancellations,
 *     expiries, holds and refunds, which arrive when the app is not running.
 *
 * ── Setup this code cannot do for you ────────────────────────────────────────
 *  1. Play Console → Monetize → Subscriptions → create product id `premium`
 *     with an ACTIVE base plan. A draft base plan is invisible to the app.
 *  2. Google Cloud Console → enable "Google Play Android Developer API" on the
 *     project that runs these functions.
 *  3. Play Console → Users and permissions → invite the functions runtime service
 *     account (`<project>@appspot.gserviceaccount.com`) and grant it
 *     "View financial data" + "Manage orders and subscriptions" for this app.
 *     Without this every call here fails with 401/403.
 *  4. Play Console → Monetize → Monetization setup → Real-time developer
 *     notifications → topic `projects/<project>/topics/play-billing-rtdn`
 *     (create the topic first; the deploy of `playBillingRtdn` creates it too).
 */

const functions = require('firebase-functions/v1');
const admin = require('firebase-admin');
const crypto = require('crypto');
const { google } = require('googleapis');
const { sendToUser } = require('./fcm');

/** Must match `applicationId` in app/build.gradle.kts. */
const PACKAGE_NAME = process.env.PLAY_PACKAGE_NAME || 'com.kineticecotracker';

/** Pub/Sub topic configured in Play Console for real-time developer notifications. */
const RTDN_TOPIC = 'play-billing-rtdn';

const ENTITLEMENTS_COLLECTION = 'entitlements';
const ENTITLEMENT_DOC = 'premium';
/** purchaseToken → uid, so an RTDN (which carries no uid) can find its owner. */
const PURCHASE_INDEX = 'playPurchases';

/** Lazy so `admin.initializeApp()` in index.js has definitely run first. */
function db() {
  return admin.firestore();
}

// ── Play Developer API ───────────────────────────────────────────────────────

let androidPublisherClient = null;

/**
 * Android Publisher client authenticated as the functions runtime service account
 * (Application Default Credentials). Built once per instance — token refresh is
 * handled internally by google-auth-library.
 */
function androidPublisher() {
  if (!androidPublisherClient) {
    const auth = new google.auth.GoogleAuth({
      scopes: ['https://www.googleapis.com/auth/androidpublisher']
    });
    androidPublisherClient = google.androidpublisher({ version: 'v3', auth });
  }
  return androidPublisherClient;
}

/** SHA-256 hex of the Firebase uid — what the app sends as obfuscatedAccountId. */
function obfuscatedAccountId(uid) {
  return crypto.createHash('sha256').update(String(uid)).digest('hex');
}

/**
 * Subscription states Play considers as "the user should have access right now".
 * Grace period is included deliberately: their card failed but Play is retrying
 * and they have not lost the subscription yet. Cutting them off mid-retry is the
 * fastest way to turn a payment blip into a cancellation.
 */
const ENTITLING_STATES = new Set([
  'SUBSCRIPTION_STATE_ACTIVE',
  'SUBSCRIPTION_STATE_IN_GRACE_PERIOD'
]);

function parseTimeMs(value) {
  if (!value) return 0;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : 0;
}

/**
 * Flatten a `purchases.subscriptionsv2.get` response into what the client needs.
 *
 * Expiry is the latest of all line items. A subscription normally has one, but an
 * upgrade in progress can briefly carry two, and the user is entitled until the
 * later of them.
 */
function summarizeSubscription(purchaseToken, sub) {
  const lineItems = Array.isArray(sub.lineItems) ? sub.lineItems : [];
  const expiryMs = lineItems.reduce((max, li) => Math.max(max, parseTimeMs(li.expiryTime)), 0);
  const productId = lineItems.map((li) => li.productId).find(Boolean) || null;
  const willRenew = lineItems.some(
    (li) => li.autoRenewingPlan && li.autoRenewingPlan.autoRenewEnabled === true
  );
  const state = sub.subscriptionState || 'SUBSCRIPTION_STATE_UNSPECIFIED';

  return {
    purchaseToken,
    productId,
    expiryMs,
    willRenew,
    state,
    active: ENTITLING_STATES.has(state),
    acknowledged: sub.acknowledgementState === 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED',
    linkedPurchaseToken: sub.linkedPurchaseToken || null,
    isTestPurchase: !!sub.testPurchase,
    obfuscatedAccountId:
      (sub.externalAccountIdentifiers &&
        sub.externalAccountIdentifiers.obfuscatedExternalAccountId) ||
      null
  };
}

/** Fetch subscription state for a token. Returns null when Play doesn't know it. */
async function fetchSubscription(purchaseToken) {
  try {
    const res = await androidPublisher().purchases.subscriptionsv2.get({
      packageName: PACKAGE_NAME,
      token: purchaseToken
    });
    return summarizeSubscription(purchaseToken, res.data || {});
  } catch (err) {
    const code = err && err.code;
    if (code === 404 || code === 410) {
      console.warn(`Play does not recognise purchase token ...${purchaseToken.slice(-12)} (${code})`);
      return null;
    }
    throw err;
  }
}

/**
 * Acknowledge a purchase Play is still waiting on.
 *
 * Play auto-refunds and revokes anything unacknowledged after three days, so this
 * is not optional bookkeeping — skipping it silently gives every subscriber their
 * money back. Done server-side rather than on the client so a user who closes the
 * app the instant the purchase completes still keeps what they paid for.
 */
async function acknowledgeIfNeeded(summary) {
  if (summary.acknowledged || !summary.productId) return;
  try {
    await androidPublisher().purchases.subscriptions.acknowledge({
      packageName: PACKAGE_NAME,
      subscriptionId: summary.productId,
      token: summary.purchaseToken,
      requestBody: {}
    });
    summary.acknowledged = true;
    console.log(`✅ Acknowledged ${summary.productId} ...${summary.purchaseToken.slice(-12)}`);
  } catch (err) {
    // Already-acknowledged races return 400; that's a success for our purposes.
    console.error(`Acknowledge failed for ...${summary.purchaseToken.slice(-12)}`, err.message);
    if (!(err && err.code === 400)) throw err;
  }
}

// ── Firestore writes ─────────────────────────────────────────────────────────

/**
 * Write the entitlement doc the app reads.
 *
 * Guarded against stale tokens: an upgrade or resignup issues a *new* purchase
 * token while the old one keeps reporting its own (earlier) expiry, and a late
 * RTDN for that old token must not shorten an entitlement the new one extended.
 * A revocation passes `force` because it legitimately needs to lower the expiry.
 */
async function applyEntitlement(uid, summary, source, { force = false } = {}) {
  const ref = db()
    .collection('users').doc(uid)
    .collection(ENTITLEMENTS_COLLECTION).doc(ENTITLEMENT_DOC);

  await db().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const current = snap.exists ? snap.data() : null;

    if (!force && current && current.purchaseToken &&
        current.purchaseToken !== summary.purchaseToken &&
        Number(current.expiryMs || 0) > Number(summary.expiryMs || 0)) {
      console.log(`Skipping stale token write for ${uid}: a newer token holds a later expiry`);
      return;
    }

    tx.set(ref, {
      active: summary.active,
      productId: summary.productId,
      expiryMs: summary.expiryMs,
      willRenew: summary.willRenew,
      source,
      state: summary.state,
      purchaseToken: summary.purchaseToken,
      isTestPurchase: !!summary.isTestPurchase,
      updatedAtMs: Date.now()
    }, { merge: true });
  });

  console.log(
    `Entitlement for ${uid}: state=${summary.state} expiry=${new Date(summary.expiryMs).toISOString()} renew=${summary.willRenew}`
  );
}

/** Record purchaseToken → uid so RTDNs, which carry no uid, can be routed. */
async function indexPurchase(uid, summary) {
  const batch = db().batch();
  batch.set(db().collection(PURCHASE_INDEX).doc(summary.purchaseToken), {
    uid,
    productId: summary.productId,
    updatedAtMs: Date.now()
  }, { merge: true });

  // An upgrade/downgrade supersedes the previous token. Point it at the same uid
  // so a trailing notification for it still resolves, and mark it dead.
  if (summary.linkedPurchaseToken) {
    batch.set(db().collection(PURCHASE_INDEX).doc(summary.linkedPurchaseToken), {
      uid,
      supersededBy: summary.purchaseToken,
      updatedAtMs: Date.now()
    }, { merge: true });
  }
  await batch.commit();
}

/** Reverse lookup for notifications, which identify a purchase but not a user. */
async function uidForPurchaseToken(purchaseToken) {
  const snap = await db().collection(PURCHASE_INDEX).doc(purchaseToken).get();
  return snap.exists ? (snap.data().uid || null) : null;
}

// ── verifyPlayPurchase ───────────────────────────────────────────────────────

/**
 * POST { purchaseToken, productId? } with a Firebase ID token as Bearer auth.
 *
 * Called after a purchase completes and on every launch for tokens Play still
 * reports as owned. Idempotent by design — re-posting the same token just
 * re-reads Play and rewrites the same values.
 */
exports.verifyPlayPurchase = functions.https.onRequest(async (req, res) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.set('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    res.status(204).send('');
    return;
  }
  if (req.method !== 'POST') {
    res.status(405).json({ error: 'Method not allowed' });
    return;
  }

  try {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      res.status(401).json({ error: 'Unauthorized: Missing auth token' });
      return;
    }

    let decoded;
    try {
      decoded = await admin.auth().verifyIdToken(authHeader.split('Bearer ')[1]);
    } catch (err) {
      console.error('❌ ID token verification failed', err.message);
      res.status(401).json({ error: 'Unauthorized: Invalid auth token' });
      return;
    }
    const uid = decoded.uid;

    const purchaseToken = (req.body && req.body.purchaseToken || '').trim();
    if (!purchaseToken) {
      res.status(400).json({ error: 'Missing purchaseToken' });
      return;
    }

    // Reject a token already bound to somebody else before touching Play. Without
    // this, one purchase pasted between accounts unlocks all of them.
    const existingOwner = await uidForPurchaseToken(purchaseToken);
    if (existingOwner && existingOwner !== uid) {
      console.warn(`🚫 Token ...${purchaseToken.slice(-12)} belongs to ${existingOwner}, not ${uid}`);
      res.status(403).json({ error: 'This purchase belongs to a different account' });
      return;
    }

    const summary = await fetchSubscription(purchaseToken);
    if (!summary) {
      res.status(404).json({ error: 'Purchase not found' });
      return;
    }

    // The app stamps sha256(uid) on the purchase. When present it must match —
    // it is the only link between a Play purchase and a Firebase account that a
    // caller cannot forge.
    if (summary.obfuscatedAccountId && summary.obfuscatedAccountId !== obfuscatedAccountId(uid)) {
      console.warn(`🚫 obfuscatedAccountId mismatch for ...${purchaseToken.slice(-12)}`);
      res.status(403).json({ error: 'This purchase belongs to a different account' });
      return;
    }

    await acknowledgeIfNeeded(summary);
    await applyEntitlement(uid, summary, 'play');
    await indexPurchase(uid, summary);

    res.status(200).json({
      active: summary.active,
      productId: summary.productId,
      expiryMs: summary.expiryMs,
      willRenew: summary.willRenew,
      state: summary.state
    });
  } catch (err) {
    console.error('❌ verifyPlayPurchase failed', err);
    res.status(500).json({ error: 'Verification failed. Please try again.' });
  }
});

// ── Real-time developer notifications ────────────────────────────────────────

/**
 * Renewals, cancellations, expiries, holds and refunds — everything that changes
 * a subscription while the app is closed.
 *
 * Deliberately re-reads full state from Play rather than trusting the
 * notification's type. Notifications can arrive late, out of order, or twice; the
 * authoritative answer is whatever subscriptionsv2 says at the moment we ask.
 */
exports.playBillingRtdn = functions.pubsub.topic(RTDN_TOPIC).onPublish(async (message) => {
  let payload;
  try {
    payload = message.json || {};
  } catch (err) {
    console.error('❌ RTDN payload was not JSON', err);
    return null;
  }

  if (payload.testNotification) {
    console.log('✅ Received Play test notification — RTDN wiring is live');
    return null;
  }

  if (payload.packageName && payload.packageName !== PACKAGE_NAME) {
    console.warn(`Ignoring RTDN for unexpected package ${payload.packageName}`);
    return null;
  }

  try {
    if (payload.voidedPurchaseNotification) {
      await handleVoidedPurchase(payload.voidedPurchaseNotification);
      return null;
    }
    if (payload.subscriptionNotification) {
      await handleSubscriptionNotification(payload.subscriptionNotification);
      return null;
    }
    console.log('RTDN carried no subscription or voided-purchase payload; ignoring');
    return null;
  } catch (err) {
    // Throwing asks Pub/Sub to redeliver, which is what we want for a transient
    // Play API error. The handler is idempotent, so redelivery is safe.
    console.error('❌ RTDN handling failed; will be retried', err);
    throw err;
  }
});

async function handleSubscriptionNotification(notification) {
  const purchaseToken = notification.purchaseToken;
  if (!purchaseToken) return;

  const uid = await uidForPurchaseToken(purchaseToken);
  if (!uid) {
    // Normal for a purchase whose verify call has not landed yet — the app's own
    // verification will write the entitlement moments later.
    console.warn(`No uid indexed for ...${purchaseToken.slice(-12)} (type ${notification.notificationType})`);
    return;
  }

  const summary = await fetchSubscription(purchaseToken);
  if (!summary) {
    console.warn(`Play no longer knows ...${purchaseToken.slice(-12)}; leaving entitlement as-is`);
    return;
  }

  const previousState = await currentEntitlementState(uid);

  await acknowledgeIfNeeded(summary);
  await applyEntitlement(uid, summary, 'play-rtdn');
  await indexPurchase(uid, summary);

  await notifyBillingProblem(uid, previousState, summary);
}

/** The state we last recorded, so a transition can be told from a repeat delivery. */
async function currentEntitlementState(uid) {
  try {
    const snap = await db()
      .collection('users').doc(uid)
      .collection('entitlements').doc('premium').get();
    return snap.exists ? (snap.data().state || null) : null;
  } catch (e) {
    console.warn(`Could not read prior entitlement state for ${uid}`, e);
    return null;
  }
}

/**
 * Tell the user when their payment fails, and again when it recovers.
 *
 * Without this the failure is completely silent: Play retries for days, we keep them
 * entitled (see ENTITLING_STATES), and then premium simply evaporates. The user
 * experiences that as the app breaking, not as a card that needs updating — which is
 * both a support ticket and an avoidable cancellation.
 *
 * Fires only on a *transition*, not on every RTDN. Pub/Sub delivers at-least-once and
 * Play re-sends the same state, so keying off the state alone would push repeatedly
 * about one failed payment — the definition of a notification people disable.
 */
async function notifyBillingProblem(uid, previousState, summary) {
  const GRACE = 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD';
  const HOLD = 'SUBSCRIPTION_STATE_ON_HOLD';
  const state = summary.state;

  let title = null;
  let body = null;

  if ((state === GRACE || state === HOLD) && previousState !== state) {
    if (state === GRACE) {
      // Still entitled — say so. The point is to prompt a card update, not to alarm
      // someone into thinking they have already lost what they paid for.
      const until = summary.expiryMs
        ? new Date(summary.expiryMs).toLocaleDateString('en-GB', { day: 'numeric', month: 'long' })
        : null;
      title = 'Payment problem';
      body = until
        ? `Google Play could not take your payment. Premium stays on until ${until} — update your payment method to keep it.`
        : 'Google Play could not take your payment. Update your payment method to keep Premium.';
    } else {
      title = 'Premium is paused';
      body = 'Your payment did not go through. Update your payment method in Google Play to switch Premium back on.';
    }
  } else if (
    state === 'SUBSCRIPTION_STATE_ACTIVE' &&
    (previousState === GRACE || previousState === HOLD)
  ) {
    // Closing the loop matters: the user acted on our nudge and deserves to know it
    // worked, rather than wondering whether the fix took.
    title = 'Payment sorted';
    body = 'Thanks — your payment went through and Premium is fully active.';
  }

  if (!title) return;

  try {
    const devices = await sendToUser(db(), uid, { data: { type: 'billing', title, body } });
    console.log(`💳 billing push to ${uid} (${previousState} → ${state}), ${devices} device(s)`);
  } catch (e) {
    // Never let a push failure fail the RTDN — the entitlement write is what matters,
    // and throwing here would ask Pub/Sub to redeliver and re-apply it.
    console.error(`Billing push failed for ${uid}`, e);
  }
}

/**
 * A refund or chargeback. Play has taken the money back, so access ends now —
 * this is the one path allowed to shorten an entitlement.
 */
async function handleVoidedPurchase(notification) {
  const purchaseToken = notification.purchaseToken;
  if (!purchaseToken) return;

  const uid = await uidForPurchaseToken(purchaseToken);
  if (!uid) {
    console.warn(`Voided purchase ...${purchaseToken.slice(-12)} has no indexed uid`);
    return;
  }

  await applyEntitlement(uid, {
    purchaseToken,
    productId: null,
    expiryMs: 0,
    willRenew: false,
    state: 'SUBSCRIPTION_STATE_VOIDED',
    active: false,
    isTestPurchase: false
  }, 'play-voided', { force: true });

  console.log(`↩️  Revoked entitlement for ${uid} after refund/chargeback`);
}