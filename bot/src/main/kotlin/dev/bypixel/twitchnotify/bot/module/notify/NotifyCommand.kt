package dev.bypixel.twitchnotify.bot.module.notify

import com.github.ygimenez.method.Pages
import com.github.ygimenez.model.InteractPage
import com.github.ygimenez.model.Page
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import dev.bypixel.twitchnotify.bot.TwitchNotifyBot
import dev.bypixel.twitchnotify.bot.util.DiscordUtil
import dev.bypixel.twitchnotify.bot.util.TextUtil
import dev.bypixel.twitchnotify.bot.util.TwitchUtil
import dev.bypixel.twitchnotify.bot.util.enum.Constants
import dev.bypixel.twitchnotify.bot.util.enum.Emotes
import dev.bypixel.twitchnotify.shared.models.LiveNotifyEntry
import dev.bypixel.twitchnotify.shared.models.TwitchNameCache
import dev.bypixel.twitchnotify.shared.models.TwitchNotifyEntry
import dev.minn.jda.ktx.coroutines.await
import io.github.freya022.botcommands.api.commands.annotations.Command
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandManager
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandProvider
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent
import kotlinx.coroutines.flow.toList
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel
import java.awt.Color

@Command
object NotifyCommand : GlobalApplicationCommandProvider {
    suspend fun onSlashNotifyAdd(
        event: GuildSlashEvent,
        channelName: String,
        channel: StandardGuildMessageChannel?,
        mention: Role?,
        deleteMsgWhenStreamEnded: Boolean = true
    ) {
        try {
            val twitchClient = TwitchNotifyBot.twitchClient

            val userResults = twitchClient.helix.getUsers(null, null, listOf(channelName)).execute()
            if (userResults.users.isEmpty()) {

                val embed = EmbedBuilder()
                    .setTitle("Channel not found")
                    .setDescription("${Emotes.NO}**${Emotes.DOT}**The channel `$channelName` could not be found on Twitch.")
                    .setColor(Color.RED)
                    .setTimestamp(event.timeCreated)
                    .setAuthor(event.user.effectiveName, null, DiscordUtil.getAvatarOrDefault(event.user))
                    .setFooter(event.jda.selfUser.name, DiscordUtil.getAvatarOrDefault(event.jda.selfUser))
                    .build()
                event.replyEmbeds(embed).setEphemeral(true).await()

                return
            }

            val user = userResults.users.first()
            val twitchChannelId = user.id
            val channelId = channel?.id ?: event.channel.id
            val guildId = event.guild.id

            val existingEntries = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                .getCollection<TwitchNotifyEntry>("twitch_notify_entries")
                .find()
                .toList()

            val existingEntriesForChannel = existingEntries.filter { it.guildId == guildId && it.channelId == channelId && it.twitchChannelId == twitchChannelId }

            if (existingEntriesForChannel.isNotEmpty()) {
                val embed = EmbedBuilder()
                    .setTitle("Channel already added")
                    .setDescription("${Emotes.NO}**${Emotes.DOT}**The channel `$channelName` already sents notifications in this channel. You can only add a channel once per discord channel.")
                    .setColor(Color.RED)
                    .setTimestamp(event.timeCreated)
                    .setAuthor(event.user.effectiveName, null, DiscordUtil.getAvatarOrDefault(event.user))
                    .setFooter(event.jda.selfUser.name, DiscordUtil.getAvatarOrDefault(event.jda.selfUser))
                    .build()
                event.replyEmbeds(embed).setEphemeral(true).await()

                return
            }

            var notifyId = TextUtil.generateRandomCode(8, false)
            while (existingEntries.any { it.notifyId == notifyId }) {
                notifyId = TextUtil.generateRandomCode(8, false)
            }

            TwitchNotifyBot.mongoClient.getDatabase("twitch_notify").getCollection<TwitchNotifyEntry>("twitch_notify_entries")
                .insertOne(TwitchNotifyEntry(guildId, channelId, twitchChannelId, mention?.id, deleteMsgWhenStreamEnded, notifyId))
            val embed = EmbedBuilder()
                .setTitle("Channel added successfully")
                .setDescription("${Emotes.YES}**${Emotes.DOT}** The channel `${user.displayName}` has been added to the notification list.")
                .setColor(Color.GREEN)
                .setTimestamp(event.timeCreated)
                .setAuthor(event.user.effectiveName, null, DiscordUtil.getAvatarOrDefault(event.user))
                .setFooter(event.jda.selfUser.name, DiscordUtil.getAvatarOrDefault(event.jda.selfUser))
                .setThumbnail(user.profileImageUrl)
                .build()
            event.replyEmbeds(embed).setEphemeral(true).await()

            TwitchNotifyBot.mongoClient.getDatabase("twitch_notify").getCollection<TwitchNameCache>("twitch_name_cache")
                .replaceOne(
                    eq("twitchChannelId", twitchChannelId),
                    TwitchNameCache(twitchChannelId, user.displayName),
                    com.mongodb.client.model.ReplaceOptions().upsert(true)
                )

            // check if the channel is currently live
            val userStreamResults = twitchClient.helix.getStreams(null, null, null, null, null, null, listOf(twitchChannelId), null)
                .execute()

            if (userStreamResults.streams.isNotEmpty()) {
                val stream = userStreamResults.streams.first()
                val streamEmbed = Template.getStreamEmbed(stream, user, event.timeCreated.toInstant())

                if (channel != null) {
                    if (mention != null) {
                        val message = channel.sendMessage(mention.asMention).setEmbeds(streamEmbed).await()
                        TwitchNotifyBot.mongoClient.getDatabase("twitch_notify").getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                            .insertOne(LiveNotifyEntry(
                                message.id,
                                message.guild.id,
                                message.channelId,
                                stream.userId,
                                stream.id,
                                stream.startedAtInstant.toEpochMilli(),
                                notifyId
                            ))
                    } else {
                        val message = channel.sendMessageEmbeds(streamEmbed).await()
                        TwitchNotifyBot.mongoClient.getDatabase("twitch_notify").getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                            .insertOne(LiveNotifyEntry(
                                message.id,
                                message.guild.id,
                                message.channelId,
                                stream.userId,
                                stream.id,
                                stream.startedAtInstant.toEpochMilli(),
                                notifyId
                            ))
                    }
                } else {
                    if (mention != null) {
                        val message = event.channel.sendMessage(mention.asMention).setEmbeds(streamEmbed).await()
                        TwitchNotifyBot.mongoClient.getDatabase("twitch_notify").getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                            .insertOne(LiveNotifyEntry(
                                message.id,
                                message.guild.id,
                                message.channelId,
                                stream.userId,
                                stream.id,
                                stream.startedAtInstant.toEpochMilli(),
                                notifyId
                            ))
                    } else {
                        val message = event.channel.sendMessageEmbeds(streamEmbed).await()
                        TwitchNotifyBot.mongoClient.getDatabase("twitch_notify").getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                            .insertOne(LiveNotifyEntry(
                                message.id,
                                message.guild.id,
                                message.channelId,
                                stream.userId,
                                stream.id,
                                stream.startedAtInstant.toEpochMilli(),
                                notifyId
                            ))
                    }
                }
            }
        } catch (_: Exception) {
            val embed = EmbedBuilder()
                .setTitle("Unknown error")
                .setDescription("${Emotes.NO}**${Emotes.DOT}**An unknown error occurred while trying to add the channel `$channelName`. Maybe it is not a valid Twitch channel name? If you think this is a bug, please report it on the **[${Emotes.DISCORD} Support Server](${Constants.SUPPORT_SERVER})**")
                .setColor(Color.RED)
                .setTimestamp(event.timeCreated)
                .setAuthor(event.user.effectiveName, null, DiscordUtil.getAvatarOrDefault(event.user))
                .setFooter(event.jda.selfUser.name, DiscordUtil.getAvatarOrDefault(event.jda.selfUser))
                .build()
            event.reply(Constants.SUPPORT_SERVER.value).setEmbeds(embed).await()
            return
        }
    }

    suspend fun onSlashNotifyRemove(
        event: GuildSlashEvent,
        twitchUserName: String,
        channel: StandardGuildMessageChannel? = null
    ) {
        event.interaction.deferReply(true).await()
        val channel = channel ?: event.channel as StandardGuildMessageChannel
        val notifyEntries = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
            .getCollection<TwitchNotifyEntry>("twitch_notify_entries")
            .find(and(eq("channelId", channel.id), eq("guildId", event.guild.id)))
            .toList()

        val nameNotifyEntry = notifyEntries
            .map { entry -> "${TwitchUtil.getUserById(entry.twitchChannelId)?.users?.firstOrNull()?.displayName}" }
            .firstOrNull { it != "null" && it == twitchUserName }

        if (nameNotifyEntry == null) {
            val embed = EmbedBuilder()
                .setTitle("Channel not found")
                .setDescription("${Emotes.NO}**${Emotes.DOT}**The channel `$twitchUserName` is not in the notification list for ${channel.asMention}. Maybe you want to add it first or it is in another channel?\n\n${Emotes.ARROW}**${Emotes.DOT}** Use `/notify list` to see all channels that are currently in the notification list.")
                .setColor(Color.RED)
                .setTimestamp(event.timeCreated)
                .setAuthor(event.user.effectiveName, null, DiscordUtil.getAvatarOrDefault(event.user))
                .setFooter(event.jda.selfUser.name, DiscordUtil.getAvatarOrDefault(event.jda.selfUser))
                .build()
            event.hook.sendMessageEmbeds(embed).setEphemeral(true).await()
            return
        } else {
            val notifyEntry = notifyEntries.first { entry ->
                "${TwitchUtil.getUserById(entry.twitchChannelId)?.users?.firstOrNull()?.displayName}" == twitchUserName
            }
            val notifyId = notifyEntry.notifyId

            val liveNotifyIds = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                .find(eq("linkedNotifyId", notifyId))
                .toList().map { it.messageId }

            if (liveNotifyIds.isNotEmpty()) {
                liveNotifyIds.forEach { messageId ->
                    val message = channel.retrieveMessageById(messageId).await()
                    message.delete().await()
                }
            }

            TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                .getCollection<TwitchNotifyEntry>("twitch_notify_entries")
                .deleteOne(eq("notifyId", notifyId))
            TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
                .getCollection<LiveNotifyEntry>("twitch_notify_live_entries")
                .deleteMany(eq("linkedNotifyId", notifyId))

            val embed = EmbedBuilder()
                .setTitle("Channel removed successfully")
                .setDescription("${Emotes.YES}**${Emotes.DOT}** The channel `${twitchUserName}` has been removed from the notification list for ${channel.asMention}.")
                .setColor(Color.GREEN)
                .setTimestamp(event.timeCreated)
                .setAuthor(event.user.effectiveName, null, DiscordUtil.getAvatarOrDefault(event.user))
                .setFooter(event.jda.selfUser.name, DiscordUtil.getAvatarOrDefault(event.jda.selfUser))
                .build()
            event.hook.sendMessageEmbeds(embed).setEphemeral(true).await()
        }
    }

    suspend fun onSlashNotifyList(event: GuildSlashEvent) {
        event.interaction.deferReply(true).await()
        val twitchNotifications = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
            .getCollection<TwitchNotifyEntry>("twitch_notify_entries")
            .find(eq("guildId", event.guild.id))
            .toList()

        if (twitchNotifications.isEmpty()) {
            val embed = EmbedBuilder()
                .setTitle("No notifications found")
                .setDescription("${Emotes.NO}**${Emotes.DOT}** There are no Twitch channels in the notification list for this server. You can add channels using `/notify add`.")
                .setColor(Color.RED)
                .setTimestamp(event.timeCreated)
                .setAuthor(event.user.effectiveName, null, DiscordUtil.getAvatarOrDefault(event.user))
                .setFooter(event.jda.selfUser.name, DiscordUtil.getAvatarOrDefault(event.jda.selfUser))
                .build()
            event.replyEmbeds(embed).setEphemeral(true).await()
            return
        }

        val pages = mutableListOf<Page>()
        loop@ for (entries in twitchNotifications.chunked(10)) {
            val description = mutableListOf<String>()
            entries.forEach { entry ->
                val userName = TwitchUtil.getUserById(entry.twitchChannelId)?.users?.firstOrNull()?.displayName ?: continue@loop
                description.add("- $userName (<#${entry.channelId}>)")
            }
            if (description.isEmpty()) continue@loop
            val embed = EmbedBuilder()
                .setTitle("Twitch Notifications")
                .setDescription(description.joinToString("\n"))
                .setColor(Color.GREEN)
                .setTimestamp(event.timeCreated)
                .setAuthor(event.user.effectiveName, null, DiscordUtil.getAvatarOrDefault(event.user))
                .setFooter(event.jda.selfUser.name, DiscordUtil.getAvatarOrDefault(event.jda.selfUser))
                .build()
            pages.add(InteractPage.of(embed))
        }

        if (pages.isEmpty()) {
            val embed = EmbedBuilder()
                .setTitle("No notifications found")
                .setDescription("${Emotes.NO}**${Emotes.DOT}** There are no Twitch channels in the notification list for this server. You can add channels using `/notify add`.")
                .setColor(Color.RED)
                .setTimestamp(event.timeCreated)
                .setAuthor(event.user.effectiveName, null, DiscordUtil.getAvatarOrDefault(event.user))
                .setFooter(event.jda.selfUser.name, DiscordUtil.getAvatarOrDefault(event.jda.selfUser))
                .build()
            event.replyEmbeds(embed).setEphemeral(true).await()
            return
        }

        val messageContent = pages[0].content as MessageEmbed
        event.hook.sendMessageEmbeds(messageContent).setEphemeral(true).queue { success ->
            Pages.paginate(success, pages, true)
        }
    }

    override fun declareGlobalApplicationCommands(manager: GlobalApplicationCommandManager) {
        manager.slashCommand("notify", null) {
            description = "Manage Twitch notifications"
            subcommand("add", ::onSlashNotifyAdd) {
                description = "Add a Twitch channel where you want to get notified about streams"
                option("channelName", "channel_name") {
                    description = "The Twitch channel to add"
                }
                option("channel", "notification_channel") {
                    description = "The channel to send the notification to"
                }
                option("mention", "role_to_mention") {
                    description = "The role to mention when the stream starts"
                }
                option("deleteMsgWhenStreamEnded", "delete_msg_when_stream_ended") {
                    description = "Delete the message when the stream ends"
                }
            }
            subcommand("remove", ::onSlashNotifyRemove) {
                description = "Remove a Twitch channel from the notification list"
                option("twitchUserName", "channel_name") {
                    description = "The Twitch channel to remove from the notification list"
                    autocompleteByFunction(NotifyCommandAutocomplete::onNotificationAutocomplete)
                }
                option("channel", "notification_channel") {
                    description = "The channel to remove the notification from"
                }
            }
            subcommand("list", ::onSlashNotifyList) {
                description = "List all Twitch channels that are currently in the notification list on this server"
            }
        }
    }
}