const functions = require('firebase-functions');
const admin = require('firebase-admin');
const { GoogleGenerativeAI } = require('@google/generative-ai');

// Initialize Firebase Admin
admin.initializeApp();
const db = admin.firestore();

/** Firestore session.timestamp ms (number or Timestamp). */
function getTimestampMs(data) {
  const t = data.timestamp;
  if (t == null) return 0;
  if (typeof t.toMillis === 'function') return t.toMillis();
  return Number(t) || 0;
}

/** Rolling window: "7days", "30days", "14days", etc. Returns day count 1–366. */
function parseRollingDaysFromTimeframe(timeframe) {
  if (timeframe === '1day') return 1;
  const s = String(timeframe || '').trim();
  const m = /^(\d+)days$/.exec(s);
  if (m) {
    const n = parseInt(m[1], 10);
    return Math.min(366, Math.max(1, n));
  }
  const legacy = { '7days': 7, '30days': 30, '90days': 90 };
  return legacy[s] || 7;
}

/** Calendar day yyyy-MM-dd; prefers sessionDateKey field (matches Android). */
function sessionDateKeyFromDoc(data) {
  if (data.sessionDateKey && typeof data.sessionDateKey === 'string') {
    const s = data.sessionDateKey.trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s;
  }
  const ts = getTimestampMs(data);
  if (!ts) return null;
  return new Date(ts).toISOString().slice(0, 10);
}

// Initialize Gemini AI
// API key will be set via: firebase functions:config:set gemini.key="YOUR_KEY"
const genAI = new GoogleGenerativeAI(functions.config().gemini?.key || 'YOUR_API_KEY_HERE');

// Global throttle: max Gemini analyses per minute per instance (reduces Billing API / quota pressure)
const ANALYSES_PER_MINUTE = 8;
const analysisTimestamps = [];
function throttleAnalysis() {
  const now = Date.now();
  const oneMinuteAgo = now - 60000;
  while (analysisTimestamps.length && analysisTimestamps[0] < oneMinuteAgo) analysisTimestamps.shift();
  if (analysisTimestamps.length >= ANALYSES_PER_MINUTE) {
    const retryAfter = Math.ceil((analysisTimestamps[0] + 60000 - now) / 1000);
    return retryAfter;
  }
  analysisTimestamps.push(now);
  return 0;
}

/**
 * Analyze user's activity data using Gemini AI
 * HTTP endpoint with manual authentication
 */
exports.analyzeActivity = functions.https.onRequest(async (req, res) => {
  // Enable CORS
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
    // Step 1: Get and verify Authorization token
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      console.error('❌ Missing or invalid Authorization header');
      res.status(401).json({ error: 'Unauthorized: Missing auth token' });
      return;
    }

    const idToken = authHeader.split('Bearer ')[1];
    console.log('🔐 Verifying ID token...');
    
    // Verify the ID token using Firebase Admin SDK
    let decodedToken;
    try {
      decodedToken = await admin.auth().verifyIdToken(idToken);
      console.log(`✅ Token verified for user: ${decodedToken.uid}`);
    } catch (error) {
      console.error('❌ Token verification failed:', error.message);
      res.status(401).json({ error: 'Unauthorized: Invalid token' });
      return;
    }

    const userId = decodedToken.uid;
    // Support both HTTP (body.timeframe) and Callable (body.data.timeframe) payloads
    let timeframe = (req.body.data && req.body.data.timeframe) || req.body.timeframe || '7days';
    const locale = (req.body.data && req.body.data.locale) || req.body.locale || 'en';
    const sessionDateKey = ((req.body.data && req.body.data.sessionDateKey) || req.body.sessionDateKey || '').trim();
    // If a valid calendar day is sent, single-day analysis must win (ignore mistaken rolling timeframe from client).
    if (/^\d{4}-\d{2}-\d{2}$/.test(sessionDateKey)) {
      timeframe = '1day';
    }
    const effectiveCacheTimeframe =
      timeframe === '1day' && sessionDateKey ? `1day:${sessionDateKey}` : timeframe;

    console.log(
      `Analyzing activity for user: ${userId}, timeframe: ${timeframe}, sessionDateKey: ${sessionDateKey || 'n/a'}, locale: ${locale}`
    );

    if (timeframe === '1day' && (!sessionDateKey || !/^\d{4}-\d{2}-\d{2}$/.test(sessionDateKey))) {
      res.status(400).json({ error: 'Single-day analysis requires sessionDateKey (yyyy-MM-dd)' });
      return;
    }

    // Step 2: Check rate limiting (5 analyses per hour per user)
    const ANALYSES_PER_HOUR = 5;
    const rateLimitDoc = await db.collection('analysisRateLimit').doc(userId).get();
    const hourAgo = Date.now() - (60 * 60 * 1000);
    let timestamps = [];
    if (rateLimitDoc.exists) {
      const data = rateLimitDoc.data();
      timestamps = (data.timestamps || (data.timestamp ? [data.timestamp] : [])).filter(t => t > hourAgo);
    }
    if (timestamps.length >= ANALYSES_PER_HOUR) {
      const oldestInWindow = Math.min(...timestamps);
      const minutesLeft = Math.ceil((oldestInWindow + 3600000 - Date.now()) / 60000);
      res.status(429).json({ 
        error: `Limit reached (5 per hour). Please wait ${minutesLeft} more minutes before requesting another analysis` 
      });
      return;
    }

    // Step 3: Check cache (24-hour TTL, keyed by timeframe + locale)
    const cacheDoc = await db.collection('analysisCache').doc(userId).get();
    if (cacheDoc.exists) {
      const cached = cacheDoc.data();
      const dayAgo = Date.now() - (24 * 60 * 60 * 1000);
      
      if (cached.timestamp > dayAgo && cached.timeframe === effectiveCacheTimeframe && cached.locale === locale) {
        console.log('Returning cached analysis');
        res.status(200).json({
          ...cached.analysis,
          cached: true,
          cacheAge: Math.floor((Date.now() - cached.timestamp) / 1000 / 60) // minutes
        });
        return;
      }
    }

    // Step 3b: Global throttle (avoid Billing API / Gemini quota 429)
    const retryAfterSec = throttleAnalysis();
    if (retryAfterSec > 0) {
      console.warn('Global analysis throttle: too many requests this minute');
      res.set('Retry-After', String(retryAfterSec));
      res.status(429).json({
        error: 'Too many analysis requests. Please wait ' + retryAfterSec + ' seconds and try again.'
      });
      return;
    }

    // Step 4: Fetch user sessions from Firestore
    const sessions = await fetchUserSessions(userId, timeframe, sessionDateKey);
    
    if (!sessions || sessions.length === 0) {
      res.status(404).json({ 
        error: 'No activity data found for analysis. Please track some activities first!' 
      });
      return;
    }

    // Step 5: Calculate aggregate stats
    const stats = calculateAggregateStats(sessions);
    console.log('Stats calculated:', JSON.stringify(stats, null, 2));

    // Step 6: Build prompt for Gemini (locale: en/fr/de/es/zh)
    const prompt = buildAnalysisPrompt(stats, timeframe, locale, sessionDateKey);

    // Step 7: Call Gemini API
    console.log('Calling Gemini API...');
    const model = genAI.getGenerativeModel({ model: 'gemini-2.0-flash' });
    const result = await model.generateContent(prompt);
    const responseText = result.response.text();
    console.log('Gemini response received:', responseText);
    
    // Step 8: Parse AI response (extract JSON from markdown if needed)
    let analysis;
    try {
      // Try to extract JSON from markdown code blocks
      const jsonMatch = responseText.match(/```json\n([\s\S]*?)\n```/);
      if (jsonMatch) {
        analysis = JSON.parse(jsonMatch[1]);
      } else {
        // Try direct parse
        analysis = JSON.parse(responseText);
      }
    } catch (parseError) {
      console.error('Failed to parse AI response:', responseText);
      console.error('Parse error:', parseError);
      res.status(500).json({ 
        error: 'Failed to parse AI response. Please try again.' 
      });
      return;
    }

    // Step 9: Update rate limit (append timestamp, keep last 5 in window)
    timestamps.push(Date.now());
    timestamps = timestamps.filter(t => t > hourAgo).slice(-ANALYSES_PER_HOUR);
    await db.collection('analysisRateLimit').doc(userId).set({
      timestamps: timestamps
    });

    // Step 10: Cache the result (keyed by timeframe + locale)
    await db.collection('analysisCache').doc(userId).set({
      analysis: analysis,
      timestamp: Date.now(),
      timeframe: effectiveCacheTimeframe,
      locale: locale
    });

    console.log('✅ Analysis complete successfully');
    res.status(200).json({
      ...analysis,
      cached: false
    });

  } catch (error) {
    console.error('❌ Error in analyzeActivity:', error);
    const msg = error.message || '';
    const is429 = msg.includes('429') || msg.includes('Quota exceeded') || msg.includes('resource-exhausted') || msg.includes('cloudbilling');
    if (is429) {
      res.set('Retry-After', '60');
      res.status(429).json({
        error: 'Analysis is rate-limited. Please wait a minute and try again.'
      });
    } else {
      res.status(500).json({
        error: 'An error occurred during analysis: ' + msg.substring(0, 200)
      });
    }
  }
});

/**
 * Fetch user sessions from Firestore
 */
async function fetchUserSessions(userId, timeframe, sessionDateKey) {
  if (timeframe === '1day' && sessionDateKey) {
    try {
      let snapshot = await db
        .collection('users')
        .doc(userId)
        .collection('sessions')
        .where('sessionDateKey', '==', sessionDateKey)
        .get();

      let list = snapshot.docs.map((doc) => ({ id: doc.id, ...doc.data() }));

      if (list.length === 0) {
        const wide = await db
          .collection('users')
          .doc(userId)
          .collection('sessions')
          .orderBy('timestamp', 'desc')
          .limit(500)
          .get();
        list = wide.docs
          .map((doc) => ({ id: doc.id, ...doc.data() }))
          .filter((s) => sessionDateKeyFromDoc(s) === sessionDateKey);
      }

      console.log(`Found ${list.length} sessions for single day ${sessionDateKey}`);
      return list;
    } catch (error) {
      console.error('Error fetching single-day sessions:', error);
      return [];
    }
  }

  const days = parseRollingDaysFromTimeframe(timeframe);
  const cutoffDate = new Date();
  cutoffDate.setDate(cutoffDate.getDate() - days);

  try {
    const snapshot = await db
      .collection('users')
      .doc(userId)
      .collection('sessions')
      .where('timestamp', '>=', cutoffDate.getTime())
      .orderBy('timestamp', 'desc')
      .get();

    if (snapshot.empty) {
      console.log('No sessions found for user');
      return [];
    }

    console.log(`Found ${snapshot.size} sessions`);
    return snapshot.docs.map((doc) => ({
      id: doc.id,
      ...doc.data()
    }));
  } catch (error) {
    console.error('Error fetching sessions:', error);
    return [];
  }
}

/**
 * Calculate aggregate statistics from sessions
 */
function calculateAggregateStats(sessions) {
  const stats = {
    totalSessions: sessions.length,
    totalDistance: 0,
    totalDuration: 0,
    totalCalories: 0,
    co2Emissions: 0,
    co2Conserved: 0,
    activityBreakdown: {
      IDLE: { count: 0, distance: 0, duration: 0 },
      WALKING: { count: 0, distance: 0, duration: 0 },
      RUNNING: { count: 0, distance: 0, duration: 0 },
      CYCLING: { count: 0, distance: 0, duration: 0 },
      DRIVING: { count: 0, distance: 0, duration: 0 },
      ELECTRIC_VEHICLE: { count: 0, distance: 0, duration: 0 },
      FLYING: { count: 0, distance: 0, duration: 0 }
    },
    activeDays: new Set(),
    avgDistance: 0,
    avgDuration: 0
  };

  sessions.forEach(session => {
    // Add to date set
    const date = new Date(session.timestamp).toDateString();
    stats.activeDays.add(date);

    // Aggregate totals
    stats.totalDistance += session.totalDistance || 0;
    stats.totalDuration += session.totalDuration || 0;
    stats.totalCalories += session.caloriesBurned || 0;
    stats.co2Emissions += session.co2Emissions || 0;
    stats.co2Conserved += session.co2Conserved || 0;

    // Activity breakdown
    if (session.breakdown) {
      Object.keys(session.breakdown).forEach(activity => {
        if (stats.activityBreakdown[activity]) {
          stats.activityBreakdown[activity].count++;
          stats.activityBreakdown[activity].distance += session.breakdown[activity].distance || 0;
          stats.activityBreakdown[activity].duration += session.breakdown[activity].time || 0;
        }
      });
    }
  });

  stats.activeDaysCount = stats.activeDays.size;
  stats.avgDistance = stats.totalDistance / stats.totalSessions;
  stats.avgDuration = stats.totalDuration / stats.totalSessions;

  return stats;
}

/**
 * Map locale code to language name for the prompt
 */
function getLanguageForLocale(locale) {
  const map = { en: 'English', fr: 'French', de: 'German', es: 'Spanish', zh: 'Chinese' };
  return map[locale] || 'English';
}

/**
 * Build analysis prompt for Gemini
 */
function buildAnalysisPrompt(stats, timeframe, locale, sessionDateKey) {
  const days = parseRollingDaysFromTimeframe(timeframe);
  const language = getLanguageForLocale(locale);
  const periodLabel =
    timeframe === '1day' && sessionDateKey
      ? `Single calendar day (${sessionDateKey}, session start dates as stored in the app)`
      : `Past ${days} days`;

  return `You are a fitness and health analyst. Analyze this user's activity data and provide insights.

**IMPORTANT**: Respond entirely in ${language}. All text in scoreReasoning, insights, recommendations, motivation, environmentalImpact, and highlights must be written in ${language}.

**Time Period**: ${periodLabel}

**Activity Summary**:
- Total Sessions: ${stats.totalSessions}
- Active Days: ${stats.activeDaysCount} out of ${timeframe === '1day' ? 1 : days} day(s)
- Total Distance: ${(stats.totalDistance / 1000).toFixed(2)} km
- Total Duration: ${(stats.totalDuration / 3600).toFixed(1)} hours
- Calories Burned: ${Math.round(stats.totalCalories)} kcal
- CO2 Emissions: ${stats.co2Emissions.toFixed(2)} kg
- CO2 Conserved: ${stats.co2Conserved.toFixed(2)} kg

**Activity Breakdown**:
${Object.entries(stats.activityBreakdown)
  .filter(([_, data]) => data.count > 0)
  .map(([activity, data]) => 
    `- ${activity}: ${data.count} sessions, ${(data.distance / 1000).toFixed(2)} km, ${(data.duration / 60).toFixed(0)} minutes`
  )
  .join('\n')}

**Average Per Session**:
- Distance: ${(stats.avgDistance / 1000).toFixed(2)} km
- Duration: ${(stats.avgDuration / 60).toFixed(0)} minutes

Please provide a comprehensive analysis in the following JSON format:

{
  "score": 8.5,
  "scoreReasoning": "Brief explanation of the score",
  "insights": [
    "First key insight with specific numbers",
    "Second key insight with specific numbers",
    "Third key insight with specific numbers"
  ],
  "recommendations": [
    "First actionable recommendation",
    "Second actionable recommendation"
  ],
  "motivation": "One encouraging message celebrating their achievements",
  "environmentalImpact": "Brief summary of their CO2 impact",
  "highlights": {
    "bestDay": "Day with most activity",
    "topActivity": "Most frequent activity type",
    "improvement": "Area showing most improvement or potential"
  }
}

Guidelines:
- Be specific with numbers and percentages
- Be encouraging and positive
- Provide actionable recommendations
- Consider activity consistency, variety, and intensity
- Score out of 10 (0-3: needs improvement, 4-6: fair, 7-8: good, 9-10: excellent)
- Keep insights brief but meaningful (1-2 sentences each)
- If CO2 conserved is significant, celebrate it!
- If user is mostly sedentary, gently encourage more activity

Return ONLY the JSON object, no additional text or markdown formatting.`;
}

function breakdownDistance(data, activity) {
  const b = data.breakdown;
  if (!b || !b[activity]) return 0;
  return Number(b[activity].distance) || 0;
}

function computeCombinedScore(totalDistance, co2Conserved, totalSessions) {
  return totalDistance + co2Conserved * 100 + totalSessions * 500;
}

async function getDisplayProfile(userId) {
  let displayName = 'User';
  let photoUrl = '';
  try {
    const u = await db.collection('users').doc(userId).get();
    const data = u.data() || {};
    if (data.displayName) displayName = String(data.displayName);
    if (data.photoUrl) photoUrl = String(data.photoUrl);
    const authUser = await admin.auth().getUser(userId).catch(() => null);
    if (authUser) {
      if (!data.displayName) {
        displayName = authUser.displayName || authUser.email?.split('@')[0] || 'User';
      }
      if (!data.photoUrl && authUser.photoURL) photoUrl = authUser.photoURL;
    }
  } catch (e) {
    console.warn('getDisplayProfile', e.message);
  }
  return { displayName, photoUrl };
}

async function rebuildUserDailyAggregate(userId, dateKey) {
  if (!dateKey || !/^\d{4}-\d{2}-\d{2}$/.test(dateKey)) return;

  const userDoc = await db.collection('users').doc(userId).get();
  const optedIn = !!(userDoc.data() || {}).leaderboardOptIn;
  const dayRef = db.collection('leaderboardDaily').doc(dateKey).collection('users').doc(userId);

  if (!optedIn) {
    await dayRef.delete().catch(() => {});
    return;
  }

  const sessionsSnap = await db.collection('users').doc(userId).collection('sessions').get();
  let totalDistance = 0;
  let totalSessions = 0;
  let co2Conserved = 0;
  let co2Emissions = 0;
  let topSpeedMps = 0;
  let distanceWalking = 0;
  let distanceRunning = 0;
  let distanceCycling = 0;

  sessionsSnap.docs.forEach((doc) => {
    const data = doc.data();
    const dk = sessionDateKeyFromDoc(data);
    if (dk !== dateKey) return;
    totalDistance += Number(data.totalDistance) || 0;
    totalSessions += 1;
    co2Conserved += Number(data.co2Conserved) || 0;
    co2Emissions += Number(data.co2Emissions) || 0;
    topSpeedMps = Math.max(topSpeedMps, Number(data.topSpeedMps) || 0);
    distanceWalking += breakdownDistance(data, 'WALKING');
    distanceRunning += breakdownDistance(data, 'RUNNING');
    distanceCycling += breakdownDistance(data, 'CYCLING');
  });

  if (totalSessions === 0) {
    await dayRef.delete().catch(() => {});
    return;
  }

  const score = computeCombinedScore(totalDistance, co2Conserved, totalSessions);
  const { displayName, photoUrl } = await getDisplayProfile(userId);

  await dayRef.set({
    userId,
    displayName,
    photoUrl: photoUrl || '',
    totalDistance,
    totalSessions,
    co2Conserved,
    co2Emissions,
    score,
    topSpeedMps,
    distanceWalking,
    distanceRunning,
    distanceCycling,
    lastUpdated: admin.firestore.FieldValue.serverTimestamp()
  });
}

exports.onSessionWriteDailyLeaderboard = functions.firestore
  .document('users/{userId}/sessions/{sessionId}')
  .onWrite(async (change, context) => {
    const userId = context.params.userId;
    const before = change.before.exists ? change.before.data() : null;
    const after = change.after.exists ? change.after.data() : null;
    const keys = new Set();
    if (before) {
      const k = sessionDateKeyFromDoc(before);
      if (k) keys.add(k);
    }
    if (after) {
      const k = sessionDateKeyFromDoc(after);
      if (k) keys.add(k);
    }
    for (const dateKey of keys) {
      await rebuildUserDailyAggregate(userId, dateKey);
    }
    return null;
  });

const CATEGORY_SORT_FIELD = {
  COMBINED: 'score',
  DISTANCE: 'totalDistance',
  TOP_SPEED: 'topSpeedMps',
  WALKING: 'distanceWalking',
  RUNNING: 'distanceRunning',
  CYCLING: 'distanceCycling'
};

exports.dailyLeaderboardTop = functions.https.onRequest(async (req, res) => {
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
      res.status(401).json({ error: 'Unauthorized' });
      return;
    }
    await admin.auth().verifyIdToken(authHeader.split('Bearer ')[1]);

    const dateKey = ((req.body.data && req.body.data.dateKey) || req.body.dateKey || '').trim();
    const category = ((req.body.data && req.body.data.category) || req.body.category || 'COMBINED')
      .trim()
      .toUpperCase();

    if (!dateKey || !/^\d{4}-\d{2}-\d{2}$/.test(dateKey)) {
      res.status(400).json({ error: 'Invalid or missing dateKey (yyyy-MM-dd)' });
      return;
    }

    const sortField = CATEGORY_SORT_FIELD[category] || CATEGORY_SORT_FIELD.COMBINED;
    const ref = db.collection('leaderboardDaily').doc(dateKey).collection('users');
    const snap = await ref.orderBy(sortField, 'desc').limit(1).get();

    if (snap.empty) {
      res.status(200).json({ dateKey, category, entry: null });
      return;
    }

    const doc = snap.docs[0];
    const d = doc.data();
    res.status(200).json({
      dateKey,
      category,
      entry: {
        userId: d.userId || doc.id,
        displayName: d.displayName || null,
        photoUrl: d.photoUrl || null,
        totalDistance: Number(d.totalDistance) || 0,
        totalSessions: Number(d.totalSessions) || 0,
        co2Conserved: Number(d.co2Conserved) || 0,
        co2Emissions: Number(d.co2Emissions) || 0,
        score: Number(d.score) || 0,
        topSpeedMps: Number(d.topSpeedMps) || 0,
        distanceWalking: Number(d.distanceWalking) || 0,
        distanceRunning: Number(d.distanceRunning) || 0,
        distanceCycling: Number(d.distanceCycling) || 0,
        rank: 1
      }
    });
  } catch (e) {
    console.error('dailyLeaderboardTop', e);
    res.status(500).json({ error: 'Failed to load daily leaderboard' });
  }
});

/**
 * Health check endpoint
 */
exports.healthCheck = functions.https.onRequest((req, res) => {
  res.json({ 
    status: 'ok', 
    timestamp: Date.now(),
    version: '1.0.0',
    message: 'Kinetic AI Analysis API is running'
  });
});
