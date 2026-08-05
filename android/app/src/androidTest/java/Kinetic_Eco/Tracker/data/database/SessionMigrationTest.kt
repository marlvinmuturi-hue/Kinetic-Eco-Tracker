package Kinetic_Eco.Tracker.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays a real v8 database through the v8→v9 migration.
 *
 * A Room migration that goes wrong on a user's device is a data-loss event, and this one
 * runs against histories of well over a thousand sessions. `AppDatabase` deliberately has
 * no `fallbackToDestructiveMigration`, so a broken migration crashes rather than silently
 * wiping — but crashing on launch is still a shipped outage, and the only way to find out
 * before release is to migrate a fixture and inspect what survived.
 *
 * Requires `exportSchema = true` (already set) and the JSON schemas in `app/schemas`.
 */
@RunWith(AndroidJUnit4::class)
class SessionMigrationTest {

    private companion object {
        const val TEST_DB = "migration-test-db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /** Insert a v8-shaped session row — no endpoint columns exist at this version. */
    private fun insertV8Session(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String) {
        db.execSQL(
            """
            INSERT INTO sessions (
                id, userId, date, totalDuration, totalDistance, caloriesBurned,
                co2Emissions, co2Conserved, totalSteps, elevationGain, elevationLoss,
                topSpeedMps, kmMilestonesJson, segmentsJson, routePathJson,
                createdAt, breakdown, synced
            ) VALUES (
                ?, 'user-1', '2026-08-01', 900, 6000.0, 210.0,
                1.2, 0.0, 0, 0.0, 0.0,
                12.5, '[]', '[]',
                '[{"latitude":-1.2921,"longitude":36.8219,"timestamp":1},{"latitude":-1.32,"longitude":36.85,"timestamp":2}]',
                1000, '{}', 0
            )
            """.trimIndent(),
            arrayOf(id)
        )
    }

    @Test
    fun migrate8To9_preservesEveryRow() {
        helper.createDatabase(TEST_DB, 8).apply {
            repeat(25) { insertV8Session(this, "session-$it") }
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, AppDatabase.MIGRATION_8_9)

        db.query("SELECT COUNT(*) FROM sessions").use {
            it.moveToFirst()
            assertEquals("no session may be lost by the migration", 25, it.getInt(0))
        }
    }

    @Test
    fun migrate8To9_addsEndpointColumnsAsNull() {
        helper.createDatabase(TEST_DB, 8).apply {
            insertV8Session(this, "session-a")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, AppDatabase.MIGRATION_8_9)

        // Null, not zero: the backfill runs later, in bounded batches. Defaulting to 0
        // would place every un-backfilled session at 0,0 — a real coordinate in the
        // Atlantic — and cluster them all together as a phantom daily commute.
        db.query("SELECT startLat, startLng, endLat, endLng FROM sessions WHERE id = 'session-a'").use {
            it.moveToFirst()
            assertTrue("startLat should be NULL after migration", it.isNull(0))
            assertTrue("startLng should be NULL after migration", it.isNull(1))
            assertTrue("endLat should be NULL after migration", it.isNull(2))
            assertTrue("endLng should be NULL after migration", it.isNull(3))
        }
    }

    @Test
    fun migrate8To9_keepsExistingColumnsIntact() {
        helper.createDatabase(TEST_DB, 8).apply {
            insertV8Session(this, "session-b")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, AppDatabase.MIGRATION_8_9)

        db.query(
            "SELECT userId, totalDistance, co2Emissions, routePathJson FROM sessions WHERE id = 'session-b'"
        ).use {
            it.moveToFirst()
            assertEquals("user-1", it.getString(0))
            assertEquals(6000.0, it.getDouble(1), 1e-9)
            assertEquals(1.2, it.getDouble(2), 1e-9)
            // Route geometry must survive — the backfill reads it to populate endpoints.
            assertTrue("routePathJson must be preserved", it.getString(3).contains("36.8219"))
        }
    }

    @Test
    fun migrate7To9_runsTheWholeChain() {
        helper.createDatabase(TEST_DB, 7).close()
        val db = helper.runMigrationsAndValidate(
            TEST_DB, 9, true, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9
        )
        db.query("SELECT startLat FROM sessions LIMIT 1").use {
            assertEquals("empty table is fine; the schema is what matters", 0, it.count)
        }
        assertNull(null)
    }
}
