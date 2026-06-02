package com.vibe.notiflow.notification

object SourceNotificationPolicy {
    fun shouldProcessForRules(
        sourcePackageName: String,
        ownPackageName: String,
        isOngoing: Boolean,
        isGroupSummary: Boolean
    ): Boolean {
        if (sourcePackageName == ownPackageName) return false
        if (isOngoing) return false
        if (isGroupSummary) return false
        return true
    }
}
