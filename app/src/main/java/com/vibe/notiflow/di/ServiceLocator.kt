package com.vibe.notiflow.di

import android.content.Context
import androidx.room.Room
import com.vibe.notiflow.data.local.NotiFlowDatabase
import com.vibe.notiflow.data.local.SecureStore
import com.vibe.notiflow.domain.action.ActionRegistry
import com.vibe.notiflow.domain.action.WebhookPostAction
import com.vibe.notiflow.domain.engine.RuleEngine
import com.vibe.notiflow.domain.filter.FilterRegistry
import com.vibe.notiflow.domain.filter.PackageEqualsFilter
import com.vibe.notiflow.domain.filter.TextContainsFilter
import com.vibe.notiflow.domain.filter.TextRegexFilter
import com.vibe.notiflow.domain.filter.TitleContainsFilter
import com.vibe.notiflow.domain.repo.RuleRepository
import com.vibe.notiflow.worker.WorkManagerRetryScheduler

object ServiceLocator {
    private lateinit var appContext: Context

    private val db: NotiFlowDatabase by lazy {
        Room.databaseBuilder(appContext, NotiFlowDatabase::class.java, "notiflow.db")
            .addMigrations(NotiFlowDatabase.MIGRATION_1_2)
            .build()
    }

    val secureStore: SecureStore by lazy { SecureStore(appContext) }
    val ruleRepository: RuleRepository by lazy { RuleRepository(db.ruleDao(), db.logDao()) }

    private val filterRegistry: FilterRegistry by lazy {
        FilterRegistry(listOf(PackageEqualsFilter(), TitleContainsFilter(), TextContainsFilter(), TextRegexFilter()))
    }
    private val actionRegistry: ActionRegistry by lazy { ActionRegistry(listOf(WebhookPostAction(secureStore))) }

    val ruleEngine: RuleEngine by lazy {
        RuleEngine(ruleRepository, filterRegistry, actionRegistry, WorkManagerRetryScheduler(appContext))
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
