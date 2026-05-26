package Kinetic_Eco.Tracker.services

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import Kinetic_Eco.Tracker.data.*

/**
 * Derives CO2 tier and cohort behavioral metrics purely from local session history.
 * No network calls; fast enough for every analytics load.
 *
 * Three questions answered:
 *  1. **Session patterns per tier** — do higher-tier users go shorter but more often?
 *     [CohortProfile.tierPhases] carries avg duration + sessions/week per tier phase.
 *
 *  2. **Fastest route to the next tier** — which activity earns CO2 savings fastest?
 *     [CohortProfile.activityRates] ranks activity types by kg CO2 saved per hour.
 *
 *  3. **Milestone timeline** — when did the user cross each threshold?
 *     [CohortProfile.milestones] records date + days-from-start for each tier crossing.
 */
object CohortAnalysisService {

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun compute(sessions: List<SessionStats>): CohortProfile {
        if (sessions.isEmpty()) return emptyProfile()

        val sorted = sessions.sortedBy { it.date }
        val totalCo2 = sorted.sumOf { it.co2Conserved }
        val tier = Co2Tier.forTotal(totalCo2)
        val nextKg = tier.nextThresholdKg
        val kgToNext = nextKg?.let { (it - totalCo2).coerceAtLeast(0.0) }
        val progress = when {
            nextKg == null -> 1.0
            else -> ((totalCo2 - tier.thresholdKg) / (nextKg - tier.thresholdKg)).coerceIn(0.0, 1.0)
        }

        return CohortProfile(
            currentTier = tier,
            totalCo2SavedKg = totalCo2,
            progressToNextTier = progress,
            kgToNextTier = kgToNext,
            tierPhases = buildTierPhases(sorted),
            activityRates = buildActivityRates(sorted),
            milestones = buildMilestones(sorted)
        )
    }

    // ── 1. Session patterns per tier phase ───────────────────────────────────

    /**
     * Tag each session with the tier the user was in at that moment (cumulative
     * CO2 *before* this session), then group consecutive same-tier sessions into
     * a phase and compute behavioral stats.
     */
    private fun buildTierPhases(sorted: List<SessionStats>): List<TierPhaseStats> {
        data class Tagged(val session: SessionStats, val tier: Co2Tier)

        var running = 0.0
        val tagged = sorted.map { s ->
            val t = Co2Tier.forTotal(running)
            running += s.co2Conserved
            Tagged(s, t)
        }

        val phases = mutableListOf<TierPhaseStats>()
        var start = 0
        for (i in 1..tagged.size) {
            val end = i == tagged.size
            val flip = !end && tagged[i].tier != tagged[start].tier
            if (end || flip) {
                phases.add(buildPhase(tagged[start].tier, tagged.subList(start, i).map { it.session }))
                start = i
            }
        }
        return phases
    }

    private fun buildPhase(tier: Co2Tier, sessions: List<SessionStats>): TierPhaseStats {
        val count = sessions.size
        val avgMin = sessions.sumOf { it.totalDuration } / count / 60.0

        val firstDate = parseDate(sessions.first().date)
        val lastDate  = parseDate(sessions.last().date)
        val phaseDays = daysBetween(firstDate, lastDate)?.plus(1) ?: 1
        val sessionsPerWeek = count * 7.0 / phaseDays.coerceAtLeast(1)
        val co2Saved = sessions.sumOf { it.co2Conserved }

        val activityCo2 = mutableMapOf<ActivityType, Double>()
        for (s in sessions) {
            for ((type, bd) in s.breakdown) {
                val saving = -CO2Factors.getFactor(type) * (bd.distance / 1000.0)
                if (saving > 0) activityCo2[type] = (activityCo2[type] ?: 0.0) + saving
            }
        }
        val topActivity = activityCo2.maxByOrNull { it.value }?.key

        return TierPhaseStats(tier, count, avgMin, sessionsPerWeek, phaseDays, co2Saved, topActivity)
    }

    // ── 2. CO2 rate per activity type ────────────────────────────────────────

    private fun buildActivityRates(sessions: List<SessionStats>): List<ActivityCo2Rate> {
        data class Accum(var co2Kg: Double = 0.0, var durationSec: Long = 0L, var count: Int = 0)
        val accum = mutableMapOf<ActivityType, Accum>()

        for (s in sessions) {
            for ((type, bd) in s.breakdown) {
                val saving = -CO2Factors.getFactor(type) * (bd.distance / 1000.0)
                if (saving > 0 && bd.time > 0) {
                    val a = accum.getOrPut(type) { Accum() }
                    a.co2Kg += saving
                    a.durationSec += bd.time
                    a.count++
                }
            }
        }

        return accum.map { (type, a) ->
            ActivityCo2Rate(
                activity = type,
                co2PerHour = if (a.durationSec > 0) a.co2Kg / (a.durationSec / 3600.0) else 0.0,
                totalCo2Kg = a.co2Kg,
                sessionCount = a.count
            )
        }.sortedByDescending { it.co2PerHour }
    }

    // ── 3. Tier milestone dates ───────────────────────────────────────────────

    private fun buildMilestones(sessions: List<SessionStats>): List<TierMilestone> {
        val firstDate = parseDate(sessions.first().date)
        val reached = mutableMapOf<Co2Tier, TierMilestone>()

        // Bronze is reached on the very first session (threshold = 0 kg)
        reached[Co2Tier.BRONZE] = TierMilestone(Co2Tier.BRONZE, sessions.first().date, 0, 1)

        var running = 0.0
        for ((idx, s) in sessions.withIndex()) {
            running += s.co2Conserved
            for (tier in listOf(Co2Tier.SILVER, Co2Tier.GOLD, Co2Tier.CHAMPION)) {
                if (tier !in reached && running >= tier.thresholdKg) {
                    val days = daysBetween(firstDate, parseDate(s.date))
                    reached[tier] = TierMilestone(tier, s.date, days, idx + 1)
                }
            }
        }

        return Co2Tier.entries.map { reached[it] ?: TierMilestone(it, null, null, null) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseDate(s: String): Date? = try { sdf.parse(s) } catch (_: Exception) { null }

    private fun daysBetween(from: Date?, to: Date?): Int? {
        if (from == null || to == null) return null
        return ((to.time - from.time) / 86_400_000L).toInt()
    }

    private fun emptyProfile() = CohortProfile(
        currentTier = Co2Tier.BRONZE,
        totalCo2SavedKg = 0.0,
        progressToNextTier = 0.0,
        kgToNextTier = Co2Tier.BRONZE.nextThresholdKg,
        tierPhases = emptyList(),
        activityRates = emptyList(),
        milestones = Co2Tier.entries.map { TierMilestone(it, null, null, null) }
    )
}