package Kinetic_Eco.Tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Guards the app's single definition of "this week".
 *
 * The bug these cover: the dashboard filtered sessions to the calendar week but
 * bucketed and labelled its chart as a rolling 7 days, so early in the week most bars
 * could not be anything but zero regardless of activity — it read as the app wiping
 * history every Monday.
 */
class WeekWindowTest {

    /** Local timestamp for a given date and hour. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun dayOfWeek(ms: Long): Int =
        Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)

    // 2026-08-05 is a Wednesday; the week containing it starts Monday 2026-08-03.
    private val wednesday = at(2026, Calendar.AUGUST, 5, 14)
    private val weekStart = WeekWindow.startOfWeekMs(wednesday)

    @Test
    fun `week starts on Monday at midnight`() {
        assertEquals(Calendar.MONDAY, dayOfWeek(weekStart))
        val cal = Calendar.getInstance().apply { timeInMillis = weekStart }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `a Monday resolves to its own midnight, not the week before`() {
        val monday = at(2026, Calendar.AUGUST, 3, 9)
        assertEquals(weekStart, WeekWindow.startOfWeekMs(monday))
    }

    @Test
    fun `a Sunday belongs to the week that began six days earlier`() {
        val sunday = at(2026, Calendar.AUGUST, 9, 23)
        assertEquals(weekStart, WeekWindow.startOfWeekMs(sunday))
        assertEquals(6, WeekWindow.dayIndexInWeek(sunday, weekStart))
    }

    @Test
    fun `day indices run Monday zero through Sunday six`() {
        val expected = (0..6).map { offset ->
            at(2026, Calendar.AUGUST, 3 + offset, 10)
        }
        expected.forEachIndexed { index, ms ->
            assertEquals("offset $index", index, WeekWindow.dayIndexInWeek(ms, weekStart))
        }
    }

    @Test
    fun `midnight and one-minute-to-midnight land in the same bucket`() {
        val start = at(2026, Calendar.AUGUST, 6, 0, 0)
        val end = at(2026, Calendar.AUGUST, 6, 23, 59)
        assertEquals(3, WeekWindow.dayIndexInWeek(start, weekStart))
        assertEquals(3, WeekWindow.dayIndexInWeek(end, weekStart))
    }

    /** The regression: last week's activity must not leak into this week's chart. */
    @Test
    fun `a session from the previous week is excluded`() {
        val lastSunday = at(2026, Calendar.AUGUST, 2, 20)
        assertNull(WeekWindow.dayIndexInWeek(lastSunday, weekStart))
    }

    @Test
    fun `a session from the following week is excluded`() {
        val nextMonday = at(2026, Calendar.AUGUST, 10, 8)
        assertNull(WeekWindow.dayIndexInWeek(nextMonday, weekStart))
    }

    @Test
    fun `today index matches the day index of now within its own week`() {
        val index = WeekWindow.todayIndex(wednesday)
        assertEquals(2, index) // Wednesday
        assertEquals(index, WeekWindow.dayIndexInWeek(wednesday, weekStart))
    }

    @Test
    fun `labels are Monday-first and cover the whole week`() {
        val labels = WeekWindow.weekdayLabels("EEE", weekStart)
        assertEquals(WeekWindow.DAYS, labels.size)
        assertTrue("labels should be non-blank", labels.all { it.isNotBlank() })
        // First label must name the same weekday as weekStart itself.
        val mondayName = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
            .format(java.util.Date(weekStart))
        assertEquals(mondayName, labels.first())
    }
}