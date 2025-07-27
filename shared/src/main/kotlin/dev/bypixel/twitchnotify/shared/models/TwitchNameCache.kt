package dev.bypixel.twitchnotify.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class TwitchNameCache(
    val id: String,
    val name: String
)