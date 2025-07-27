package dev.bypixel.twitchnotify.bot.util

import dev.bypixel.twitchnotify.bot.TwitchNotifyBot
import net.dv8tion.jda.api.entities.User

object DiscordUtil {
    fun getAvatarOrDefault(user: User): String {
        return user.avatarUrl?.let { "$it?size=2048" } ?: user.defaultAvatarUrl
    }

    fun getGuilds(): Int {
        val shardManager = TwitchNotifyBot.shardManager
        return shardManager.guilds.size
    }
}