package dev.bypixel.twitchnotify.bot

import io.github.cdimascio.dotenv.dotenv
import io.github.freya022.botcommands.api.core.JDAService
import io.github.freya022.botcommands.api.core.defaultSharded
import io.github.freya022.botcommands.api.core.events.BReadyEvent
import io.github.freya022.botcommands.api.core.service.annotations.BService
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.hooks.IEventManager
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag

@BService
class Bot(private val config: Config) : JDAService() {
    private val dotenv = dotenv()

    override val intents: Set<GatewayIntent> = defaultIntents(GatewayIntent.GUILD_MESSAGES,
        GatewayIntent.GUILD_MEMBERS,
        GatewayIntent.GUILD_PRESENCES,
        GatewayIntent.MESSAGE_CONTENT,
        GatewayIntent.GUILD_MEMBERS)

    override val cacheFlags: Set<CacheFlag> = setOf(/* _Additional_ cache flags */ CacheFlag.VOICE_STATE, CacheFlag.ONLINE_STATUS,
        CacheFlag.MEMBER_OVERRIDES)

    val shardTotal = 1 // Total number of shards
    val shardStart = 0
    val shardEnd = 0

    override fun createJDA(event: BReadyEvent, eventManager: IEventManager) {
        TwitchNotifyBot.shardManager = defaultSharded(config.token) {
            setStatus(OnlineStatus.ONLINE)
            setMemberCachePolicy(MemberCachePolicy.ALL)
            setChunkingFilter(ChunkingFilter.ALL)
            setShardsTotal(shardTotal)
            setShards(shardStart, shardEnd)
        }
    }
}