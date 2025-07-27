package dev.bypixel.twitchnotify.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class LiveNotifyEntry(
    val messageId: String,
    val guildId: String,
    val channelId: String,
    val twitchChannelId: String,
    val streamId: String,
    val startTime: Long,
    val linkedNotifyId: String
)