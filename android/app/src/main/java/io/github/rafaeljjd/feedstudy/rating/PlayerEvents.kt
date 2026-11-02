package io.github.rafaeljjd.feedstudy.rating

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

sealed interface PlayerEvent {
    data object Ready : PlayerEvent
    data class StateChange(val state: Int) : PlayerEvent
    data class Time(val currentSeconds: Double, val durationSeconds: Double) : PlayerEvent
    data class Error(val code: Int) : PlayerEvent

    companion object {
        const val STATE_ENDED = 0
        const val STATE_PLAYING = 1

        /** The autoplay block is not a broken video: the participant can still press play. */
        const val ERROR_AUTOPLAY = 3002
    }
}

/** Reads TikTok embed player messages, and ignores anything else sent to the page. */
object PlayerEvents {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(message: String): PlayerEvent? {
        val root = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull()
            ?: return null
        val flag = (root["x-tiktok-player"] as? JsonPrimitive)?.booleanOrNull
        if (flag != true) return null

        val type = (root["type"] as? JsonPrimitive)?.content ?: return null
        val value = root["value"]
        return when (type) {
            "onPlayerReady" -> PlayerEvent.Ready
            "onStateChange" -> (value as? JsonPrimitive)?.intOrNull?.let { PlayerEvent.StateChange(it) }
            "onCurrentTime" -> (value as? JsonObject)?.let { obj ->
                val current = (obj["currentTime"] as? JsonPrimitive)?.doubleOrNull
                val duration = (obj["duration"] as? JsonPrimitive)?.doubleOrNull
                if (current != null) PlayerEvent.Time(current, duration ?: 0.0) else null
            }
            // The deprecated "onError" arrives first with no error code, so it is ignored.
            "onPlayerError" -> {
                val code = (value as? JsonObject)?.get("errorCode") as? JsonPrimitive
                PlayerEvent.Error(code?.intOrNull ?: -1)
            }
            else -> null
        }
    }
}
