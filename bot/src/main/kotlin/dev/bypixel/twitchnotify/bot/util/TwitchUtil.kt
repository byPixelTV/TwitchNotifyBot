package dev.bypixel.twitchnotify.bot.util

import com.github.twitch4j.helix.domain.StreamList
import com.github.twitch4j.helix.domain.User
import com.github.twitch4j.helix.domain.UserList
import dev.bypixel.twitchnotify.bot.TwitchNotifyBot
import dev.bypixel.twitchnotify.shared.models.TwitchNameCache
import dev.bypixel.twitchnotify.shared.models.TwitchNotifyEntry
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

object TwitchUtil {
    private val twitchClient = TwitchNotifyBot.twitchClient
    private val mongoClient = TwitchNotifyBot.mongoClient
    private val logger = LoggerFactory.getLogger(TwitchUtil::class.java)

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

    suspend fun validateCache() {
        mongoClient.getDatabase("twitch_notify").getCollection<TwitchNameCache>("twitch_name_cache")
            .drop()
        logger.info("🗑️ Cleared Twitch Name Cache collection.")
        val twitchIds = mongoClient.getDatabase("twitch_notify")
            .getCollection<TwitchNotifyEntry>("twitch_notify_entries")
            .find()
            .toList()
            .map { it.twitchChannelId }
            .distinct()
        logger.info("🔄 Caching Twitch IDs: ${twitchIds.size} entries found.")
        val twitchCache = mutableListOf<TwitchNameCache>()
        for (twitchId in twitchIds) {
            try {
                val user = getUserById(twitchId)?.users?.firstOrNull()
                if (user != null) {
                    twitchCache.add(
                        TwitchNameCache(
                            user.id,
                            user.displayName,
                        )
                    )
                    TwitchNotifyBot.redisController.setHashFieldAsync("twitch_name_cache", user.id, user.displayName)
                    TwitchNotifyBot.redisController.setHashFieldAsync("twitch_name_cache", user.displayName, user.id)
                    logger.info("✅ Cached Twitch ID: $twitchId as ${user.displayName}")
                } else {
                    logger.warn("⚠️ No user found for Twitch ID: $twitchId")
                }
            } catch (e: Exception) {
                logger.error("❌ Error caching Twitch ID $twitchId: ${e.message}")
            }
        }
        if (twitchCache.isNotEmpty()) {
            mongoClient.getDatabase("twitch_notify")
                .getCollection<TwitchNameCache>("twitch_name_cache")
                .insertMany(twitchCache)
            logger.info("✅ Cached ${twitchCache.size} Twitch IDs to MongoDB.")
        } else {
            logger.warn("❌ No Twitch IDs to cache.")
        }
    }
}