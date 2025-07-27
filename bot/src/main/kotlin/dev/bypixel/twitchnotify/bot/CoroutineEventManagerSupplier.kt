package dev.bypixel.twitchnotify.bot

import dev.minn.jda.ktx.events.CoroutineEventManager
import io.github.freya022.botcommands.api.core.ICoroutineEventManagerSupplier
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.freya022.botcommands.api.core.utils.namedDefaultScope

@BService
class CoroutineEventManagerSupplier : ICoroutineEventManagerSupplier {
    override fun get(): CoroutineEventManager {
        val scope = namedDefaultScope("TwitchNotifyBot Coroutine", corePoolSize = 4)
        return CoroutineEventManager(scope)
    }
}
