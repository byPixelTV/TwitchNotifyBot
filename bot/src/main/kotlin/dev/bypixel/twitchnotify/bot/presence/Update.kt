package dev.bypixel.twitchnotify.bot.presence

import dev.bypixel.twitchnotify.bot.TwitchNotifyBot
import dev.bypixel.twitchnotify.shared.models.TwitchNotifyEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import net.dv8tion.jda.api.entities.Activity

object Update {
    @Suppress("UNUSED")
    private val job = CoroutineScope(Dispatchers.Default).launch {
        while (isActive) {
            updateActivity()
            delay(15000)
        }
    }

    private suspend fun updateActivity() {
        val shardManager = TwitchNotifyBot.shardManager

        val allTrackedChannels = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
            .getCollection<TwitchNotifyEntry>("twitch_notify_entries")
            .find()
            .toList().size

        shardManager.setActivity(Activity.watching("$allTrackedChannels Twitch channels"))
    }
}