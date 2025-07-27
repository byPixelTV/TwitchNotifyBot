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
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration

object MessageUpdateService {
    private val logger = LoggerFactory.getLogger(MessageUpdateService::class.java)

    private val job = CoroutineScope(Dispatchers.IO).launch {
        while (isActive) {
            updateMessages()
            delay(Duration.parse("1m"))
        }
    }

    init {
        job.start()
    }

    private suspend fun updateMessages() {
        val currentStreamMessages = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
            .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
            .find()
            .toList()

        for (entry in currentStreamMessages) {
            val channel = TwitchNotifyBot.shardManager.getTextChannelById(entry.channelId)
            if (channel != null) {
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
                                channel.retrieveMessageById(entry.messageId).await().editMessageEmbeds(embed).await()
                            } catch (_: Exception) {
                                val mainCollectionEntry = mainCollection.firstOrNull { it.notifyId == entry.linkedNotifyId }
                                if (mainCollectionEntry != null) {
                                    val channel = TwitchNotifyBot.shardManager.getTextChannelById(entry.channelId)
                                    if (channel != null) {
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
                                    }
                                }
                            }
                        } else {
                            try {
                                channel.retrieveMessageById(entry.messageId).await().delete().await()
                            } catch (e: Exception) {
                                logger.error("❌ Failed to delete message with ID ${entry.messageId} in channel ${entry.channelId}: ${e.message}")
                            }
                            TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                                .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                                .deleteOne(eq("messageId", entry.messageId))
                        }
                    } else {
                        println("User ${user.displayName} is not live, updating message to offline state or deleting it.")
                        mainCollection.forEach { mainEntry ->
                            if (mainEntry.deleteMsgWhenStreamEnded) {
                                try {
                                    channel.retrieveMessageById(entry.messageId).await().delete().await()
                                } catch (e: Exception) {
                                    logger.error("❌ Failed to delete message with ID ${entry.messageId} in channel ${entry.channelId}: ${e.message}")
                                }
                                TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                                    .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                                    .deleteOne(eq("messageId", entry.messageId))
                            } else {
                                val embed = Template.getOfflineEmbed(user, entry.startTime)
                                try {
                                    channel.retrieveMessageById(entry.messageId).await().editMessageEmbeds(embed).await()
                                } catch (_: Exception) {
                                    channel.sendMessageEmbeds(embed).await()
                                }
                                TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                                    .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                                    .deleteOne(eq("messageId", entry.messageId))
                            }
                        }
                    }
                } else {
                    try {
                        channel.retrieveMessageById(entry.messageId).await().delete().await()
                    } catch (e: Exception) {
                        logger.error("❌ Failed to delete message with ID ${entry.messageId} in channel ${entry.channelId}: ${e.message}")
                    }
                    TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                        .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                        .deleteOne(eq("messageId", entry.messageId))
                }
            } else {
                logger.info("Channel with ID ${entry.channelId} not found or not in cache, not updating message or deleting it.")
            }
        }
    }
}