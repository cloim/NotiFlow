package com.vibe.notiflow.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY priority DESC, id DESC")
    fun observeRules(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY priority DESC, id DESC")
    suspend fun getAllRules(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RuleEntity?

    @Query("SELECT * FROM rules WHERE enabled = 1")
    suspend fun getEnabledRules(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<RuleEntity>): List<Long>

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun updateEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM execution_logs ORDER BY executedAt DESC LIMIT :limit")
    fun observeLogs(limit: Int = 200): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs ORDER BY executedAt DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 200): List<ExecutionLogEntity>

    @Insert
    suspend fun insert(entity: ExecutionLogEntity)
}

@Dao
interface ReceivedNotificationDao {
    @Query("SELECT * FROM received_notifications ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    suspend fun getRecentReceivedNotifications(limit: Int = 200): List<ReceivedNotificationEntity>

    @Insert
    suspend fun insert(entity: ReceivedNotificationEntity)
}
