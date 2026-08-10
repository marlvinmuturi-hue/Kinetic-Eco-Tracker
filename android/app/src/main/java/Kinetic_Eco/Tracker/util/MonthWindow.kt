package Kinetic_Eco.Tracker.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The app's single definition of "a month": local calendar month, 1st 00:00 to the
 * last day 23:59:59.999.
 *
 * Exists for the same reason [WeekWindow] does. The week definition was copy-pasted
 * into four places and drifted until the dashboard and Analysis disagreed about what
 * "this week" meant. Monthly statements are money documents — a user will compare them
 * against a bank statement — so there must be exactly one answer to where a month
 * starts, and it lives here.
 */
object MonthWindow {

    /** Local 1st-of-the-month 00:00:00.000 containing [nowMs]. */
    fun startOfMonthMs(nowMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Exclusive end of the month beginning at [monthStartMs].
     *
     * Computed by adding one calendar month rather than a fixed number of days, so
     * February, leap years and daylight-saving transitions all land correctly.
     */
    fun endOfMonthMs(monthStartMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = monthStartMs
        cal.add(Calendar.MONTH, 1)
        return cal.timeInMillis
    }

    /** Start of the month [offset] months away — negative for the past. */
    fun shiftMonths(monthStartMs: Long, offset: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = monthStartMs
        cal.add(Calendar.MONTH, offset)
        return cal.timeInMillis
    }

    /** The month a statement is normally *about*: the one that just finished. */
    fun previousMonthStartMs(nowMs: Long = System.currentTimeMillis()): Long =
        shiftMonths(startOfMonthMs(nowMs), -1)

    /** Localised label, e.g. "July 2026". */
    fun label(monthStartMs: Long): String =
        SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(java.util.Date(monthStartMs))

    /** `yyyy-MM`, for keys and logs. */
    fun key(monthStartMs: Long): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(java.util.Date(monthStartMs))

    /** True when [monthStartMs] is the month currently in progress — it is incomplete. */
    fun isCurrentMonth(monthStartMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        monthStartMs == startOfMonthMs(nowMs)
}