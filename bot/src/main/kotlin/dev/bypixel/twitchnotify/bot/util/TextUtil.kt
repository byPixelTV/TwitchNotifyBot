package dev.bypixel.twitchnotify.bot.util

object TextUtil {
    fun generateRandomCode(length: Int, onlyLowercase: Boolean = false): String {
        val chars = if (onlyLowercase) "abcdefghijklmnopqrstuvwxyz0123456789" else "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }
}