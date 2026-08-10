package Kinetic_Eco.Tracker.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SessionEntity::class, FuelEntryEntity::class], version = 10, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun fuelEntryDao(): FuelEntryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v7 → v8: add the `synced` flag used by the Firestore backfill. NOT NULL DEFAULT 0 so every
         * existing local session is treated as un-synced and re-uploaded once (idempotent — the upload
         * reuses the session id as the Firestore doc id, so it overwrites rather than duplicates).
         */
        /** Internal, not private: `SessionMigrationTest` replays these against a real fixture. */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8 → v9: denormalise each session's first/last GPS fix into four columns.
         *
         * Nullable with no default: existing rows are left NULL and backfilled in
         * bounded batches by [SessionRepository.backfillRouteEndpoints], one session's
         * geometry at a time. Filling them during migration would mean parsing every
         * stored route in a single transaction — precisely the all-routes-in-memory
         * read that already OOMs this app on large histories.
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN startLat REAL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN startLng REAL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN endLat REAL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN endLng REAL")
            }
        }

        /**
         * v9 → v10: the fuel log.
         *
         * A new table only — no existing column is touched, so the migration cannot
         * lose session history. Indexed on (userId, filledAtMs) because every read is
         * "this user's fill-ups in time order".
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS fuel_entries (
                        id TEXT NOT NULL PRIMARY KEY,
                        userId TEXT NOT NULL,
                        filledAtMs INTEGER NOT NULL,
                        litres REAL NOT NULL,
                        amountPaid REAL NOT NULL,
                        currencyCode TEXT NOT NULL,
                        isFullTank INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL,
                        synced INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_fuel_entries_userId_filledAtMs " +
                        "ON fuel_entries (userId, filledAtMs)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kinetic_eco_database"
                )
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}



