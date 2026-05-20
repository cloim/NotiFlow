package com.vibe.notiflow.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RuleEntity::class, ExecutionLogEntity::class], version = 2, exportSchema = false)
abstract class NotiFlowDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun logDao(): LogDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE rules ADD COLUMN filterOperator TEXT NOT NULL DEFAULT 'AND'")
            }
        }
    }
}
