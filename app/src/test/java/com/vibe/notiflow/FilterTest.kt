package com.vibe.notiflow

import com.vibe.notiflow.domain.filter.PackageEqualsFilter
import com.vibe.notiflow.domain.filter.TextRegexFilter
import com.vibe.notiflow.domain.filter.TitleContainsFilter
import com.vibe.notiflow.domain.model.NotificationEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTest {
    private val event = NotificationEvent("com.test", "Alert Title", "otp 1234", postedAt = 1)

    @Test
    fun packageEqualsWorks() {
        val f = PackageEqualsFilter()
        assertTrue(f.matches(event, buildJsonObject { put("value", "com.test") }))
        assertFalse(f.matches(event, buildJsonObject { put("value", "com.x") }))
    }

    @Test
    fun titleAndRegexWork() {
        assertTrue(TitleContainsFilter().matches(event, buildJsonObject { put("value", "alert") }))
        assertTrue(TextRegexFilter().matches(event, buildJsonObject { put("value", "otp\\s+\\d+") }))
    }
}
