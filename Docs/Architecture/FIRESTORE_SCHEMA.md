# Firestore Database Schema - Kinetic Eco Tracker

## Overview

This document defines the complete Firestore database schema for the Kinetic Eco Tracker platform. The schema is designed to support activity tracking, GPS data storage, user statistics, and future social features while maintaining data privacy and scalability.

**Last Updated:** December 2025  
**Schema Version:** 1.0  
**Firebase Project:** Kinetic Eco Tracker

---

## Table of Contents

1. [Schema Structure](#schema-structure)
2. [Collections & Documents](#collections--documents)
3. [Field Definitions](#field-definitions)
4. [Indexes](#indexes)
5. [Security Rules](#security-rules)
6. [Query Patterns](#query-patterns)
7. [Data Migration](#data-migration)
8. [Scalability Considerations](#scalability-considerations)

---

## Schema Structure

### Root Path: `users/{uid}`

All user data is organized under the authenticated user's Firebase UID. This ensures data isolation and simplifies security rules.

```
users/{uid}/
  ├── (root document) - User profile data
  ├── sessions/{sessionId} - Tracking sessions
  │   └── gpsPoints/{pointId} - Sampled GPS coordinates
  ├── statistics/{period} - Pre-calculated aggregates
  ├── preferences/ (document) - User settings
  └── feedback/{feedbackId} - User feedback
```

**Design Principles:**
- User data isolation via UID-based paths
- Subcollections for scalable one-to-many relationships
- Pre-calculated aggregations for performance
- Schema versioning for future migrations
- GPS point sampling to manage storage costs

---

## Collections & Documents

### 1. Users Collection: `users/{uid}`

**Document ID:** Firebase Auth UID  
**Type:** Root user document

#### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `email` | string | Yes | User's email address (normalized, lowercase) |
| `displayName` | string | No | Optional display name for social features |
| `createdAt` | timestamp | Yes | Account creation timestamp |
| `lastLogin` | timestamp | Yes | Last successful login timestamp |
| `schemaVersion` | number | Yes | Current schema version (default: 1) |
| `isLegacy` | boolean | No | Flag for users migrated from localStorage |
| `totalSessions` | number | Yes | Total number of completed sessions |
| `totalDistance` | number | Yes | Lifetime distance tracked (meters) |
| `totalDuration` | number | Yes | Lifetime tracking time (seconds) |
| `totalCO2Saved` | number | Yes | Lifetime CO2 emissions avoided (kg) |
| `updatedAt` | timestamp | Yes | Last profile update timestamp |

#### Sample Document

```json
{
  "email": "user@example.com",
  "displayName": "John Doe",
  "createdAt": "2025-12-08T10:00:00.000Z",
  "lastLogin": "2025-12-08T15:30:00.000Z",
  "schemaVersion": 1,
  "isLegacy": false,
  "totalSessions": 42,
  "totalDistance": 125000,
  "totalDuration": 86400,
  "totalCO2Saved": 24.5,
  "updatedAt": "2025-12-08T15:30:00.000Z"
}
```

---

### 2. Sessions Subcollection: `users/{uid}/sessions/{sessionId}`

**Document ID:** Auto-generated UUID or custom session ID  
**Type:** Subcollection

Stores individual tracking sessions with complete statistics.

#### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | Unique session identifier |
| `startTime` | timestamp | Yes | Session start timestamp |
| `endTime` | timestamp | Yes | Session end timestamp |
| `date` | string | Yes | ISO date string (YYYY-MM-DD) for queries |
| `totalDuration` | number | Yes | Total session time (seconds) |
| `totalDistance` | number | Yes | Total distance covered (meters) |
| `caloriesBurned` | number | Yes | Total calories burned (kcal) |
| `co2Emissions` | number | Yes | Total CO2 emissions (kg) |
| `co2Conserved` | number | Yes | Total CO2 conserved (kg) |
| `schemaVersion` | number | Yes | Session schema version |
| `activityBreakdown` | map | Yes | Time and distance per activity type |
| `segments` | array | Yes | Activity segments with details |
| `startLocation` | geopoint | No | Starting GPS coordinates |
| `endLocation` | geopoint | No | Ending GPS coordinates |
| `gpsPointsCount` | number | Yes | Number of stored GPS points |
| `createdAt` | timestamp | Yes | Document creation timestamp |
| `updatedAt` | timestamp | Yes | Last update timestamp |

#### Activity Breakdown Structure

```typescript
activityBreakdown: {
  IDLE: {
    time: number,      // seconds
    distance: number   // meters
  },
  WALKING: {
    time: number,
    distance: number
  },
  DRIVING: {
    time: number,
    distance: number
  },
  FLYING: {
    time: number,
    distance: number
  }
}
```

#### Segments Array Structure

```typescript
segments: [
  {
    type: "IDLE" | "WALKING" | "DRIVING" | "FLYING",
    startTime: timestamp,
    endTime: timestamp,
    distance: number,    // meters
    avgSpeed: number     // m/s
  }
]
```

#### Sample Document

```json
{
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "startTime": "2025-12-08T14:00:00.000Z",
  "endTime": "2025-12-08T15:30:00.000Z",
  "date": "2025-12-08",
  "totalDuration": 5400,
  "totalDistance": 8500,
  "caloriesBurned": 425,
  "co2Emissions": 0.0,
  "co2Conserved": 1.632,
  "schemaVersion": 1,
  "activityBreakdown": {
    "IDLE": { "time": 300, "distance": 0 },
    "WALKING": { "time": 5100, "distance": 8500 },
    "DRIVING": { "time": 0, "distance": 0 },
    "FLYING": { "time": 0, "distance": 0 }
  },
  "segments": [
    {
      "type": "WALKING",
      "startTime": "2025-12-08T14:05:00.000Z",
      "endTime": "2025-12-08T15:30:00.000Z",
      "distance": 8500,
      "avgSpeed": 1.67
    }
  ],
  "startLocation": { "latitude": 37.7749, "longitude": -122.4194 },
  "endLocation": { "latitude": 37.7849, "longitude": -122.4094 },
  "gpsPointsCount": 85,
  "createdAt": "2025-12-08T15:30:05.000Z",
  "updatedAt": "2025-12-08T15:30:05.000Z"
}
```

---

### 3. GPS Points Subcollection: `users/{uid}/sessions/{sessionId}/gpsPoints/{pointId}`

**Document ID:** Sequential number or timestamp-based ID  
**Type:** Nested subcollection

Stores sampled GPS coordinates for session route replay and analysis.

**Sampling Strategy:** Store every 10th point OR when significant change occurs:
- Speed change > 2 m/s
- Direction change > 30 degrees
- Distance from last point > 50 meters

#### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `pointId` | string | Yes | Sequential point identifier |
| `timestamp` | timestamp | Yes | GPS reading timestamp |
| `location` | geopoint | Yes | GPS coordinates (lat, lng) |
| `latitude` | number | Yes | Latitude (for queries) |
| `longitude` | number | Yes | Longitude (for queries) |
| `speed` | number | No | Speed at this point (m/s) |
| `accuracy` | number | Yes | GPS accuracy (meters) |
| `altitude` | number | No | Altitude (meters) |
| `heading` | number | No | Direction of travel (degrees) |
| `activity` | string | Yes | Classified activity at this point |

#### Sample Document

```json
{
  "pointId": "00010",
  "timestamp": "2025-12-08T14:10:00.000Z",
  "location": { "latitude": 37.7759, "longitude": -122.4184 },
  "latitude": 37.7759,
  "longitude": -122.4184,
  "speed": 1.5,
  "accuracy": 10,
  "altitude": 50,
  "heading": 180,
  "activity": "WALKING"
}
```

**Storage Optimization:**
- Maximum 1000 points per session (enforce sampling)
- Consider Cloud Storage for very long sessions
- Archive old GPS data after 90 days

---

### 4. Statistics Subcollection: `users/{uid}/statistics/{period}`

**Document ID:** Period identifier (format varies by type)  
**Type:** Subcollection

Pre-calculated statistics for fast dashboard queries.

#### Period ID Formats

- **Daily:** `daily-YYYY-MM-DD` (e.g., `daily-2025-12-08`)
- **Weekly:** `weekly-YYYY-WW` (e.g., `weekly-2025-49`)
- **Monthly:** `monthly-YYYY-MM` (e.g., `monthly-2025-12`)
- **Yearly:** `yearly-YYYY` (e.g., `yearly-2025`)

#### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `period` | string | Yes | Period identifier |
| `periodType` | string | Yes | "daily", "weekly", "monthly", "yearly" |
| `startDate` | timestamp | Yes | Period start date |
| `endDate` | timestamp | Yes | Period end date |
| `sessionCount` | number | Yes | Number of sessions |
| `totalDuration` | number | Yes | Total tracking time (seconds) |
| `totalDistance` | number | Yes | Total distance (meters) |
| `caloriesBurned` | number | Yes | Total calories (kcal) |
| `co2Emissions` | number | Yes | Total CO2 (kg) |
| `co2Conserved` | number | Yes | Total CO2 conserved (kg) |
| `activityBreakdown` | map | Yes | Stats per activity type |
| `averageSpeed` | number | Yes | Average speed (m/s) |
| `peakSpeed` | number | Yes | Maximum speed (m/s) |
| `topActivity` | string | Yes | Most common activity |
| `updatedAt` | timestamp | Yes | Last calculation timestamp |

#### Sample Document

```json
{
  "period": "daily-2025-12-08",
  "periodType": "daily",
  "startDate": "2025-12-08T00:00:00.000Z",
  "endDate": "2025-12-08T23:59:59.999Z",
  "sessionCount": 3,
  "totalDuration": 12600,
  "totalDistance": 25000,
  "caloriesBurned": 1250,
  "co2Emissions": 0.5,
  "co2Conserved": 3.2,
  "activityBreakdown": {
    "IDLE": { "time": 600, "distance": 0 },
    "WALKING": { "time": 10800, "distance": 18000 },
    "DRIVING": { "time": 1200, "distance": 7000 },
    "FLYING": { "time": 0, "distance": 0 }
  },
  "averageSpeed": 1.98,
  "peakSpeed": 15.5,
  "topActivity": "WALKING",
  "updatedAt": "2025-12-08T23:59:59.999Z"
}
```

**Calculation Strategy:**
- Daily stats: Calculated on session completion
- Weekly/Monthly: Aggregated from daily stats via Cloud Functions
- Yearly: Aggregated from monthly stats

---

### 5. Preferences Document: `users/{uid}/preferences`

**Document ID:** Fixed as `preferences`  
**Type:** Single document

Stores user-specific settings and preferences.

#### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `unitSystem` | string | Yes | "METRIC" or "IMPERIAL" |
| `notifications` | map | Yes | Notification preferences |
| `privacy` | map | Yes | Privacy settings |
| `displayOptions` | map | Yes | UI preferences |
| `gpsSettings` | map | Yes | GPS tracking options |
| `updatedAt` | timestamp | Yes | Last update timestamp |

#### Sample Document

```json
{
  "unitSystem": "METRIC",
  "notifications": {
    "sessionReminders": true,
    "weeklyReports": true,
    "achievements": true,
    "emailUpdates": false
  },
  "privacy": {
    "profileVisible": false,
    "sessionsVisible": false,
    "statisticsVisible": false
  },
  "displayOptions": {
    "theme": "dark",
    "dashboardView": "compact",
    "mapStyle": "default"
  },
  "gpsSettings": {
    "highAccuracy": true,
    "backgroundTracking": true,
    "autoStopTimeout": 300
  },
  "updatedAt": "2025-12-08T10:00:00.000Z"
}
```

---

### 6. Feedback Subcollection: `users/{uid}/feedback/{feedbackId}`

**Document ID:** Auto-generated  
**Type:** Subcollection

Stores user feedback submissions.

#### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `feedbackId` | string | Yes | Unique feedback identifier |
| `type` | string | Yes | "bug", "feature", "improvement", "other" |
| `subject` | string | No | Optional subject line |
| `message` | string | Yes | Feedback content |
| `rating` | number | No | Optional rating (1-5) |
| `deviceInfo` | map | Yes | Device and app information |
| `status` | string | Yes | "submitted", "reviewed", "resolved" |
| `createdAt` | timestamp | Yes | Submission timestamp |
| `updatedAt` | timestamp | Yes | Last update timestamp |

#### Sample Document

```json
{
  "feedbackId": "fb-550e8400-e29b",
  "type": "feature",
  "subject": "Route replay feature",
  "message": "Would love to see a replay of my routes on a map",
  "rating": 5,
  "deviceInfo": {
    "platform": "iOS",
    "version": "16.5",
    "appVersion": "1.3",
    "browser": "Safari"
  },
  "status": "submitted",
  "createdAt": "2025-12-08T16:00:00.000Z",
  "updatedAt": "2025-12-08T16:00:00.000Z"
}
```

---

## Indexes

### Required Composite Indexes

#### 1. Sessions by Date (Descending)
```
Collection: users/{uid}/sessions
Fields:
  - date (Descending)
  - startTime (Descending)
```

#### 2. Sessions by Activity
```
Collection: users/{uid}/sessions
Fields:
  - date (Descending)
  - activityBreakdown.WALKING.time (Descending)
```

#### 3. Statistics by Period
```
Collection: users/{uid}/statistics
Fields:
  - periodType (Ascending)
  - startDate (Descending)
```

#### 4. GPS Points by Timestamp
```
Collection: users/{uid}/sessions/{sessionId}/gpsPoints
Fields:
  - timestamp (Ascending)
```

### Single-Field Indexes

Auto-created by Firestore for:
- `email`
- `createdAt`
- `date`
- `startTime`
- `periodType`

---

## Security Rules

### Firestore Security Rules

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    
    // Helper functions
    function isAuthenticated() {
      return request.auth != null;
    }
    
    function isOwner(uid) {
      return isAuthenticated() && request.auth.uid == uid;
    }
    
    function isValidEmail(email) {
      return email.matches('^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$');
    }
    
    function isValidSchemaVersion() {
      return request.resource.data.schemaVersion is number 
        && request.resource.data.schemaVersion >= 1;
    }
    
    // Users collection
    match /users/{uid} {
      // User can read/write their own profile
      allow read: if isOwner(uid);
      allow create: if isOwner(uid) 
        && isValidEmail(request.resource.data.email)
        && isValidSchemaVersion();
      allow update: if isOwner(uid);
      allow delete: if false; // Prevent accidental deletion
      
      // Sessions subcollection
      match /sessions/{sessionId} {
        allow read: if isOwner(uid);
        allow create: if isOwner(uid) 
          && request.resource.data.sessionId is string
          && request.resource.data.totalDuration >= 0
          && request.resource.data.totalDistance >= 0;
        allow update: if isOwner(uid);
        allow delete: if isOwner(uid);
        
        // GPS Points nested subcollection
        match /gpsPoints/{pointId} {
          allow read: if isOwner(uid);
          allow create: if isOwner(uid)
            && request.resource.data.latitude >= -90
            && request.resource.data.latitude <= 90
            && request.resource.data.longitude >= -180
            && request.resource.data.longitude <= 180;
          allow update: if false; // GPS points are immutable
          allow delete: if isOwner(uid);
        }
      }
      
      // Statistics subcollection
      match /statistics/{period} {
        allow read: if isOwner(uid);
        allow write: if isOwner(uid); // Server-side writes via Cloud Functions
      }
      
      // Preferences document
      match /preferences {
        allow read: if isOwner(uid);
        allow write: if isOwner(uid);
      }
      
      // Feedback subcollection
      match /feedback/{feedbackId} {
        allow read: if isOwner(uid);
        allow create: if isOwner(uid)
          && request.resource.data.message.size() > 0;
        allow update: if isOwner(uid);
        allow delete: if isOwner(uid);
      }
    }
    
    // Deny all other access
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

### Security Best Practices

1. **Authentication Required:** All operations require Firebase Authentication
2. **User Isolation:** Users can only access data under their own UID
3. **Validation:** Field-level validation in security rules
4. **Immutability:** GPS points cannot be modified after creation
5. **Soft Deletes:** Consider implementing soft deletes for users
6. **Rate Limiting:** Use App Check and Firebase Security Rules for rate limiting

---

## Query Patterns

### Common Query Examples

#### 1. Get User's Recent Sessions (Last 30 days)

```javascript
const thirtyDaysAgo = new Date();
thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);

const sessionsRef = db.collection('users').doc(uid).collection('sessions');
const query = sessionsRef
  .where('startTime', '>=', thirtyDaysAgo)
  .orderBy('startTime', 'desc')
  .limit(50);
```

#### 2. Get Daily Statistics for Current Month

```javascript
const startOfMonth = new Date();
startOfMonth.setDate(1);
startOfMonth.setHours(0, 0, 0, 0);

const statsRef = db.collection('users').doc(uid).collection('statistics');
const query = statsRef
  .where('periodType', '==', 'daily')
  .where('startDate', '>=', startOfMonth)
  .orderBy('startDate', 'desc');
```

#### 3. Get GPS Route for Session

```javascript
const gpsPointsRef = db
  .collection('users').doc(uid)
  .collection('sessions').doc(sessionId)
  .collection('gpsPoints');
  
const query = gpsPointsRef.orderBy('timestamp', 'asc');
```

#### 4. Calculate Total Distance This Week

```javascript
const weekStart = new Date();
weekStart.setDate(weekStart.getDate() - weekStart.getDay());
weekStart.setHours(0, 0, 0, 0);

const sessionsRef = db.collection('users').doc(uid).collection('sessions');
const snapshot = await sessionsRef
  .where('startTime', '>=', weekStart)
  .get();

const totalDistance = snapshot.docs.reduce((sum, doc) => {
  return sum + doc.data().totalDistance;
}, 0);
```

#### 5. Get User Preferences

```javascript
const prefsRef = db.collection('users').doc(uid).collection('preferences').doc('preferences');
const snapshot = await prefsRef.get();
const preferences = snapshot.data();
```

### Query Optimization Tips

1. **Use Indexes:** Create composite indexes for complex queries
2. **Limit Results:** Always use `.limit()` for large collections
3. **Pagination:** Use cursor-based pagination for better UX
4. **Cache Locally:** Use Firestore's offline persistence
5. **Batch Reads:** Combine related queries when possible

---

## Data Migration

### Migrating from localStorage to Firestore

#### Phase 1: User Profile Migration

```typescript
async function migrateUserProfile(localProfile: UserProfile, uid: string) {
  const userRef = db.collection('users').doc(uid);
  
  await userRef.set({
    email: localProfile.email,
    createdAt: localProfile.createdAt,
    lastLogin: new Date(),
    schemaVersion: 1,
    isLegacy: true,
    totalSessions: localProfile.sessions.length,
    totalDistance: calculateTotalDistance(localProfile.sessions),
    totalDuration: calculateTotalDuration(localProfile.sessions),
    totalCO2Saved: calculateTotalCO2(localProfile.sessions),
    updatedAt: new Date()
  });
}
```

#### Phase 2: Sessions Migration

```typescript
async function migrateSessions(sessions: StoredSession[], uid: string) {
  const batch = db.batch();
  const sessionsRef = db.collection('users').doc(uid).collection('sessions');
  
  for (const session of sessions) {
    const sessionRef = sessionsRef.doc(session.id);
    batch.set(sessionRef, {
      sessionId: session.id,
      startTime: new Date(session.date),
      endTime: new Date(session.date),
      date: session.date,
      totalDuration: session.stats.totalDuration,
      totalDistance: session.stats.totalDistance,
      caloriesBurned: session.stats.caloriesBurned,
      co2Emissions: session.stats.co2Emissions,
      co2Conserved: session.stats.co2Conserved || 0,
      schemaVersion: session.schemaVersion || 1,
      activityBreakdown: session.stats.breakdown,
      segments: session.stats.segments,
      gpsPointsCount: 0, // No GPS data in localStorage
      createdAt: new Date(session.date),
      updatedAt: new Date()
    });
  }
  
  await batch.commit();
}
```

#### Phase 3: Initialize Preferences

```typescript
async function initializePreferences(uid: string) {
  const prefsRef = db.collection('users').doc(uid).collection('preferences').doc('preferences');
  
  await prefsRef.set({
    unitSystem: 'METRIC',
    notifications: {
      sessionReminders: true,
      weeklyReports: true,
      achievements: true,
      emailUpdates: false
    },
    privacy: {
      profileVisible: false,
      sessionsVisible: false,
      statisticsVisible: false
    },
    displayOptions: {
      theme: 'dark',
      dashboardView: 'compact',
      mapStyle: 'default'
    },
    gpsSettings: {
      highAccuracy: true,
      backgroundTracking: true,
      autoStopTimeout: 300
    },
    updatedAt: new Date()
  });
}
```

#### Migration Strategy

1. **Gradual Migration:** Migrate users on first login after Firestore integration
2. **Dual Write:** Write to both localStorage and Firestore during transition
3. **Validation:** Verify migrated data matches localStorage
4. **Cleanup:** Remove localStorage data after successful migration
5. **Rollback:** Keep localStorage backup for 30 days

---

## Scalability Considerations

### Document Size Limits

- **Maximum Document Size:** 1 MB
- **Sessions:** Limit segments array to 1000 items
- **GPS Points:** Store as subcollection (max 1000 points per session)
- **Statistics:** Consider document splitting for high-activity users

### GPS Point Sampling Strategy

#### Intelligent Sampling Algorithm

```typescript
function shouldStorGPSPoint(
  currentPoint: GeoPosition,
  lastStoredPoint: GeoPosition,
  pointsSinceLastStore: number
): boolean {
  // Always store every 10th point
  if (pointsSinceLastStore >= 10) return true;
  
  // Store if significant speed change
  const speedDelta = Math.abs(currentPoint.speed - lastStoredPoint.speed);
  if (speedDelta > 2) return true; // 2 m/s change
  
  // Store if significant distance
  const distance = calculateDistance(
    currentPoint.latitude, currentPoint.longitude,
    lastStoredPoint.latitude, lastStoredPoint.longitude
  );
  if (distance > 50) return true; // 50 meters
  
  // Store if activity change
  const currentActivity = classifyActivity(currentPoint.speed);
  const lastActivity = classifyActivity(lastStoredPoint.speed);
  if (currentActivity !== lastActivity) return true;
  
  return false;
}
```

### Statistics Rollup Strategy

#### Cloud Functions for Aggregation

```typescript
// Triggered when session is created
exports.updateDailyStats = functions.firestore
  .document('users/{uid}/sessions/{sessionId}')
  .onCreate(async (snap, context) => {
    const session = snap.data();
    const { uid } = context.params;
    
    const date = session.date;
    const statsRef = db
      .collection('users').doc(uid)
      .collection('statistics').doc(`daily-${date}`);
    
    await db.runTransaction(async (transaction) => {
      const statsDoc = await transaction.get(statsRef);
      
      if (!statsDoc.exists) {
        // Create new daily stats
        transaction.set(statsRef, {
          period: `daily-${date}`,
          periodType: 'daily',
          startDate: new Date(date),
          endDate: new Date(date),
          sessionCount: 1,
          totalDuration: session.totalDuration,
          totalDistance: session.totalDistance,
          // ... other fields
        });
      } else {
        // Update existing stats
        const currentStats = statsDoc.data();
        transaction.update(statsRef, {
          sessionCount: currentStats.sessionCount + 1,
          totalDuration: currentStats.totalDuration + session.totalDuration,
          totalDistance: currentStats.totalDistance + session.totalDistance,
          // ... other fields
          updatedAt: new Date()
        });
      }
    });
  });
```

### Cost Optimization

1. **Read Optimization:**
   - Use pre-calculated statistics instead of aggregating sessions
   - Implement client-side caching with offline persistence
   - Use pagination for large result sets

2. **Write Optimization:**
   - Batch writes when possible
   - Use Cloud Functions for server-side aggregations
   - Implement GPS point sampling

3. **Storage Optimization:**
   - Archive old GPS points to Cloud Storage after 90 days
   - Compress activity segments
   - Use document references instead of duplicating data

### Sharding Strategy

For high-traffic users (future consideration):

```
users/{uid}/
  ├── sessions-shard-0/{sessionId}
  ├── sessions-shard-1/{sessionId}
  ├── sessions-shard-2/{sessionId}
  └── ...
```

### Future Social Features Support

#### Potential Schema Extensions

```
// Followers subcollection
users/{uid}/followers/{followerId}
  - followerId: string
  - followedAt: timestamp
  - status: "active" | "blocked"

// Following subcollection
users/{uid}/following/{followingId}
  - followingId: string
  - followedAt: timestamp

// Shared sessions
sharedSessions/{sharedId}
  - sessionId: string
  - ownerId: string
  - sharedWith: array<string>
  - permissions: "view" | "comment"
  - shareUrl: string
  - expiresAt: timestamp

// Public leaderboards
leaderboards/{period}/{category}
  - rankings: array<{uid, score, username}>
  - updatedAt: timestamp
```

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-12-08 | Initial schema design |

---

## References

- [Firestore Data Model](https://firebase.google.com/docs/firestore/data-model)
- [Firestore Security Rules](https://firebase.google.com/docs/firestore/security/get-started)
- [Cloud Functions for Firebase](https://firebase.google.com/docs/functions)
- [Firestore Best Practices](https://firebase.google.com/docs/firestore/best-practices)

---

## Contact

For schema questions or modifications, contact the development team.












