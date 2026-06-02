package com.vibe.notiflow

import com.vibe.notiflow.notification.SourceNotificationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceNotificationPolicyTest {
    @Test
    fun externalClearableNotificationsAreProcessedForRules() {
        assertTrue(
            SourceNotificationPolicy.shouldProcessForRules(
                sourcePackageName = "com.sender",
                ownPackageName = "com.cloimism.notiflow",
                isOngoing = false,
                isGroupSummary = false
            )
        )
    }

    @Test
    fun ownNotificationsAreNotProcessedForRules() {
        assertFalse(
            SourceNotificationPolicy.shouldProcessForRules(
                sourcePackageName = "com.cloimism.notiflow",
                ownPackageName = "com.cloimism.notiflow",
                isOngoing = false,
                isGroupSummary = false
            )
        )
    }

    @Test
    fun ongoingNotificationsAreNotProcessedForRules() {
        assertFalse(
            SourceNotificationPolicy.shouldProcessForRules(
                sourcePackageName = "com.sender",
                ownPackageName = "com.cloimism.notiflow",
                isOngoing = true,
                isGroupSummary = false
            )
        )
    }

    @Test
    fun groupSummaryNotificationsAreNotProcessedForRules() {
        assertFalse(
            SourceNotificationPolicy.shouldProcessForRules(
                sourcePackageName = "com.sender",
                ownPackageName = "com.cloimism.notiflow",
                isOngoing = false,
                isGroupSummary = true
            )
        )
    }
}
