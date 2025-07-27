package dev.bypixel.twitchnotify.bot.service

import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.ReplaceOptions
import dev.bypixel.twitchnotify.bot.TwitchNotifyBot
import dev.bypixel.twitchnotify.bot.module.notify.Template
import dev.bypixel.twitchnotify.bot.util.TwitchUtil
import dev.bypixel.twitchnotify.shared.models.LiveNotifyEntry
import dev.bypixel.twitchnotify.shared.models.TwitchNotifyEntry
import dev.minn.jda.ktx.coroutines.await
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import java.time.Instant
import kotlin.time.Duration

object MessageUpdateService {
    private val job = CoroutineScope(Dispatchers.IO).launch {
        while (isActive) {
            updateMessages()
            delay(Duration.parse("5m"))
        }
    }

    private suspend fun updateMessages() {
        val currentStreamMessages = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
            .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
            .find()
            .toList()

        for (entry in currentStreamMessages) {
            val channel = TwitchNotifyBot.shardManager.getTextChannelById(entry.channelId)
            if (channel != null) {
                val message = channel.retrieveMessageById(entry.messageId).await()
                if (message != null && message.embeds.isNotEmpty()) {
                    val userId = entry.twitchChannelId
                    val user = TwitchUtil.getUserById(userId)?.users?.firstOrNull()
                    if (user != null) {
                        val mainCollection = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                            .getCollection<TwitchNotifyEntry>("twitch_notify_entries")
                            .find(and(eq("twitchChannelId", userId), eq("guildId", entry.guildId), eq("channelId", entry.channelId)))
                            .toList()

                        val isUserLive = TwitchUtil.isUserLive(user)
                        if (isUserLive) {
                            val streams = TwitchUtil.getStreamsOfUser(user)
                            if (streams != null && streams.streams.isNotEmpty()) {
                                val stream = streams.streams.first()
                                val embed = Template.getStreamEmbed(stream, user, Instant.now())

                                try {
                                    message.editMessageEmbeds(embed).await()
                                } catch (_: Exception) {
                                    val mainCollectionEntry = mainCollection.firstOrNull { it.notifyId == entry.linkedNotifyId }
                                    if (mainCollectionEntry != null) {
                                        val channel = TwitchNotifyBot.shardManager.getTextChannelById(entry.channelId)
                                        if (channel != null) {
                                            val pingRoleId = mainCollectionEntry.mentionRole
                                            val mention = if (pingRoleId != null) {
                                                channel.guild.getRoleById(pingRoleId)
                                            } else {
                                                null
                                            }
                                            if (mention == null) {
                                                val newMessage = channel.sendMessageEmbeds(embed).await()
                                                TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                                                    .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                                                    .replaceOne(
                                                        eq("messageId", entry.messageId),
                                                        LiveNotifyEntry(
                                                            newMessage.id,
                                                            entry.guildId,
                                                            entry.channelId,
                                                            entry.twitchChannelId,
                                                            entry.streamId,
                                                            entry.startTime,
                                                            entry.linkedNotifyId
                                                        ),
                                                        ReplaceOptions().upsert(false)
                                                    )
                                            } else {
                                                val newMessage = channel.sendMessage(mention.asMention).setEmbeds(embed).await()
                                                TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                                                    .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                                                    .replaceOne(
                                                        eq("messageId", entry.messageId),
                                                        LiveNotifyEntry(
                                                            newMessage.id,
                                                            entry.guildId,
                                                            entry.channelId,
                                                            entry.twitchChannelId,
                                                            entry.streamId,
                                                            entry.startTime,
                                                            entry.linkedNotifyId
                                                        ),
                                                        ReplaceOptions().upsert(false)
                                                    )
                                            }
                                        }
                                    }
                                }
                            } else {
                                message.delete().await()
                                TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                                    .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                                    .deleteOne(eq("messageId", entry.messageId))
                            }
                        } else {
                            mainCollection.forEach { mainEntry ->
                                if (mainEntry.deleteMsgWhenStreamEnded) {
                                    message.delete().await()
                                    TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                                        .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                                        .deleteOne(eq("messageId", entry.messageId))
                                } else {
                                    val embed = Template.getOfflineEmbed(user, entry.startTime)
                                    message.editMessageEmbeds(embed).await()
                                    TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                                        .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                                        .deleteOne(eq("messageId", entry.messageId))
                                }
                            }
                        }
                    } else {
                        message.delete().await()
                        TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                            .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                            .deleteOne(eq("messageId", entry.messageId))
                    }
                }
            }
        }
    }
}