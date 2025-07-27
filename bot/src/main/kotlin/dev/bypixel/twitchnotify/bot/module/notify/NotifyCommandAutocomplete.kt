package dev.bypixel.twitchnotify.bot.module.notify

import com.mongodb.client.model.Filters.eq
import dev.bypixel.twitchnotify.bot.TwitchNotifyBot
import dev.bypixel.twitchnotify.shared.models.TwitchNotifyEntry
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.CacheAutocomplete
import io.github.freya022.botcommands.api.core.annotations.Handler
import kotlinx.coroutines.flow.toList
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent

@Handler
class NotifyCommandAutocomplete {
    @AutocompleteHandler(WORD_AUTOCOMPLETE_NAME)
    @CacheAutocomplete(cacheSize = 20000, guildLocal = true, userLocal = false, channelLocal = false)
    suspend fun onNotificationAutocomplete(event: CommandAutoCompleteInteractionEvent): Collection<String> {
        val twitchGuildChannelIds = TwitchNotifyBot.mongoClient.getDatabase("twitch_notify")
            .getCollection<TwitchNotifyEntry>("twitch_notify_entries")
            .find(eq("guildId", event.guild?.id))
            .toList().map { entry -> entry.twitchChannelId }
        return TwitchNotifyBot.redisController.getHashValuesAsPair("twitch_name_cache")
            .filter { it.key in twitchGuildChannelIds }
            .map { it.value }
            .toSet()
    }

    companion object {
        const val WORD_AUTOCOMPLETE_NAME = "Twitch notification"
    }
}