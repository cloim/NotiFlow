package com.vibe.notiflow.domain.retry

import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.NotificationEvent

interface RetryScheduler {
    fun scheduleWebhookRetry(ruleId: Long, action: ActionSpec, event: NotificationEvent)
}
