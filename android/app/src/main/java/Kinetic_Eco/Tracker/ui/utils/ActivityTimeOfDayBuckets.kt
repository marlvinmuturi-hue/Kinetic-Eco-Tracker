package Kinetic_Eco.Tracker.ui.utils

import Kinetic_Eco.Tracker.data.SessionStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Splits tracked time for **one local calendar day** ([dateKey] = `yyyy-MM-dd`) across four
 * 6 h buckets (0–6 … 18–24). Only the part of each session that overlaps that day is counted
 * (sessions crossing midnight are clipped).
 */
fun aggregateActivityMsInSixHourBinsForLocalDay(sessions: List<SessionStats>, dateKey: String): LongArray {
    val buckets = LongArray(4)
    val zone = ZoneId.systemDefault()
    val localDate = LocalDate.parse(dateKey)
    val dayStartMs = localDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEndMs = localDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    for (s in sessions) {
        if (s.totalDuration <= 0L) continue
        val endMs = s.sessionEndTimeMs
        if (endMs <= 0L) continue
        val startMs = endMs - s.totalDuration * 1000L
        val clipped = clipIntervalToRange(startMs, endMs, dayStartMs, dayEndMs) ?: continue
        distributeIntervalToBins(clipped.first, clipped.second, buckets)
    }
    return buckets
}

private fun clipIntervalToRange(
    startMs: Long,
    endMs: Long,
    rangeStart: Long,
    rangeEnd: Long
): Pair<Long, Long>? {
    val a = maxOf(startMs, rangeStart)
    val b = minOf(endMs, rangeEnd)
    return if (b > a) a to b else null
}

private fun distributeIntervalToBins(startMs: Long, endMs: Long, buckets: LongArray) {
    if (endMs <= startMs) return
    var cur = startMs
    val zone = ZoneId.systemDefault()
    while (cur < endMs) {
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(cur), zone)
        val hour = zdt.hour
        val bin = hour / 6
        val nextBoundaryMs = nextSixHourBoundaryEpochMs(zdt, zone)
        val chunkEnd = minOf(endMs, nextBoundaryMs)
        buckets[bin] += chunkEnd - cur
        cur = chunkEnd
    }
}

private fun nextSixHourBoundaryEpochMs(zdt: ZonedDateTime, zone: ZoneId): Long {
    val hour = zdt.hour
    val bin = hour / 6
    val nextHour = (bin + 1) * 6
    return if (nextHour < 24) {
        zdt.toLocalDate().atTime(nextHour, 0).atZone(zone).toInstant().toEpochMilli()
    } else {
        zdt.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
