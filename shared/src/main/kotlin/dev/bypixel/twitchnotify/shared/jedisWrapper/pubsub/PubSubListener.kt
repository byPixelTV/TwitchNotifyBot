package dev.bypixel.twitchnotify.shared.jedisWrapper.pubsub

import dev.bypixel.twitchnotify.shared.jedisWrapper.RedisController
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import redis.clients.jedis.exceptions.JedisDataException

abstract class PubSubListener(protected val redisController: RedisController, protected val channel: String) {

    init {
        registerListener(this)
    }

    fun unregister() {
        unregisterListener(this)
    }

    companion object {
        private val listeners = mutableSetOf<PubSubListener>()
        private var isSubscribed = false
        private var jedisPubSub: JedisPubSub? = null
        private var jedisPool: JedisPool? = null

        fun initialize(redisController: RedisController) {
            jedisPool = redisController.getJedisPool()
        }

        private fun subscribeToAllChannels() {
            if (!isSubscribed && jedisPool != null) {
                jedisPubSub = object : JedisPubSub() {
                    override fun onPMessage(pattern: String?, channel: String?, message: String?) {
                        if (channel != null && message != null) {
                            listeners.forEach { listener ->
                                if (listener.channel == channel) {
                                    listener.onMessage(message)
                                }
                            }
                        }
                    }
                }

                Thread {
                    var attempts = 0
                    val maxAttempts = 5
                    while (attempts < maxAttempts) {
                        try {
                            jedisPool!!.resource.use { jedis ->
                                jedis.psubscribe(jedisPubSub, "*")
                            }
                            break
                        } catch (ex: JedisDataException) {
                            val errorMsg = ex.message ?: ""
                            if (errorMsg.contains("invalid multibulk length", ignoreCase = true)) {
                                attempts++
                                println("Ignored invalid multibulk reply; attempt $attempts of $maxAttempts.")
                                Thread.sleep(2000)
                                continue
                            } else {
                                ex.printStackTrace()
                                break
                            }
                        } catch (ex: Exception) {
                            attempts++
                            ex.printStackTrace()
                            Thread.sleep(2000)
                        }
                    }
                }.start()

                isSubscribed = true
            }
        }

        private fun registerListener(listener: PubSubListener) {
            listeners.add(listener)
            subscribeToAllChannels()
        }

        fun unregisterListener(listener: PubSubListener) {
            listeners.remove(listener)
        }
    }

    abstract fun onMessage(message: String)
}