package dev.bypixel.twitchnotify.bot.service

import com.mongodb.client.model.Filters.eq
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

object LiveTrackerService {
    private val logger = LoggerFactory.getLogger(LiveTrackerService::class.java)
    private val job = CoroutineScope(Dispatchers.IO).launch {
        while (isActive) {
            trackNewLiveStreams()
            delay(Duration.parse("1m"))
        }
    }

    init {
        job.start()
    }

    private suspend fun trackNewLiveStreams() {
        val currentLiveStreams = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
            .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
            .find()
            .toList()

        val currentLiveStreamNotifyIds = currentLiveStreams.map { it.linkedNotifyId }.toSet()

        val allTrackingChannel = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
            .getCollection<TwitchNotifyEntry>("twitch_notify_entries")
            .find()
            .toList()

        val streamsToTrack = allTrackingChannel.filter { entry ->
            !currentLiveStreamNotifyIds.contains(entry.notifyId)
        }

        for (entry in streamsToTrack) {
            val user = TwitchUtil.getUserById(entry.twitchChannelId)?.users?.firstOrNull()

            if (user == null) {
                continue
            }

            if (TwitchUtil.isUserLive(user)) {
                val streams = TwitchUtil.getStreamsOfUser(user)
                if (streams != null && streams.streams.isNotEmpty()) {
                    val stream = streams.streams.first()
                    val embed = Template.getStreamEmbed(stream, user, Instant.now())
                    val pingRoleId = entry.mentionRole
                    val mention = if (pingRoleId != null) {
                        TwitchNotifyBot.shardManager.getGuildById(entry.guildId)?.getRoleById(pingRoleId)
                    } else {
                        null
                    }
                    val channel = TwitchNotifyBot.shardManager.getTextChannelById(entry.channelId)

                    if (channel == null) {
                        TwitchNotifyBot.mongoClient.getDatabase("twitch_notify").getCollection<TwitchNotifyEntry>("twitch_notify_entries")
                            .deleteMany(eq("channelId", entry.channelId))
                        logger.warn("Channel with ID ${entry.channelId} not found, removing entry from database.")
                        continue
                    }

                    if (mention != null) {
                        val message = channel.sendMessage(mention.asMention).setEmbeds(embed).await()
                        TwitchNotifyBot.mongoClient.getDatabase("twitch_notify").getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                            .insertOne(LiveNotifyEntry(
                                message.id,
                                message.guild.id,
                                message.channelId,
                                stream.userId,
                                stream.id,
                                stream.startedAtInstant.toEpochMilli(),
                                entry.notifyId
                            ))
                    } else {
                        val message = channel.sendMessageEmbeds(embed).await()
                        TwitchNotifyBot.mongoClient.getDatabase("twitch_notify").getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                            .insertOne(LiveNotifyEntry(
                                message.id,
                                message.guild.id,
                                message.channelId,
                                stream.userId,
                                stream.id,
                                stream.startedAtInstant.toEpochMilli(),
                                entry.notifyId
                            ))
                    }
                }
            }
        }
    }
}