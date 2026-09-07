package Kinetic_Eco.Tracker.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The app's single definition of "this week": the local calendar week, Monday 00:00
 * through Sunday 23:59.
 *
 * This exists because the definition had been copy-pasted into four places
 * (`DashboardScreen`, `AnalysisScreen`, `SessionsListScreen`, `LeaderboardService` —
 * whose copy was even commented "Mirrors DashboardScreen's `thisWeekCutoff`") and then
 * drifted. The dashboard filtered to the calendar week but bucketed and labelled its
 * chart as a rolling 7 days, so on a Monday six of seven bars were structurally empty
 * no matter how active the user had been, and Analysis headlined a rolling 7-day total
 * against the dashboard's calendar-week one. Same data, different windows, no label
 * saying so — it read as the app deleting history every Monday.
 *
 * Anything shown to a user as "this week" must come from here. A rolling window is a
 * legitimate thing to display, but it must be labelled "last 7 days" and must not be
 * mixed into a calendar-week surface.
 */
object WeekWindow {

    /** Number of days in a week — the bucket count for any weekly chart. */
    const val DAYS = 7

    /**
     * Start of the rolling seven-day window ending today: local midnight, six days back.
     *
     * This is what every user-facing "recent activity" surface uses. The calendar week is
     * honest but empties itself every Monday at 00:00 — the dashboard, the weekly report and
     * the sessions list all went blank at once, which reads as the app having lost the user's
     * history rather than as a week beginning. A rolling window always covers seven days of
     * real activity, so there is no cliff.
     *
     * Day-aligned rather than `now - 7 days` so the seven chart buckets are whole days and
     * "today" is always the last one, instead of every bucket straddling two dates.
     *
     * The calendar-week functions remain for the leaderboard, which needs a window shared by
     * every user to rank them fairly — see [startOfWeekMs].
     */
    fun rollingStartMs(nowMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -(DAYS - 1))
        return cal.timeInMillis
    }

    /**
     * Bucket index for [timestampMs] within the seven-day window beginning [windowStartMs]:
     * 0 = the oldest day, 6 = today. Null when the timestamp falls outside the window.
     *
     * Counts whole calendar days rather than dividing by 86 400 000, so a daylight-saving
     * transition inside the window cannot shift every later day by one.
     */
    fun dayIndexInWindow(timestampMs: Long, windowStartMs: Long): Int? {
        if (timestampMs < windowStartMs) return null
        val cursor = midnight(windowStartMs)
        val target = midnight(timestampMs)
        var idx = 0
        while (idx < DAYS) {
            // Does `target` fall inside the day starting at `cursor`?
            val next = (cursor.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            if (target.timeInMillis < next.timeInMillis) return idx
            cursor.add(Calendar.DAY_OF_YEAR, 1)
            idx++
        }
        return null   // beyond the window's last day
    }

    private fun midnight(ms: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** Local Monday 00:00:00.000 of the calendar week containing [nowMs]. */
    fun startOfWeekMs(nowMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val daysFromMonday = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        cal.add(Calendar.DAY_OF_YEAR, -daysFromMonday)
        return cal.timeInMillis
    }

    /**
     * Bucket index for [timestampMs] within the week beginning [weekStartMs]:
     * 0 = Monday … 6 = Sunday. Null when the timestamp falls outside that week.
     *
     * Computed from calendar fields rather than `(t - weekStart) / 86_400_000` so a
     * daylight-saving transition inside the week cannot shift every later day by one.
     */
    fun dayIndexInWeek(timestampMs: Long, weekStartMs: Long): Int? {
        if (timestampMs < weekStartMs) return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val index = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        // Guard against a timestamp a week or more ahead of weekStart landing in a
        // valid-looking slot (e.g. a future-dated session, or stale `now`).
        return if (timestampMs < startOfWeekMs(weekStartMs) + WEEK_MS) index else null
    }

    /** Index of today within the current week: 0 = Monday … 6 = Sunday. */
    fun todayIndex(nowMs: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        return (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
    }

    /**
     * Localised weekday labels, Monday-first, for chart axes.
     * [pattern] is a `SimpleDateFormat` day pattern — "EEE" for "Mon", "EEEEE" for "M".
     */
    fun weekdayLabels(pattern: String, weekStartMs: Long = startOfWeekMs()): List<String> {
        val fmt = SimpleDateFormat(pattern, Locale.getDefault())
        val cal = Calendar.getInstance()
        return (0 until DAYS).map { offset ->
            cal.timeInMillis = weekStartMs
            cal.add(Calendar.DAY_OF_YEAR, offset)
            fmt.format(cal.time)
        }
    }

    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
}