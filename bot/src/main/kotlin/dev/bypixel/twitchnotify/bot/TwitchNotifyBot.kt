package dev.bypixel.twitchnotify.bot
import com.github.twitch4j.TwitchClient
import com.github.twitch4j.TwitchClientBuilder
import com.github.twitch4j.auth.providers.TwitchIdentityProvider
import com.github.ygimenez.model.PaginatorBuilder
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import dev.bypixel.twitchnotify.bot.presence.Update
import dev.bypixel.twitchnotify.bot.service.CacheValidateService
import dev.bypixel.twitchnotify.bot.service.LiveTrackerService
import dev.bypixel.twitchnotify.bot.service.MessageUpdateService
import dev.bypixel.twitchnotify.bot.util.TwitchUtil
import dev.bypixel.twitchnotify.shared.jedisWrapper.RedisController
import dev.bypixel.twitchnotify.shared.jedisWrapper.pubsub.PubSubListener
import dev.bypixel.twitchnotify.shared.models.TwitchNameCache
import dev.bypixel.twitchnotify.shared.models.TwitchNotifyEntry
import dev.reformator.stacktracedecoroutinator.jvm.DecoroutinatorJvmApi
import io.github.cdimascio.dotenv.dotenv
import io.github.freya022.botcommands.api.core.BotCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.sharding.ShardManager
import okhttp3.OkHttpClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit


class TwitchNotifyBot : ListenerAdapter() {
    val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        lateinit var shardManager: ShardManager
        private val logger: Logger = LoggerFactory.getLogger(TwitchNotifyBot::class.java)
        lateinit var mongoClient: MongoClient
        lateinit var redisController: RedisController
        lateinit var twitchClient: TwitchClient
        var startTime: Long = 0

        @JvmStatic
        fun main(args: Array<String>) {
            if ("-XX:+AllowEnhancedClassRedefinition" in ManagementFactory.getRuntimeMXBean().inputArguments) {
                logger.info("Skipping stacktrace-decoroutinator as enhanced hotswap is active")
            } else if ("--no-decoroutinator" in args) {
                logger.info("Skipping stacktrace-decoroutinator as --no-decoroutinator is specified")
            } else {
                DecoroutinatorJvmApi.install()
            }

            logger.info("⚙️ Initializing TwitchNotifyBot...")
            startTime = System.currentTimeMillis() / 1000
            val dotenv = dotenv()

            // Initialize RedisController and PubSubListener
            logger.info("⚙️ Initializing RedisController & PubSubListener...")
            val password = dotenv["REDIS_PASSWORD"] ?: throw IllegalArgumentException("REDIS_PASSWORD is not set in .env")
            val port = dotenv["REDIS_PORT"]?.toIntOrNull() ?: 6379
            val host = dotenv["REDIS_HOST"] ?: "127.0.0.1"
            redisController = RedisController(password, port, host)

            PubSubListener.initialize(redisController)
            logger.info("⚡ RedisController & PubSubListener initialized.")

            val mongoUser = dotenv["MONGO_USER"] ?: throw IllegalArgumentException("MONGO_USER is not set in .env")
            val mongoPassword = dotenv["MONGO_PASSWORD"] ?: throw IllegalArgumentException("MONGO_PASSWORD is not set in .env")
            val mongoHost = dotenv["MONGO_HOST"] ?: "localhost"
            val mongoPort = dotenv["MONGO_PORT"] ?: "27017"

            val connectionString = "mongodb://$mongoUser:$mongoPassword@$mongoHost:$mongoPort/?retryWrites=true&w=majority&uuidRepresentation=standard"

            try {
                val settings = MongoClientSettings.builder()
                    .applyConnectionString(ConnectionString(connectionString))
                    .applyToConnectionPoolSettings {
                        it.maxSize(50) // Optional, weniger = schnelleres Timeout bei Auslastung
                        it.maxWaitTime(2, TimeUnit.SECONDS) // Wichtig! Kein ewiges Warten auf Connection
                    }
                    .applyToSocketSettings {
                        it.connectTimeout(2, TimeUnit.SECONDS) // DNS / Verbindungsaufbau
                        it.readTimeout(2, TimeUnit.SECONDS)    // Langsame Antwort
                    }
                    .retryWrites(true)
                    .build()

                mongoClient = MongoClient.create(settings)
                logger.info("🔥 Connected to MongoDB at $mongoHost:$mongoPort as $mongoUser")

            } catch (e: Exception) {
                logger.error("❌ Failed to connect to MongoDB: ${e.message}")
                return
            }


            val clientId = dotenv["TWITCH_CLIENT_ID"]
            val clientSecret = dotenv["TWITCH_CLIENT_SECRET"]

            val identityProvider = TwitchIdentityProvider(clientId, clientSecret, "https://localhost")

            twitchClient = TwitchClientBuilder.builder()
                .withClientId(clientId)
                .withClientSecret(clientSecret)
                .withEnableHelix(true)
                .withDefaultAuthToken(identityProvider.appAccessToken)
                .build()
            logger.info("📷 Connected to Twitch API via twitch4j")

            // PresenceChanger
            BotCommands.create {
                addSearchPath("dev.bypixel.twitchnotify.bot")
                components {
                    enable = true
                }
            }

            Update
            LiveTrackerService
            MessageUpdateService

            PaginatorBuilder.createPaginator(shardManager)
                .shouldEventLock(true)
                .shouldRemoveOnReact(true)
                .activate()

            CacheValidateService
        }
    }

    override fun onReady(event: ReadyEvent) {
        val shardId = event.jda.shardInfo.shardId
        logger.info("⚡ Spawned shard $shardId")
    }

    override fun onShutdown(event: ShutdownEvent) {
        event.jda.shutdown()
        logger.info("⚙️ Shard ${event.jda.shardInfo.shardId} is shutting down.")
        val client: OkHttpClient = event.jda.httpClient
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        coroutineScope.cancel()

        redisController.shutdown()
        logger.info("✅ TwitchNotifyBot has been shut down successfully.")
    }
}