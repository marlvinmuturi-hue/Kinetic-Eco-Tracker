#!/usr/bin/env node
/**
 * Targeted cleanup for corrupted session docs in users/{uid}/sessions.
 *
 * Two problems collided on this account, both landing on 2026-07-11:
 *   - ~325 WEB-created junk docs (ids `session-*` / 20-char auto-id, empty routePath),
 *     written by services/firestoreSessionService.ts (web "sync to cloud").
 *   - ~192 REAL Android trips whose date got corrupted to 2026-07-11 (both `timestamp`
 *     and `sessionDateKey` overwritten, no `segments`) — true dates unrecoverable.
 * Together they inflate the dashboard's weekly CO2 (~48 kg on one day vs a real ~2.8 kg
 * week). This script deletes everything attributed to a given corrupted day.
 *
 * SAFE MATCHING — a doc is "on <date>" iff:
 *   - it has `sessionDateKey`  → match only when sessionDateKey === <date>   (Android's own date string), OR
 *   - it has NO `sessionDateKey` → match when UTC(timestamp) === <date>       (web junk lacks the field).
 * A healthy Android session on any other day is never matched (its sessionDateKey differs),
 * and old sessions have non-today timestamps, so nothing outside <date> is touched.
 *
 * USAGE (run from the functions/ dir — it has firebase-admin installed):
 *   # Auth: a service-account key (Firebase console → Project settings → Service accounts → Generate key)
 *   #   PowerShell:  $env:GOOGLE_APPLICATION_CREDENTIALS="C:\path\to\serviceAccountKey.json"
 *
 *   # Inspect (read-only):
 *   node cleanup-web-sessions.cjs --inspect --uid gUbd1yWA55aASVjERYWd4sVECGF3
 *
 *   # DRY RUN — report what would be deleted for a day, delete nothing:
 *   node cleanup-web-sessions.cjs --uid gUbd1yWA55aASVjERYWd4sVECGF3 --date 2026-07-11
 *
 *   # DELETE that day (after the dry run looks right):
 *   node cleanup-web-sessions.cjs --uid gUbd1yWA55aASVjERYWd4sVECGF3 --date 2026-07-11 --confirm
 */

const admin = require('firebase-admin');

function arg(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? (process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[i + 1] : true) : undefined;
}
const uid = arg('--uid');
const date = arg('--date'); // e.g. 2026-07-11
const confirm = !!arg('--confirm');
const inspect = !!arg('--inspect');
const keyPath = arg('--key'); // optional path to a service-account JSON

if (!uid) { console.error('Provide --uid <userId>.'); process.exit(1); }
if (!inspect && !date) { console.error('Provide --date YYYY-MM-DD (or --inspect). Add --confirm to actually delete.'); process.exit(1); }
if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) { console.error('--date must be YYYY-MM-DD.'); process.exit(1); }

// Explicit project id so we never hit "Unable to detect a Project Id", and accept a service-account key
// directly via --key so you don't have to set GOOGLE_APPLICATION_CREDENTIALS in each shell.
const PROJECT_ID = process.env.GOOGLE_CLOUD_PROJECT || process.env.GCLOUD_PROJECT || 'gen-lang-client-0114974661';
if (keyPath) {
  const sa = require(require('path').resolve(keyPath));
  admin.initializeApp({ credential: admin.credential.cert(sa), projectId: sa.project_id || PROJECT_ID });
} else {
  admin.initializeApp({ credential: admin.credential.applicationDefault(), projectId: PROJECT_ID });
}
const db = admin.firestore();
const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Does this doc belong to <dateStr>? Prefer the app's own sessionDateKey; fall back to UTC(timestamp). */
function onDate(x, dateStr) {
  if (x.sessionDateKey) return x.sessionDateKey === dateStr;
  const ts = Number(x.timestamp) || 0;
  return ts > 0 && new Date(ts).toISOString().slice(0, 10) === dateStr;
}

async function deleteDay(userId, dateStr) {
  const col = db.collection('users').doc(userId).collection('sessions');
  const snap = await col.get();
  const match = [];
  let co2 = 0, realAndroid = 0, webJunk = 0;
  snap.forEach((doc) => {
    const x = doc.data();
    if (!onDate(x, dateStr)) return;
    match.push(doc.ref);
    co2 += Number(x.co2Conserved || 0);
    if (uuidRe.test(doc.id)) realAndroid++; else webJunk++;
  });
  console.log(`\nuser ${userId}: ${snap.size} total sessions`);
  console.log(`  matched date=${dateStr}: ${match.length}  (uuid/android=${realAndroid}, web-junk=${webJunk})  co2Conserved=${co2.toFixed(2)}kg`);
  console.log(`  will remain: ${snap.size - match.length} sessions`);

  if (!confirm) { console.log('  DRY RUN — nothing deleted. Re-run with --confirm to delete.'); return match.length; }

  const writer = db.bulkWriter();
  match.forEach((ref) => writer.delete(ref));
  await writer.close();
  console.log(`  ✅ deleted ${match.length} sessions dated ${dateStr}`);
  return match.length;
}

/** Read-only forensic dump: id formats, routePath state, and whether true dates are recoverable. */
async function inspectUser(userId) {
  const snap = await db.collection('users').doc(userId).collection('sessions').get();
  const fmt = { uuid: 0, sessionPrefix: 0, syncedPrefix: 0, autoId20: 0, other: 0 };
  const route = { nonEmpty: 0, emptyArr: 0, missing: 0 };
  const recent = [];
  const wk = Date.now() - 7 * 864e5;
  snap.forEach((doc) => {
    const id = doc.id, x = doc.data();
    if (uuidRe.test(id)) fmt.uuid++;
    else if (id.startsWith('session-')) fmt.sessionPrefix++;
    else if (id.startsWith('synced-')) fmt.syncedPrefix++;
    else if (id.length === 20) fmt.autoId20++;
    else fmt.other++;
    if (!Object.prototype.hasOwnProperty.call(x, 'routePath')) route.missing++;
    else if (Array.isArray(x.routePath) && x.routePath.length === 0) route.emptyArr++;
    else route.nonEmpty++;
    const ts = Number(x.timestamp) || 0;
    if (ts >= wk && recent.length < 15) {
      const segs = Array.isArray(x.segments) ? x.segments.map((s) => Number(s.startTime) || 0).filter(Boolean) : [];
      recent.push({
        id: id.slice(0, 22), ts: new Date(ts).toISOString().slice(0, 10),
        key: x.sessionDateKey || '(none)',
        seg: segs.length ? new Date(Math.min(...segs)).toISOString().slice(0, 10) : '(no segs)',
        rp: Array.isArray(x.routePath) ? x.routePath.length : 'missing',
      });
    }
  });
  console.log(`\nuser ${userId}: ${snap.size} sessions`);
  console.log('  id formats:', fmt);
  console.log('  routePath :', route, ' (nonEmpty=real android; empty/missing=suspect)');
  console.log('  --- sessions with timestamp in last 7d: id | ts-date | sessionDateKey | minSegDate | routePts ---');
  recent.forEach((s) => console.log(`   ${s.id.padEnd(24)} ts=${s.ts}  key=${String(s.key).padEnd(12)} seg=${s.seg}  rp=${s.rp}`));
}

(async () => {
  try {
    if (inspect) { await inspectUser(uid); process.exit(0); }
    await deleteDay(uid, date);
    process.exit(0);
  } catch (e) {
    console.error('Failed:', e);
    process.exit(1);
  }
})();