package dev.bypixel.twitchnotify.shared.models

import kotlinx.serialization.Serializable

@Serializable
data class TwitchNotifyEntry(
    val guildId: String,
    val channelId: String,
    val twitchChannelId: String,
    val mentionRole: String? = null,
    val deleteMsgWhenStreamEnded: Boolean = true,
    val notifyId: String
)