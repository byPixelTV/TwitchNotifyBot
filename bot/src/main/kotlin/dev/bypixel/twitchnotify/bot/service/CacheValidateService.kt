package dev.bypixel.twitchnotify.bot.service

import dev.bypixel.twitchnotify.bot.util.TwitchUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

object CacheValidateService {
    val job = CoroutineScope(Dispatchers.IO).launch {
        if (isActive) {
            TwitchUtil.validateCache()
            delay(Duration.parse("30m"))
        }
    }

    init {
        job.start()
    }
}