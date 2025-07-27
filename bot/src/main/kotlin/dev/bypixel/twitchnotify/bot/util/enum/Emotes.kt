package dev.bypixel.twitchnotify.bot.util.enum

enum class Emotes(val value: String) {
    YES("<:yes:1180096759796613231>"),
    NO("<:no:1180096773423906886>"),
    SETTINGS("<:settings:1219394509138559006>"),
    ARROW("<:arrow:1195833506488668332>"),
    SLASH("<:slash:1152674307676307607>"),
    TWITCH("<:twitch:1190791170134134796>"),
    USERS("<:member:1219394606282575952>"),
    QUESTION("<:questionmark:1195840247708262551>"),
    DISCORD("<:discord:1195844391781281954>"),
    DOT("・");

    override fun toString(): String {
        return value
    }
}