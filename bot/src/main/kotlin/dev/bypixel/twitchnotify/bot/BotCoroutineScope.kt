package dev.bypixel.twitchnotify.bot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object BotCoroutineScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)