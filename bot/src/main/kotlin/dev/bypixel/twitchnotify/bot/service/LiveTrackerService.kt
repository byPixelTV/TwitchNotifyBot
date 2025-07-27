package dev.bypixel.twitchnotify.bot.service

import dev.bypixel.twitchnotify.bot.TwitchNotifyBot
import dev.bypixel.twitchnotify.bot.module.notify.Template
import dev.bypixel.twitchnotify.bot.util.TwitchUtil
import dev.bypixel.twitchnotify.shared.models.LiveNotifyEntry
import dev.bypixel.twitchnotify.shared.models.TwitchNotifyEntry
import dev.minn.jda.ktx.coroutines.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration

object LiveTrackerService {
    private val job = CoroutineScope(Dispatchers.IO).launch {
        while (isActive) {
            trackNewLiveStreams()
            delay(Duration.parse("1m"))
        }
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
            if (user != null && TwitchUtil.isUserLive(user)) {
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

                    if (mention != null && channel != null) {
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
                        if (channel != null) {
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
}