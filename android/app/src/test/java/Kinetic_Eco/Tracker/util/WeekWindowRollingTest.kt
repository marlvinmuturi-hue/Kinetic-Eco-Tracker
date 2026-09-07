package Kinetic_Eco.Tracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * The rolling seven-day window that replaced the calendar week on the user-facing surfaces.
 *
 * The bug it exists for: a calendar week empties at Monday 00:00, so the dashboard, the weekly
 * report and the sessions list all went blank simultaneously and read as lost history. A rolling
 * window always spans seven days of real activity. These tests pin the two properties that
 * matters for that — the window always ends today, and every day maps to exactly one bucket.
 */
class WeekWindowRollingTest {

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance().apply {
            set(y, m - 1, d, h, min, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun midnightOf(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `window starts at midnight six days back`() {
        val now = at(2026, 9, 7, 8, 45)          // Monday morning — the reported failure case
        val start = WeekWindow.rollingStartMs(now)
        assertEquals("must be day-aligned", midnightOf(start), start)
        assertEquals("must be exactly 6 days back", midnightOf(at(2026, 9, 1)), start)
    }

    @Test
    fun `today is always the last bucket`() {
        // The property that fixes Monday: whatever the weekday, today occupies index 6, so the
        // window never consists of six empty future days the way a calendar week does.
        listOf(
            at(2026, 9, 7),  // Monday
            at(2026, 9, 9),  // Wednesday
            at(2026, 9, 13)  // Sunday
        ).forEach { now ->
            val start = WeekWindow.rollingStartMs(now)
            assertEquals(
                "today should be the final bucket",
                WeekWindow.DAYS - 1,
                WeekWindow.dayIndexInWindow(now, start)
            )
        }
    }

    @Test
    fun `each of the seven days maps to its own bucket in order`() {
        val now = at(2026, 9, 7, 10, 0)
        val start = WeekWindow.rollingStartMs(now)
        (0 until WeekWindow.DAYS).forEach { offset ->
            val day = Calendar.getInstance().apply {
                timeInMillis = start
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, 14)     // mid-afternoon, not a boundary
            }.timeInMillis
            assertEquals(offset, WeekWindow.dayIndexInWindow(day, start))
        }
    }

    @Test
    fun `boundaries land in the right bucket`() {
        val now = at(2026, 9, 7, 10, 0)
        val start = WeekWindow.rollingStartMs(now)
        // First instant of the window
        assertEquals(0, WeekWindow.dayIndexInWindow(start, start))
        // Last instant of the first day
        assertEquals(0, WeekWindow.dayIndexInWindow(start + 86_399_999L, start))
        // First instant of the second day
        assertEquals(1, WeekWindow.dayIndexInWindow(start + 86_400_000L, start))
    }

    @Test
    fun `timestamps outside the window are rejected`() {
        val now = at(2026, 9, 7, 10, 0)
        val start = WeekWindow.rollingStartMs(now)
        assertNull("before the window", WeekWindow.dayIndexInWindow(start - 1, start))
        val eighthDay = Calendar.getInstance().apply {
            timeInMillis = start; add(Calendar.DAY_OF_YEAR, WeekWindow.DAYS)
        }.timeInMillis
        assertNull("past the last day", WeekWindow.dayIndexInWindow(eighthDay, start))
    }

    @Test
    fun `rolling window is never empty of days the way a calendar week is`() {
        // On a Monday morning the calendar week contains one partial day; the rolling window
        // contains seven whole ones. This is the whole point of the change.
        val mondayMorning = at(2026, 9, 7, 8, 45)
        val calStart = WeekWindow.startOfWeekMs(mondayMorning)
        val rollStart = WeekWindow.rollingStartMs(mondayMorning)
        val calDaysCovered = ((mondayMorning - calStart) / 86_400_000L) + 1
        val rollDaysCovered = ((mondayMorning - rollStart) / 86_400_000L) + 1
        assertEquals("calendar week covers only today", 1L, calDaysCovered)
        assertEquals("rolling window covers seven days", 7L, rollDaysCovered)
    }
}
