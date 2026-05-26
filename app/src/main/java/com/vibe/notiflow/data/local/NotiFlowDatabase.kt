package com.vibe.notiflow.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RuleEntity::class, ExecutionLogEntity::class, ReceivedNotificationEntity::class],
    version = 3,
    exportSchema = false
)
abstract class NotiFlowDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun logDao(): LogDao
    abstract fun receivedNotificationDao(): ReceivedNotificationDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE rules ADD COLUMN filterOperator TEXT NOT NULL DEFAULT 'AND'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `received_notifications` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT,
                        `body` TEXT,
                        `sender` TEXT,
                        `dataJson` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
