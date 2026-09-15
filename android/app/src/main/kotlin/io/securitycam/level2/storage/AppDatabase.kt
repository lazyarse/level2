package io.securitycam.level2.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [EventEntity::class, OutboxEntity::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v3 -> v4: adds the nullable trigger-detail column. */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN detail TEXT")
            }
        }

        /** v4 -> v5: adds the offline-delivery outbox queue. */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`channelId` TEXT, " +
                        "`eventId` INTEGER, " +
                        "`triggerType` TEXT, " +
                        "`eventTime` INTEGER, " +
                        "`text` TEXT, " +
                        "`snapshotName` TEXT, " +
                        "`mediaPath` TEXT, " +
                        "`remotePath` TEXT, " +
                        "`attempts` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastAttemptAt` INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_createdAt` ON `outbox` (`createdAt`)")
            }
        }

        /** v5 -> v6: adds the video-preview GIF reference on notify rows. */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN `previewGifName` TEXT")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "events.db",
            ).addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // Safety net only: every version this codebase ever shipped
                // (3→4→5→6) migrates explicitly above. This fires solely for a
                // foreign/corrupt db file, where starting fresh beats a
                // startup crash (event media on disk is unaffected).
                .fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
