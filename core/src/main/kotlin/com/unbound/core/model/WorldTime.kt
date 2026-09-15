package com.unbound.core.model

import kotlinx.serialization.Serializable

/**
 * In-world time. Stored as a single monotonically increasing minute count so that time arithmetic,
 * ordering and "how long ago" questions are trivial and exact, with a calendar projected on top for
 * display. The calendar is deliberately simple and fictional: 12 months of 30 days, 4 seasons.
 *
 * World time only ever moves forward. [plusMinutes] is the only way to advance it, and the state
 * validator rejects any patch that would move it backwards.
 */
@Serializable
data class WorldTime(val totalMinutes: Long) : Comparable<WorldTime> {

    val minute: Int get() = (totalMinutes % 60).toInt()
    val hour: Int get() = ((totalMinutes / 60) % 24).toInt()
    val dayOfYear: Int get() = ((totalMinutes / MINUTES_PER_DAY) % DAYS_PER_YEAR).toInt()
    val day: Int get() = (dayOfYear % DAYS_PER_MONTH) + 1
    val month: Int get() = (dayOfYear / DAYS_PER_MONTH) + 1
    val year: Int get() = (totalMinutes / MINUTES_PER_DAY / DAYS_PER_YEAR).toInt()
    val absoluteDay: Long get() = totalMinutes / MINUTES_PER_DAY

    val season: Season
        get() = Season.entries[(month - 1) / 3]

    val partOfDay: PartOfDay
        get() = when (hour) {
            in 0..4 -> PartOfDay.DEEP_NIGHT
            in 5..7 -> PartOfDay.DAWN
            in 8..11 -> PartOfDay.MORNING
            in 12..16 -> PartOfDay.AFTERNOON
            in 17..19 -> PartOfDay.EVENING
            else -> PartOfDay.NIGHT
        }

    val isDaylight: Boolean get() = hour in 6..19

    fun plusMinutes(minutes: Long): WorldTime {
        require(minutes >= 0) { "world time cannot move backwards" }
        return WorldTime(totalMinutes + minutes)
    }

    fun minutesSince(other: WorldTime): Long = totalMinutes - other.totalMinutes

    /** Short display form, e.g. "Day 12 of Month 3, Year 1 — 14:05". */
    fun display(): String = "Day %d of Month %d, Year %d — %02d:%02d".format(day, month, year, hour, minute)

    /** Human phrasing of elapsed time, used in prompts and the journal. */
    fun describeGapSince(other: WorldTime): String {
        val mins = minutesSince(other)
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins minutes ago"
            mins < MINUTES_PER_DAY -> "${mins / 60} hours ago"
            mins < MINUTES_PER_DAY * DAYS_PER_MONTH -> "${mins / MINUTES_PER_DAY} days ago"
            mins < MINUTES_PER_DAY * DAYS_PER_YEAR -> "${mins / (MINUTES_PER_DAY * DAYS_PER_MONTH)} months ago"
            else -> "${mins / (MINUTES_PER_DAY * DAYS_PER_YEAR)} years ago"
        }
    }

    override fun compareTo(other: WorldTime): Int = totalMinutes.compareTo(other.totalMinutes)

    companion object {
        const val MINUTES_PER_DAY = 60L * 24
        const val DAYS_PER_MONTH = 30
        const val DAYS_PER_YEAR = 360

        /** Default world start: year 1, month 1, day 1, 08:00. */
        val DEFAULT: WorldTime = WorldTime(8 * 60L)

        fun of(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): WorldTime {
            val days = year.toLong() * DAYS_PER_YEAR + (month - 1).toLong() * DAYS_PER_MONTH + (day - 1)
            return WorldTime(days * MINUTES_PER_DAY + hour * 60L + minute)
        }
    }
}

enum class Season { SPRING, SUMMER, AUTUMN, WINTER }

enum class PartOfDay { DEEP_NIGHT, DAWN, MORNING, AFTERNOON, EVENING, NIGHT }
