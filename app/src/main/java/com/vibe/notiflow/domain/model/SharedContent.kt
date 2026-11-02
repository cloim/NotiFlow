package com.vibe.notiflow.domain.model

import java.net.URI
import java.util.Locale

object SharedContent {
    /** Browsers commonly share a URL as text/plain, sometimes below a title. */
    fun url(event: NotificationEvent): String? {
        if (event.source != "share" || event.attachments.isNotEmpty()) return null
        return event.text?.lineSequence()?.map { it.trim() }?.firstOrNull { line ->
            val uri = runCatching { URI(line) }.getOrNull()
            uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
        }
    }

    fun mimeType(event: NotificationEvent): String? = if (url(event) != null) "text/uri-list" else event.mimeType
}
