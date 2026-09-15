package com.unbound.core

import com.unbound.core.model.PartOfDay
import com.unbound.core.model.Season
import com.unbound.core.model.WorldTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldTimeTest {

    @Test
    fun `default start is year 0 day 1 at eight in the morning`() {
        val t = WorldTime.DEFAULT
        assertEquals(8, t.hour)
        assertEquals(0, t.minute)
        assertEquals(1, t.day)
        assertEquals(1, t.month)
    }

    @Test
    fun `advancing rolls hours days months and seasons correctly`() {
        val t = WorldTime.of(year = 1, month = 3, day = 29, hour = 23, minute = 30)
        val later = t.plusMinutes(60)
        assertEquals(0, later.hour)
        assertEquals(30, later.minute)
        assertEquals(30, later.day)
        assertEquals(3, later.month)

        val nextMonth = later.plusMinutes(WorldTime.MINUTES_PER_DAY)
        assertEquals(1, nextMonth.day)
        assertEquals(4, nextMonth.month)
        assertEquals(Season.SUMMER, nextMonth.season)
    }

    @Test
    fun `time cannot move backwards`() {
        assertThrows(IllegalArgumentException::class.java) { WorldTime.DEFAULT.plusMinutes(-1) }
    }

    @Test
    fun `part of day and daylight track the hour`() {
        assertEquals(PartOfDay.DEEP_NIGHT, WorldTime.of(0, 1, 1, 2).partOfDay)
        assertEquals(PartOfDay.MORNING, WorldTime.of(0, 1, 1, 9).partOfDay)
        assertEquals(PartOfDay.EVENING, WorldTime.of(0, 1, 1, 18).partOfDay)
        assertTrue(WorldTime.of(0, 1, 1, 12).isDaylight)
        assertTrue(!WorldTime.of(0, 1, 1, 3).isDaylight)
    }

    @Test
    fun `elapsed time is described in the largest sensible unit`() {
        val start = WorldTime.DEFAULT
        assertEquals("30 minutes ago", start.plusMinutes(30).describeGapSince(start))
        assertEquals("5 hours ago", start.plusMinutes(300).describeGapSince(start))
        assertEquals("3 days ago", start.plusMinutes(3 * WorldTime.MINUTES_PER_DAY).describeGapSince(start))
        assertEquals("2 months ago", start.plusMinutes(60 * WorldTime.MINUTES_PER_DAY).describeGapSince(start))
        assertEquals("1 years ago", start.plusMinutes(365 * WorldTime.MINUTES_PER_DAY).describeGapSince(start))
    }
}
