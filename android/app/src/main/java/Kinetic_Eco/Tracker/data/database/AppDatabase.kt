package Kinetic_Eco.Tracker.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SessionEntity::class], version = 8, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v7 → v8: add the `synced` flag used by the Firestore backfill. NOT NULL DEFAULT 0 so every
         * existing local session is treated as un-synced and re-uploaded once (idempotent — the upload
         * reuses the session id as the Firestore doc id, so it overwrites rather than duplicates).
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kinetic_eco_database"
                )
                .addMigrations(MIGRATION_7_8)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}



