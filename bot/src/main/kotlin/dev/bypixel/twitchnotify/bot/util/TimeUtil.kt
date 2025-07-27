package dev.bypixel.twitchnotify.bot.util

object TimeUtil {
    // Vereinfachte Map nur für kleine Einheiten (exakte Werte)
    private val exactTimeUnitMap = mapOf(
        // Englisch
        "w" to 604800.0, "week" to 604800.0, "weeks" to 604800.0,
        "d" to 86400.0, "day" to 86400.0, "days" to 86400.0,
        "h" to 3600.0, "hr" to 3600.0, "hour" to 3600.0, "hours" to 3600.0,
        "m" to 60.0, "min" to 60.0, "minute" to 60.0, "minutes" to 60.0,
        "s" to 1.0, "sec" to 1.0, "second" to 1.0, "seconds" to 1.0,

        // Deutsch
        "woche" to 604800.0, "wochen" to 604800.0,
        "tag" to 86400.0, "tage" to 86400.0,
        "stunde" to 3600.0, "stunden" to 3600.0,
        "minute" to 60.0, "minuten" to 60.0,
        "sekunde" to 1.0, "sekunden" to 1.0,
    )

    // Erweiterte Map für alle Einheiten (für Validierung)
    private val allTimeUnitMap = exactTimeUnitMap + mapOf(
        // Englisch
        "y" to 31536000.0, "yr" to 31536000.0, "year" to 31536000.0, "years" to 31536000.0,
        "mo" to 2592000.0, "month" to 2592000.0, "months" to 2592000.0,

        // Deutsch
        "jahr" to 31536000.0, "jahre" to 31536000.0,
        "monat" to 2592000.0, "monate" to 2592000.0,
    )

    fun formatMillis(
        millis: Long,
        format: String = "{h}:{m}:{s}",
        includeZero: Boolean = true
    ): String {
        val unitOrder = exactTimeUnitMap.keys.sortedByDescending { it.length }
        val usedUnits = unitOrder.filter { format.contains("{$it}") }

        var remainingMillis = millis
        val values = mutableMapOf<String, Long>()

        val unitMillis = exactTimeUnitMap.mapValues { (_, seconds) -> (seconds * 1000).toLong() }

        for ((i, unit) in usedUnits.withIndex()) {
            val millisPerUnit = unitMillis[unit] ?: continue
            if (i == usedUnits.lastIndex) {
                values[unit] = remainingMillis / millisPerUnit
            } else {
                values[unit] = remainingMillis / millisPerUnit
                remainingMillis %= millisPerUnit
            }
        }

        val allUsedZero = usedUnits.all { values[it] == 0L }

        if (allUsedZero && !includeZero) {
            val unitPriority = unitOrder.withIndex().associate { it.value to it.index }
            val lowestUnit = usedUnits.maxByOrNull { unitPriority[it] ?: 0 } ?: "s"
            val suffix = """\{$lowestUnit}([^{]*)""".toRegex().find(format)?.groupValues?.get(1) ?: ""
            return "0$suffix"
        }

        var result = format
        for (unit in usedUnits) {
            val value = values[unit] ?: 0L
            result = if (includeZero || value > 0) {
                result.replace("""\{$unit}""".toRegex(), value.toString())
            } else {
                result.replace("""\{$unit}[^{]*""".toRegex(), "")
            }
        }

        return result.replace(Regex("\\s+"), " ").trim()
    }
}