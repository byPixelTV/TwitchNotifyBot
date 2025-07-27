package dev.bypixel.twitchnotify.bot.util

import com.github.twitch4j.helix.domain.StreamList
import com.github.twitch4j.helix.domain.User
import com.github.twitch4j.helix.domain.UserList
import dev.bypixel.twitchnotify.bot.TwitchNotifyBot

object TwitchUtil {
    private val twitchClient = TwitchNotifyBot.twitchClient

    fun getUserByName(username: String): UserList? {
        return twitchClient.helix.getUsers(null, null, listOf(username)).execute()
    }

    fun getUserById(userId: String): UserList? {
        return twitchClient.helix.getUsers(null, listOf(userId), null).execute()
    }

    fun getStreamsOfUser(user: User): StreamList? {
        return twitchClient.helix.getStreams(null, null, null, null, null, null, listOf(user.id), null)
            .execute()
    }

    fun isUserLive(user: User): Boolean {
        val streams = getStreamsOfUser(user)
        return if (streams == null || streams.streams.isEmpty()) {
            false
        } else {
            streams.streams.firstOrNull() != null && streams.streams.first().userId == user.id
        }
    }
}