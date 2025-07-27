package dev.bypixel.twitchnotify.shared.jedisWrapper

import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import org.json.JSONObject
import org.slf4j.LoggerFactory
import redis.clients.jedis.BinaryJedisPubSub
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.params.SetParams
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class RedisController(password: String, port: Int, host: String) : BinaryJedisPubSub() {

    private val jedisPool: JedisPool
    private val isConnectionBroken = AtomicBoolean(true)
    private val isConnecting = AtomicBoolean(false)

    private val redisDispatcher = Executors.newFixedThreadPool(6).asCoroutineDispatcher()
    private val scope = CoroutineScope(redisDispatcher + SupervisorJob())
    private var connectionJob: Job? = null

    private val hashLocks = mutableMapOf<String, ReentrantReadWriteLock>()
    private val listLocks = mutableMapOf<String, ReentrantReadWriteLock>()
    private val keyLocks = mutableMapOf<String, ReentrantReadWriteLock>()
    private val lockMapLock = ReentrantReadWriteLock()

    private val dotenv = dotenv()
    private val logger = LoggerFactory.getLogger(RedisController::class.java)

    init {
        val jConfig = JedisPoolConfig().apply {
            maxTotal = 20
            maxIdle = 10
            minIdle = 5
            testOnBorrow = true
            blockWhenExhausted = true
        }

        jedisPool = if (password.isNullOrEmpty()) {
            JedisPool(jConfig, host, port, 9000)
        } else {
            JedisPool(jConfig, host, port, 9000, password)
        }

        startConnectionTask()
    }

    private fun startConnectionTask() {
        connectionJob = scope.launch {
            while (isActive) {
                delay(20 * 5 * 1000L)
                tryReconnect()
            }
        }
    }

    private suspend fun tryReconnect() {
        if (!isConnectionBroken.get() || isConnecting.get()) return

        isConnecting.set(true)
        try {
            withContext(redisDispatcher) {
                jedisPool.resource.use {
                    isConnectionBroken.set(false)
                    logger.info("Successfully reconnected to Redis server!")
                }
            }
        } catch (e: Exception) {
            isConnectionBroken.set(true)
            logger.error("Connection to Redis server has failed! Please check your details.")
            e.printStackTrace()
        } finally {
            isConnecting.set(false)
        }
    }

    fun shutdown() {
        connectionJob?.cancel()
        scope.cancel()
        redisDispatcher.close()
    }

    // Hilfsfunktionen für Lock-Management
    private fun getHashLock(hashName: String): ReentrantReadWriteLock {
        return lockMapLock.read {
            hashLocks[hashName]
        } ?: lockMapLock.write {
            hashLocks.getOrPut(hashName) { ReentrantReadWriteLock() }
        }
    }

    private fun getListLock(listName: String): ReentrantReadWriteLock {
        return lockMapLock.read {
            listLocks[listName]
        } ?: lockMapLock.write {
            listLocks.getOrPut(listName) { ReentrantReadWriteLock() }
        }
    }

    private fun getKeyLock(key: String): ReentrantReadWriteLock {
        return lockMapLock.read {
            keyLocks[key]
        } ?: lockMapLock.write {
            keyLocks.getOrPut(key) { ReentrantReadWriteLock() }
        }
    }

    fun sendMessageAsync(message: String, channel: String): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            val json = JSONObject()
            json.put("messages", message)
            json.put("action", "eramc")
            json.put("date", System.currentTimeMillis())
            finishSendMessageAsync(json, channel)
        }
    }

    fun sendCustomMessageFormatAsync(json: JSONObject, channel: String) {
        scope.launch {
            try {
                val message = json.toString().toByteArray(StandardCharsets.UTF_8)
                withContext(redisDispatcher) {
                    jedisPool.resource.use { jedis ->
                        jedis.publish(channel.toByteArray(StandardCharsets.UTF_8), message)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun finishSendMessageAsync(json: JSONObject, channel: String) {
        scope.launch {
            try {
                val message = json.toString().toByteArray(StandardCharsets.UTF_8)
                jedisPool.resource.use { jedis ->
                    jedis.publish(channel.toByteArray(StandardCharsets.UTF_8), message)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setStringAsync(key: String, value: String) {
        scope.launch {
            val lock = getKeyLock(key)
            lock.write {
                try {
                    jedisPool.resource.use { jedis ->
                        jedis.set(key, value)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun getStringAsync(key: String): CompletableFuture<String?> {
        return CompletableFuture.supplyAsync {
            val lock = getKeyLock(key)
            lock.read {
                jedisPool.resource.use { jedis ->
                    jedis.get(key)
                }
            }
        }
    }

    fun getKeyByValue(hashName: String, value: String): String? {
        val lock = getHashLock(hashName)
        return lock.read {
            try {
                jedisPool.resource.use { jedis ->
                    val keys = jedis.hkeys(hashName)
                    for (key in keys) {
                        if (jedis.hget(hashName, key) == value) {
                            return@read key
                        }
                    }
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun getKeyByValueAsync(hashName: String, value: String): CompletableFuture<String?> {
        return scope.async {
            val lock = getHashLock(hashName)
            lock.read {
                jedisPool.resource.use { jedis ->
                    val keys = jedis.hkeys(hashName)
                    for (key in keys) {
                        if (jedis.hget(hashName, key) == value) {
                            return@read key
                        }
                    }
                    null
                }
            }
        }.asCompletableFuture()
    }

    fun deleteStringAsync(key: String) {
        scope.launch {
            val lock = getKeyLock(key)
            lock.write {
                jedisPool.resource.use { jedis ->
                    jedis.del(key)
                }
            }
        }
    }

    fun sendMessage(message: String, channel: String): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            val json = JSONObject()
            json.put("messages", message)
            json.put("action", "eramc")
            json.put("date", System.currentTimeMillis())
            finishSendMessageAsync(json, channel)
        }
    }

    fun sendCustomMessageFormat(json: JSONObject, channel: String) {
        scope.launch {
            try {
                val message = json.toString().toByteArray(StandardCharsets.UTF_8)
                jedisPool.resource.use { jedis ->
                    jedis.publish(channel.toByteArray(StandardCharsets.UTF_8), message)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeFromListByValue(listName: String, value: String) {
        val lock = getListLock(listName)
        lock.write {
            jedisPool.resource.use { jedis ->
                jedis.lrem(listName, 0, value)
            }
        }
    }

    fun setHashField(hashName: String, fieldName: String, value: String): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            val lock = getHashLock(hashName)
            lock.write {
                try {
                    jedisPool.resource.use { jedis ->
                        jedis.hset(hashName, fieldName, value)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun deleteHashField(hashName: String, fieldName: String) {
        val lock = getHashLock(hashName)
        lock.write {
            jedisPool.resource.use { jedis ->
                jedis.hdel(hashName, fieldName)
            }
        }
    }

    fun createHashFromMap(hashName: String, values: Map<String, String>) {
        if (values.isEmpty()) {
            return
        }

        val lock = getHashLock(hashName)
        lock.write {
            try {
                jedisPool.resource.use { jedis ->
                    jedis.hmset(hashName, values)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun createHashFromMapAsync(hashName: String, values: Map<String, String>) {
        scope.launch {
            if (values.isEmpty()) {
                return@launch
            }

            val lock = getHashLock(hashName)

            lock.write {
                try {
                    jedisPool.resource.use { jedis ->
                        jedis.hmset(hashName, values)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun createListFromList(listName: String, values: List<String>) {
        val lock = getListLock(listName)
        lock.write {
            jedisPool.resource.use { jedis ->
                jedis.rpush(listName, *values.toTypedArray())
            }
        }
    }

    fun createListFromListAsync(listName: String, values: List<String>) {
        scope.launch {
            val lock = getListLock(listName)
            lock.write {
                jedisPool.resource.use { jedis ->
                    jedis.rpush(listName, *values.toTypedArray())
                }
            }
        }
    }

    fun deleteEntriesFromArray(hashName: String, keys: Array<String>) {
        val lock = getHashLock(hashName)
        lock.write {
            jedisPool.resource.use { jedis ->
                for (key in keys) {
                    jedis.hdel(hashName, key)
                }
            }
        }
    }

    fun deleteHash(hashName: String) {
        val lock = getHashLock(hashName)
        lock.write {
            jedisPool.resource.use { jedis ->
                jedis.del(hashName)
            }
        }
    }

    fun addToList(listName: String, values: String) {
        val lock = getListLock(listName)
        lock.write {
            jedisPool.resource.use { jedis ->
                jedis.rpush(listName, values)
            }
        }
    }

    fun setListValue(listName: String, index: Int, value: String) {
        val lock = getListLock(listName)
        lock.write {
            jedisPool.resource.use { jedis ->
                val listLength = jedis.llen(listName)
                if (index >= listLength) {
                    logger.error("Error: Index $index does not exist in the list $listName.")
                } else {
                    jedis.lset(listName, index.toLong(), value)
                }
            }
        }
    }

    fun getHashValuesAsPair(hashName: String): Map<String, String> {
        val lock = getHashLock(hashName)

        lock.read {
            val values = mutableMapOf<String, String>()
            jedisPool.resource.use { jedis ->
                val keys = jedis.hkeys(hashName)
                for (key in keys) {
                    values[key] = jedis.hget(hashName, key)
                }
            }
            return values
        }
    }

    fun getHashValuesAsPairSorted(hashName: String): Map<String, String> {
        val lock = getHashLock(hashName)

        lock.read {
            val keys = mutableListOf<String>()
            val values = LinkedHashMap<String, String>()

            jedisPool.resource.use { jedis ->
                // Alle Schlüssel holen
                keys.addAll(jedis.hkeys(hashName))

                // Schlüssel alphabetisch sortieren
                keys.sort()

                // Werte in sortierter Reihenfolge einfügen
                for (key in keys) {
                    values[key] = jedis.hget(hashName, key)
                }
            }

            return values
        }
    }

    fun getHashValuesAsPairSortedAsync(hashName: String): CompletableFuture<Map<String, String>> {
        return scope.async {
            val lock = getHashLock(hashName)

            lock.read {
                val keys = mutableListOf<String>()
                val values = LinkedHashMap<String, String>()

                jedisPool.resource.use { jedis ->
                    // Alle Schlüssel holen
                    keys.addAll(jedis.hkeys(hashName))

                    // Schlüssel alphabetisch sortieren
                    keys.sort()

                    // Werte in sortierter Reihenfolge einfügen
                    for (key in keys) {
                        values[key] = jedis.hget(hashName, key)
                    }
                }

                values // <- returned from async block
            }
        }.asCompletableFuture()
    }

    fun getHashFieldNameByValueAsync(hashName: String, value: String): CompletableFuture<String?> {
        return scope.async {
            val lock = getHashLock(hashName)

            lock.read {
                jedisPool.resource.use { jedis ->
                    val keys = jedis.hkeys(hashName)
                    for (key in keys) {
                        if (jedis.hget(hashName, key) == value) {
                            return@async key
                        }
                    }
                    null
                }
            }
        }.asCompletableFuture()
    }

    fun deleteHashFieldByValue(hashName: String, value: String) {
        val lock = getHashLock(hashName)

        lock.write {
            jedisPool.resource.use { jedis ->
                val keys = jedis.hkeys(hashName)
                for (key in keys) {
                    if (jedis.hget(hashName, key) == value) {
                        jedis.hdel(hashName, key)
                    }
                }
            }
        }
    }

    fun returnKeysWithMatchingValue(hashName: String, value: String): List<String> {
        val lock = getHashLock(hashName)

        lock.read {
            val keys = mutableListOf<String>()
            jedisPool.resource.use { jedis ->
                val allKeys = jedis.hkeys(hashName)
                for (key in allKeys) {
                    if (jedis.hget(hashName, key) == value) {
                        keys.add(key)
                    }
                }
            }
            return keys
        }
    }

    fun returnValuesWithMatchingKey(hashName: String, key: String): List<String> {
        val lock = getHashLock(hashName)

        lock.read {
            val values = mutableListOf<String>()
            jedisPool.resource.use { jedis ->
                val allKeys = jedis.hkeys(hashName)
                if (allKeys.contains(key)) {
                    values.add(jedis.hget(hashName, key))
                }
            }
            return values
        }
    }

    fun findKeysWithMatchingValues(hashName: String): Map<String, List<String>> {
        val lock = getHashLock(hashName)

        lock.read {
            val matchingKeys = mutableMapOf<String, MutableList<String>>()
            jedisPool.resource.use { jedis ->
                val allKeys = jedis.hkeys(hashName)
                for (key in allKeys) {
                    val value = jedis.hget(hashName, key)
                    if (matchingKeys.containsKey(value)) {
                        matchingKeys[value]?.add(key)
                    } else {
                        matchingKeys[value] = mutableListOf(key)
                    }
                }
            }
            return matchingKeys
        }
    }

    fun findValuesWithMatchingKeys(hashName: String): Map<String, List<String>> {
        val lock = getHashLock(hashName)

        lock.read {
            val matchingValues = mutableMapOf<String, MutableList<String>>()
            jedisPool.resource.use { jedis ->
                val allKeys = jedis.hkeys(hashName)
                for (key in allKeys) {
                    val value = jedis.hget(hashName, key)
                    if (matchingValues.containsKey(key)) {
                        matchingValues[key]?.add(value)
                    } else {
                        matchingValues[key] = mutableListOf(value)
                    }
                }
            }
            return matchingValues
        }
    }

    fun getAllHashNamesByRegex(regex: String): List<String> {
        val lock = getKeyLock(regex)

        lock.read {
            val matchingKeys = mutableListOf<String>()
            jedisPool.resource.use { jedis ->
                val allKeys = jedis.keys(regex)
                for (key in allKeys) {
                    if (jedis.type(key) == "hash") {
                        matchingKeys.add(key)
                    }
                }
            }
            return matchingKeys
        }
    }

    fun getAllHashNamesByRegexAsync(regex: String): CompletableFuture<List<String>> {
        return scope.async {
            val lock = getKeyLock(regex)

            lock.read {
                val matchingKeys = mutableListOf<String>()
                jedisPool.resource.use { jedis ->
                    val allKeys = jedis.keys(regex)
                    for (key in allKeys) {
                        if (jedis.type(key) == "hash") {
                            matchingKeys.add(key)
                        }
                    }
                }
                matchingKeys
            }
        }.asCompletableFuture()
    }

    fun setTtlOfHashField(hashKey: String, field: String, ttl: Long) {
        val lock = getHashLock(hashKey)

        lock.write {
            jedisPool.resource.use { jedis ->
                jedis.hexpire(hashKey, ttl, field)
            }
        }
    }

    fun findKeysWithMatchingValuesAsList(hashName: String): List<String> {
        val lock = getHashLock(hashName)

        lock.read {
            val matchingKeys = mutableListOf<String>()
            jedisPool.resource.use { jedis ->
                val allKeys = jedis.hkeys(hashName)
                val valueToKeysMap = mutableMapOf<String, MutableList<String>>()

                for (key in allKeys) {
                    val value = jedis.hget(hashName, key)
                    if (valueToKeysMap.containsKey(value)) {
                        valueToKeysMap[value]?.add(key)
                    } else {
                        valueToKeysMap[value] = mutableListOf(key)
                    }
                }

                for ((_, keys) in valueToKeysMap) {
                    if (keys.size > 1) {
                        matchingKeys.addAll(keys)
                    }
                }
            }
            return matchingKeys
        }
    }

    fun findValuesWithMatchingKeysAsList(hashName: String): List<String> {
        val lock = getHashLock(hashName)

        lock.read {
            val matchingValues = mutableListOf<String>()
            jedisPool.resource.use { jedis ->
                val allKeys = jedis.hkeys(hashName)
                for (key in allKeys) {
                    val value = jedis.hget(hashName, key)
                    if (!matchingValues.contains(key)) {
                        matchingValues.add(key)
                    }
                }
            }
            return matchingValues
        }
    }

    fun removeFromListByIndex(listName: String, index: Int) {
        val lock = getListLock(listName)

        lock.write {
            jedisPool.resource.use { jedis ->
                val listLength = jedis.llen(listName)
                if (index >= listLength) {
                    logger.error("Error: Index $index does not exist in the list $listName.")
                } else {
                    val tempKey = UUID.randomUUID().toString()
                    jedis.lset(listName, index.toLong(), tempKey)
                    jedis.lrem(listName, 0, tempKey)
                }
            }
        }
    }

    fun removeFromList(listName: String, value: String) {
        val lock = getListLock(listName)

        lock.write {
            jedisPool.resource.use { jedis ->
                jedis.lrem(listName, 0, value)
            }
        }
    }

    fun deleteList(listName: String) {
        val lock = getListLock(listName)

        lock.write {
            jedisPool.resource.use { jedis ->
                jedis.del(listName)
            }
        }
    }

    fun setString(key: String, value: String) {
        val lock = getKeyLock(key)
        lock.write {
            jedisPool.resource.use { jedis ->
                jedis.set(key, value)
            }
        }
    }

    fun getString(key: String): String? {
        val lock = getKeyLock(key)

        return lock.read {
            jedisPool.resource.use { jedis ->
                jedis.get(key)
            }
        }
    }

    fun deleteString(key: String) {
        val lock = getKeyLock(key)

        lock.write {
            jedisPool.resource.use { jedis ->
                jedis.del(key)
            }
        }
    }

    fun getHashField(hashName: String, fieldName: String): String? {
        val lock = getHashLock(hashName)

        return lock.read {
            jedisPool.resource.use { jedis ->
                jedis.hget(hashName, fieldName)
            }
        }
    }

    fun getHashFieldNameByValue(hashName: String, value: String): String? {
        val lock = getHashLock(hashName)

        return lock.read {
            jedisPool.resource.use { jedis ->
                val keys = jedis.hkeys(hashName)
                for (key in keys) {
                    if (jedis.hget(hashName, key) == value) {
                        return@read key
                    }
                }
                null
            }
        }
    }

    fun getAllHashFields(hashName: String): Set<String>? {
        val lock = getHashLock(hashName)

        return lock.read {
            jedisPool.resource.use { jedis ->
                jedis.hkeys(hashName)
            }
        }
    }

    fun getAllHashValues(hashName: String): List<String>? {
        val lock = getHashLock(hashName)

        return lock.read {
            jedisPool.resource.use { jedis ->
                jedis.hvals(hashName)
            }
        }
    }

    fun getList(listName: String): List<String>? {
        return jedisPool.resource.use { jedis ->
            jedis.lrange(listName, 0, -1)
        }
    }

    fun exists(key: String): Boolean {
        val lock = getKeyLock(key)

        return lock.read {
            jedisPool.resource.use { jedis ->
                jedis.exists(key)
            }
        }
    }

    fun getHashFieldNamesByValue(hashName: String, value: String): List<String> {
        val lock = getHashLock(hashName)

        return lock.read {
            val keys = mutableListOf<String>()
            jedisPool.resource.use { jedis ->
                val allKeys = jedis.hkeys(hashName)
                for (key in allKeys) {
                    if (jedis.hget(hashName, key) == value) {
                        keys.add(key)
                    }
                }
            }
            keys
        }
    }

    fun getJedisPool(): JedisPool {
        return jedisPool
    }

    fun existsAsync(key: String): CompletableFuture<Boolean> {
        return scope.async {
            val lock = getKeyLock(key)

            lock.read {
                jedisPool.resource.use { jedis ->
                    jedis.exists(key)
                }
            }
        }.asCompletableFuture()
    }

    fun setTtlOfKeyAsync(key: String, ttl: Long): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            val lock = getKeyLock(key)

            lock.write {
                jedisPool.resource.use { jedis ->
                    jedis.expire(key, ttl)
                }
            }
        }
    }

    fun setHashFieldWithTTLAsync(hashKey: String, field: String, value: String, ttlInSeconds: Long): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            val lock = getHashLock(hashKey)

            lock.write {
                jedisPool.resource.use { jedis ->
                    jedis.hset(hashKey, field, value)
                    jedis.hexpire(hashKey, ttlInSeconds, field)
                }
            }
        }
    }

    fun addToListAsync(listName: String, values: String) {
        scope.launch {
            val lock = getListLock(listName)

            lock.write {
                jedisPool.resource.use { jedis ->
                    jedis.rpush(listName, values)
                }
            }
        }
    }

    fun removeFromListByValueAsync(listName: String, value: String): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            val lock = getListLock(listName)

            lock.write {
                jedisPool.resource.use { jedis ->
                    jedis.lrem(listName, 0, value)
                }
            }
        }
    }

    fun getListAsync(listName: String): CompletableFuture<List<String>?> {
        return scope.async {
            val lock = getListLock(listName)

            lock.write {
                jedisPool.resource.use { jedis ->
                    jedis.lrange(listName, 0, -1)
                }
            }
        }.asCompletableFuture()
    }

    fun deleteListAsync(listName: String) {
        scope.launch {
            val lock = getListLock(listName)

            lock.write {
                jedisPool.resource.use { jedis ->
                    jedis.del(listName)
                }
            }
        }
    }

    fun setHashFieldAsync(hashName: String, fieldName: String, value: String): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            val lock = getHashLock(hashName)

            lock.write {
                try {
                    jedisPool.resource.use { jedis ->
                        jedis.hset(hashName, fieldName, value)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun deleteHashFieldAsync(hashName: String, fieldName: String) {
        scope.launch {
            val lock = getHashLock(hashName)

            lock.write {
                jedisPool.resource.use { jedis ->
                    jedis.hdel(hashName, fieldName)
                }
            }
        }
    }

    fun deleteHashAsync(hashName: String) {
        scope.launch {
            val lock = getHashLock(hashName)

            lock.write {
                jedisPool.resource.use { jedis ->
                    jedis.del(hashName)
                }
            }
        }
    }

    fun getHashFieldAsync(hashName: String, fieldName: String): CompletableFuture<String?> {
        return scope.async {
            val lock = getHashLock(hashName)

            lock.read {
                jedisPool.resource.use { jedis ->
                    jedis.hget(hashName, fieldName)
                }
            }
        }.asCompletableFuture()
    }

    fun getAllHashFieldsAsync(hashName: String): CompletableFuture<Set<String>?> {
        return scope.async {
            val lock = getHashLock(hashName)

            lock.read {
                jedisPool.resource.use { jedis ->
                    jedis.hkeys(hashName)
                }
            }
        }.asCompletableFuture()
    }

    fun getAllHashValuesAsync(hashName: String): CompletableFuture<List<String>?> {
        return scope.async {
            val lock = getHashLock(hashName)

            lock.read {
                jedisPool.resource.use { jedis ->
                    jedis.hvals(hashName)
                }
            }
        }.asCompletableFuture()
    }

    fun getHashValuesAsPairAsync(hashName: String): CompletableFuture<Map<String, String>> {
        return scope.async {
            val values = mutableMapOf<String, String>()
            jedisPool.resource.use { jedis ->
                val keys = jedis.hkeys(hashName)
                for (key in keys) {
                    values[key] = jedis.hget(hashName, key)
                }
            }
            values
        }.asCompletableFuture()
    }

    fun <T> withRedisLock(
        jedisPool: JedisPool,
        lockKey: String,
        timeoutMs: Long = 5000,
        action: () -> T
    ): T? {
        val lockValue = UUID.randomUUID().toString()
        jedisPool.resource.use { jedis ->
            val params = SetParams().nx().px(timeoutMs)
            val acquired = jedis.set(lockKey, lockValue, params) == "OK"
            if (!acquired) return null
            try {
                return action()
            } finally {
                if (jedis.get(lockKey) == lockValue) {
                    jedis.del(lockKey)
                }
            }
        }
    }
}