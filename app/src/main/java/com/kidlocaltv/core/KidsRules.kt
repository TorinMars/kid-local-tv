package com.kidlocaltv.core

/**
 * The limits a parent sets. 0 minutes means the rule is off. Times of day are minutes after
 * midnight (20:30 = 1230).
 */
data class KidsRules(
    val dailyLimitMin: Int = 0,
    val breakEveryMin: Int = 0,
    val breakLengthMin: Int = 10,
    val bedtimeOn: Boolean = false,
    val bedtimeStartMin: Int = 20 * 60 + 30,
    val bedtimeEndMin: Int = 7 * 60,
)

/**
 * What has been watched. Times are wall-clock milliseconds (System.currentTimeMillis()).
 *
 * @property day the day [watchedTodayMs] belongs to ("2026-11-05"); a new day starts from zero.
 * @property sinceBreakMs watching since the last break, for the break rule.
 * @property breakUntil end of the break in progress, or 0.
 * @property lastWatchAt when video last played; a long enough pause counts as a break.
 * @property parentUntil a parent allowed watching until then, whatever the rules say; or 0.
 */
data class WatchState(
    val day: String = "",
    val watchedTodayMs: Long = 0,
    val sinceBreakMs: Long = 0,
    val breakUntil: Long = 0,
    val lastWatchAt: Long = 0,
    val parentUntil: Long = 0,
)

/** Whether a video may play now, and if not, why and until when. */
sealed interface Verdict {
    data object Allowed : Verdict
    /** The daily limit is used up; videos come back on the next day. */
    data object TimeUp : Verdict
    /** Bedtime hours; videos come back at [endMin] (minutes after midnight). */
    data class Bedtime(val endMin: Int) : Verdict
    /** A break; videos come back at [until] (wall-clock ms). */
    data class Break(val until: Long) : Verdict
}

/**
 * The parental rules, with no Android code so they can be unit tested. The caller passes the
 * current time as [now] (wall ms), [day] (local date) and [minuteOfDay] (local time).
 *
 * Order: a parent's "more time" beats everything; then bedtime, the daily limit, and breaks.
 */
object KidsGuard {

    fun check(rules: KidsRules, state: WatchState, now: Long, day: String, minuteOfDay: Int): Verdict {
        val s = forDay(state, day)
        if (s.parentUntil > now) return Verdict.Allowed
        if (rules.bedtimeOn && inBedtime(rules, minuteOfDay)) return Verdict.Bedtime(rules.bedtimeEndMin)
        if (rules.dailyLimitMin > 0 && s.watchedTodayMs >= rules.dailyLimitMin * MINUTE) return Verdict.TimeUp
        if (rules.breakEveryMin > 0 && s.breakUntil > now) {
            // Never longer than one break, even if the clock was moved back.
            return Verdict.Break(minOf(s.breakUntil, now + rules.breakLengthMin * MINUTE))
        }
        return Verdict.Allowed
    }

    /**
     * Adds [deltaMs] of watching that ended at [now]. Starts a break when the break rule says so.
     * A pause at least one break long (video paused, app closed) already counts as the break.
     * Watching in a parent's extra time counts for the day, but not towards the next break.
     */
    fun watched(rules: KidsRules, state: WatchState, deltaMs: Long, now: Long, day: String): WatchState {
        if (deltaMs <= 0) return state
        var s = forDay(state, day)
        val restedMs = if (s.lastWatchAt > 0) now - deltaMs - s.lastWatchAt else 0
        val parentAllows = s.parentUntil > now
        var sinceBreak = if (restedMs >= rules.breakLengthMin * MINUTE) 0 else s.sinceBreakMs
        if (!parentAllows) sinceBreak += deltaMs
        var breakUntil = s.breakUntil
        if (rules.breakEveryMin > 0 && sinceBreak >= rules.breakEveryMin * MINUTE && !parentAllows) {
            breakUntil = now + rules.breakLengthMin * MINUTE
            sinceBreak = 0
        }
        s = s.copy(
            watchedTodayMs = s.watchedTodayMs + deltaMs,
            sinceBreakMs = sinceBreak,
            breakUntil = breakUntil,
            lastWatchAt = now,
        )
        return s
    }

    /** A parent lets the child watch for [minutes] more from [now], and ends a break in progress. */
    fun giveTime(state: WatchState, minutes: Int, now: Long, day: String): WatchState {
        val s = forDay(state, day)
        val from = maxOf(now, s.parentUntil)
        return s.copy(parentUntil = from + minutes * MINUTE, breakUntil = 0, sinceBreakMs = 0)
    }

    /** A parent starts today over: no minutes watched, no break due or running. Bedtime still applies. */
    fun resetToday(state: WatchState, day: String): WatchState =
        forDay(state, day).copy(watchedTodayMs = 0, sinceBreakMs = 0, breakUntil = 0)

    fun inBedtime(rules: KidsRules, minuteOfDay: Int): Boolean {
        val start = rules.bedtimeStartMin
        val end = rules.bedtimeEndMin
        return when {
            start == end -> false
            start < end -> minuteOfDay in start until end
            else -> minuteOfDay >= start || minuteOfDay < end // over midnight, e.g. 20:30-07:00
        }
    }

    /** The state with today's counter reset when [day] is a new day. */
    fun forDay(state: WatchState, day: String): WatchState =
        if (state.day == day) state else state.copy(day = day, watchedTodayMs = 0)

    const val MINUTE = 60_000L
}
