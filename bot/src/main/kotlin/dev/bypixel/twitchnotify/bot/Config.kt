package dev.bypixel.twitchnotify.bot

import io.github.cdimascio.dotenv.dotenv
import io.github.freya022.botcommands.api.core.service.annotations.BService

class Config(val token: String, val ownerIds: List<Long>) {
    companion object {
        // Makes a service factory out of this property getter
        @get:BService
        val instance by lazy {
            val dotenv = dotenv()
            val token = dotenv["BOT_TOKEN"] ?: throw IllegalStateException("DISCORD_TOKEN is not set in .env")
            val ownerIds = dotenv["DISCORD_OWNER_IDS"]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()

            Config(token, ownerIds)
        }
    }
}