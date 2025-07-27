package dev.bypixel.twitchnotify.bot

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.sharding.ShardManager

fun ShardManager.getCurrentShard(): JDA? {
    return this.shards.firstOrNull { it.status.isInit }
}

fun ShardManager.getCurrentShardId(): Int {
    return this.shards.indexOfFirst { it.status.isInit }
}