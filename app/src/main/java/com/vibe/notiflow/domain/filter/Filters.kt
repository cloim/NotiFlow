package com.vibe.notiflow.domain.filter

import com.vibe.notiflow.domain.model.NotificationEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

interface EventFilter {
    val type: String
    fun matches(event: NotificationEvent, config: JsonObject): Boolean
}

class FilterRegistry(filters: List<EventFilter>) {
    private val map = filters.associateBy { it.type }
    fun get(type: String) = map[type]
}

class PackageEqualsFilter : EventFilter {
    override val type = "package.equals"
    override fun matches(event: NotificationEvent, config: JsonObject) =
        event.packageName == (config["value"]?.jsonPrimitive?.contentOrNull ?: "")
}

class TitleContainsFilter : EventFilter {
    override val type = "title.contains"
    override fun matches(event: NotificationEvent, config: JsonObject): Boolean {
        val q = config["value"]?.jsonPrimitive?.contentOrNull ?: return false
        return event.title?.contains(q, ignoreCase = true) == true
    }
}

class TextContainsFilter : EventFilter {
    override val type = "text.contains"
    override fun matches(event: NotificationEvent, config: JsonObject): Boolean {
        val q = config["value"]?.jsonPrimitive?.contentOrNull ?: return false
        return event.text?.contains(q, ignoreCase = true) == true
    }
}

class TextRegexFilter : EventFilter {
    override val type = "text.regex"
    override fun matches(event: NotificationEvent, config: JsonObject): Boolean {
        val pattern = config["value"]?.jsonPrimitive?.contentOrNull ?: return false
        return runCatching { Regex(pattern).containsMatchIn(event.text.orEmpty()) }.getOrDefault(false)
    }
}
