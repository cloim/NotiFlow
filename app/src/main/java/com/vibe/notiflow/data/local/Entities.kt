package com.vibe.notiflow.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val targetPackagesJson: String,
    val filtersJson: String,
    val filterOperator: String = "AND",
    val actionsJson: String,
    val createdAt: Long
)

@Entity(tableName = "execution_logs")
data class ExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val matched: Boolean,
    val result: String,
    val message: String,
    val executedAt: Long,
    val eventPackage: String,
    val eventTitle: String?
)
