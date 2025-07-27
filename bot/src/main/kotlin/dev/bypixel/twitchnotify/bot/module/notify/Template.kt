package dev.bypixel.twitchnotify.bot.module.notify

import com.github.twitch4j.helix.domain.Stream
import com.github.twitch4j.helix.domain.User
import dev.bypixel.twitchnotify.bot.util.TimeUtil
import dev.bypixel.twitchnotify.bot.util.enum.Colors
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.time.Instant

object Template {
    fun getStreamEmbed(stream: Stream, user: User, updateTime: Instant): MessageEmbed {
        return EmbedBuilder()
            .setTitle(stream.title.ifBlank { "No title set" }, "https://www.twitch.tv/${user.displayName}")
            .addField(MessageEmbed.Field("Game", stream.gameName.ifBlank { "N/A" }, true))
            .addField(MessageEmbed.Field("Viewers", stream.viewerCount.toString(), true))
            .setImage(stream.thumbnailUrlTemplate.replace("{width}", "1280").replace("{height}", "720").ifBlank { "https://cdn.bypixel.dev/raw/d5gGaa.jpg" })
            .setAuthor("${user.displayName} is live", "https://twitch.tv/${user.displayName}", user.profileImageUrl)
            .setFooter(
                "Live since ${
                    TimeUtil.formatMillis(
                        (Instant.now().toEpochMilli() - stream.startedAtInstant.toEpochMilli()),
                        "{d}d {h}h {m}m", false
                    )
                } | Updated at"
            )
            .setTimestamp(updateTime)
            .setColor(Colors.TWITCH.color)
            .build()
    }

    fun getOfflineEmbed(user: User, startedAt: Long): MessageEmbed {
        val endedAt: Instant = Instant.now()
        val duration = endedAt.toEpochMilli() - startedAt
        return EmbedBuilder()
            .setTitle("${user.displayName} is now offline", "https://twitch.tv/${user.displayName}")
            .setAuthor(user.displayName, "https://twitch.tv/${user.displayName}", user.profileImageUrl)
            .setFooter("Streamed for ${
                TimeUtil.formatMillis(
                    duration,
                    "{d}d {h}h {m}m", false
                )
            } | Ended at")
            .setTimestamp(endedAt)
            .setImage(user.offlineImageUrl.ifBlank { "https://cdn.bypixel.dev/raw/d5gGaa.jpg" })
            .setColor(Colors.TWITCH.color)
            .build()
    }
}