/**
 * Firestore security-rules unit tests.
 *
 * Run against the Firestore emulator:
 *   npm run test:rules
 * (which wraps `firebase emulators:exec --only firestore "node --test test/"`)
 *
 * The headline test — "owner can write an app-shaped leaderboard entry" —
 * reproduces the bug that made every client leaderboard write fail: the rules
 * validated bare fields (co2Saved / totalDistanceKm / totalSessions) that
 * LeaderboardService never writes. It writes SUFFIXED fields instead
 * (score7d, co2ConservedAllTime, totalDistanceAllTime[metres], totalSessionsAllTime, …).
 * If the rules ever drift back to validating non-existent fields, this test fails.
 */
import { readFileSync } from 'node:fs';
import { test, before, after, beforeEach } from 'node:test';
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc } from 'firebase/firestore';

const PROJECT_ID = 'kinetic-rules-test';
const ALICE = 'alice';
const BOB = 'bob';

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync('firestore.rules', 'utf8') },
  });
});

after(async () => {
  await testEnv?.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

const db = (uid) => testEnv.authenticatedContext(uid).firestore();
const anon = () => testEnv.unauthenticatedContext().firestore();

/** Seed a doc with rules bypassed (for read/permission tests). */
const seed = (path, data) =>
  testEnv.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), ...path.split('/')), data);
  });

/** A leaderboard entry shaped exactly like LeaderboardService.writeLeaderboardEntry. */
const entry = (uid, overrides = {}) => ({
  userId: uid,
  displayName: 'Test User',
  photoUrl: '',
  lastUpdated: Date.now(),
  score7d: 1.0,
  co2Conserved7d: 0.5,
  totalDistance7d: 100.0,
  totalSessions7d: 1,
  scoreAllTime: 2.0,
  co2ConservedAllTime: 1.2,
  totalDistanceAllTime: 500.0, // metres
  totalSessionsAllTime: 3,
  ...overrides,
});

// ── Leaderboard ────────────────────────────────────────────────────────────

test('REGRESSION: owner may write an app-shaped leaderboard entry (suffixed fields)', async () => {
  await assertSucceeds(setDoc(doc(db(ALICE), 'leaderboard', ALICE), entry(ALICE)));
});

test('a user cannot write another user\'s leaderboard entry', async () => {
  await assertFails(setDoc(doc(db(BOB), 'leaderboard', ALICE), entry(ALICE)));
});

test('leaderboard write with mismatched userId field is denied', async () => {
  await assertFails(setDoc(doc(db(ALICE), 'leaderboard', ALICE), entry(ALICE, { userId: BOB })));
});

test('out-of-range totalSessionsAllTime is denied (anti-spoofing)', async () => {
  await assertFails(
    setDoc(doc(db(ALICE), 'leaderboard', ALICE), entry(ALICE, { totalSessionsAllTime: 9_999_999 })),
  );
});

test('out-of-range totalDistanceAllTime is denied', async () => {
  await assertFails(
    setDoc(doc(db(ALICE), 'leaderboard', ALICE), entry(ALICE, { totalDistanceAllTime: 1e12 })),
  );
});

test('any authenticated user may read the leaderboard', async () => {
  await seed(`leaderboard/${ALICE}`, entry(ALICE));
  await assertSucceeds(getDoc(doc(db(BOB), 'leaderboard', ALICE)));
});

test('unauthenticated users cannot read the leaderboard', async () => {
  await seed(`leaderboard/${ALICE}`, entry(ALICE));
  await assertFails(getDoc(doc(anon(), 'leaderboard', ALICE)));
});

// ── Reactions ──────────────────────────────────────────────────────────────

test('a user may write their own reaction on another user\'s entry', async () => {
  await assertSucceeds(
    setDoc(doc(db(BOB), 'leaderboard', ALICE, 'reactions', BOB), { emoji: 'clap' }),
  );
});

test('a user cannot write a reaction under someone else\'s id', async () => {
  await assertFails(
    setDoc(doc(db(BOB), 'leaderboard', ALICE, 'reactions', 'carol'), { emoji: 'clap' }),
  );
});

// ── User docs & sessions ─────────────────────────────────────────────────────

test('owner may write their own user doc; others may not', async () => {
  await assertSucceeds(setDoc(doc(db(ALICE), 'users', ALICE), { leaderboardOptIn: true }));
  await assertFails(setDoc(doc(db(BOB), 'users', ALICE), { leaderboardOptIn: true }));
});

test('owner may write their own session; others may not', async () => {
  await assertSucceeds(setDoc(doc(db(ALICE), 'users', ALICE, 'sessions', 's1'), { totalDistance: 10 }));
  await assertFails(setDoc(doc(db(BOB), 'users', ALICE, 'sessions', 's1'), { totalDistance: 10 }));
});

test('owner may write a per-session sample subcollection; others may not', async () => {
  await assertSucceeds(
    setDoc(doc(db(ALICE), 'users', ALICE, 'sessions', 's1', 'samples', 'b0'), { samples: [] }),
  );
  await assertFails(
    setDoc(doc(db(BOB), 'users', ALICE, 'sessions', 's1', 'samples', 'b0'), { samples: [] }),
  );
});

// ── Feedback ─────────────────────────────────────────────────────────────────

test('any authenticated user may create feedback but cannot read it back', async () => {
  await assertSucceeds(setDoc(doc(db(ALICE), 'feedback', 'f1'), { text: 'hi' }));
  await seed('feedback/f2', { text: 'x' });
  await assertFails(getDoc(doc(db(ALICE), 'feedback', 'f2')));
});

// ── Premium entitlements & Play purchase index ───────────────────────────────
// The paywall is only as strong as these two rules: a client that can write its
// own entitlement, or claim someone else's purchase token, has no paywall at all.

test('owner may read their entitlement but nobody may write one', async () => {
  await seed(`users/${ALICE}/entitlements/premium`, { active: true, expiryMs: 4102444800000 });
  await assertSucceeds(getDoc(doc(db(ALICE), 'users', ALICE, 'entitlements', 'premium')));
  await assertFails(getDoc(doc(db(BOB), 'users', ALICE, 'entitlements', 'premium')));
  await assertFails(
    setDoc(doc(db(ALICE), 'users', ALICE, 'entitlements', 'premium'), { active: true }),
  );
});

test('the Play purchase index is invisible and unwritable to clients', async () => {
  await seed('playPurchases/token-123', { uid: ALICE });
  await assertFails(getDoc(doc(db(ALICE), 'playPurchases', 'token-123')));
  await assertFails(setDoc(doc(db(BOB), 'playPurchases', 'token-123'), { uid: BOB }));
});

// ── Fuel log ─────────────────────────────────────────────────────────────────
// Hand-typed fill-ups that cannot be re-recorded if lost, so they sync for
// durability. Strictly owner-only: they reveal where and when someone buys fuel.

test('owner may read and write their own fuel entries; nobody else can', async () => {
  await assertSucceeds(
    setDoc(doc(db(ALICE), 'users', ALICE, 'fuelEntries', 'f1'), { litres: 40, amountPaid: 8000 }),
  );
  await assertSucceeds(getDoc(doc(db(ALICE), 'users', ALICE, 'fuelEntries', 'f1')));
  await assertFails(getDoc(doc(db(BOB), 'users', ALICE, 'fuelEntries', 'f1')));
  await assertFails(
    setDoc(doc(db(BOB), 'users', ALICE, 'fuelEntries', 'f2'), { litres: 1, amountPaid: 1 }),
  );
});
