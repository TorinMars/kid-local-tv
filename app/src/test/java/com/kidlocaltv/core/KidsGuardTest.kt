package com.kidlocaltv.core

import com.kidlocaltv.core.KidsGuard.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsGuardTest {
    private val day = "2026-11-05"
    private val noon = 12 * 60
    private val t0 = 1_000_000_000L

    private fun watch(rules: KidsRules, state: WatchState, minutes: Int, start: Long): WatchState {
        var s = state
        var now = start
        repeat(minutes) {
            now += MINUTE
            s = KidsGuard.watched(rules, s, MINUTE, now, day)
        }
        return s
    }

    @Test
    fun noRulesAlwaysAllows() {
        val s = watch(KidsRules(), WatchState(), 600, t0)
        assertEquals(Verdict.Allowed, KidsGuard.check(KidsRules(), s, t0 + 600 * MINUTE, day, noon))
    }

    @Test
    fun dailyLimitStopsAndResetsNextDay() {
        val rules = KidsRules(dailyLimitMin = 30)
        val s29 = watch(rules, WatchState(), 29, t0)
        assertEquals(Verdict.Allowed, KidsGuard.check(rules, s29, t0 + 29 * MINUTE, day, noon))
        val s30 = KidsGuard.watched(rules, s29, MINUTE, t0 + 30 * MINUTE, day)
        assertEquals(Verdict.TimeUp, KidsGuard.check(rules, s30, t0 + 30 * MINUTE, day, noon))
        assertEquals(Verdict.Allowed, KidsGuard.check(rules, s30, t0 + 30 * MINUTE, "2026-11-06", 8 * 60))
    }

    @Test
    fun breakStartsAfterWatchingAndEnds() {
        val rules = KidsRules(breakEveryMin = 20, breakLengthMin = 5)
        val s = watch(rules, WatchState(), 20, t0)
        val now = t0 + 20 * MINUTE
        assertEquals(Verdict.Break(now + 5 * MINUTE), KidsGuard.check(rules, s, now, day, noon))
        assertEquals(Verdict.Allowed, KidsGuard.check(rules, s, now + 5 * MINUTE, day, noon))
    }

    @Test
    fun longPauseCountsAsBreak() {
        val rules = KidsRules(breakEveryMin = 20, breakLengthMin = 5)
        val s15 = watch(rules, WatchState(), 15, t0)
        // Ten minutes away from the TV, then ten more minutes of watching: no break yet.
        val back = watch(rules, s15, 10, t0 + 25 * MINUTE)
        assertEquals(10 * MINUTE, back.sinceBreakMs)
        assertEquals(Verdict.Allowed, KidsGuard.check(rules, back, t0 + 35 * MINUTE, day, noon))
    }

    @Test
    fun bedtimeOverMidnight() {
        val rules = KidsRules(bedtimeOn = true, bedtimeStartMin = 20 * 60 + 30, bedtimeEndMin = 7 * 60)
        assertTrue(KidsGuard.inBedtime(rules, 21 * 60))
        assertTrue(KidsGuard.inBedtime(rules, 0))
        assertTrue(KidsGuard.inBedtime(rules, 6 * 60 + 59))
        assertFalse(KidsGuard.inBedtime(rules, 7 * 60))
        assertFalse(KidsGuard.inBedtime(rules, 20 * 60 + 29))
        assertEquals(Verdict.Bedtime(7 * 60), KidsGuard.check(rules, WatchState(), t0, day, 22 * 60))
        assertFalse(KidsGuard.inBedtime(KidsRules(bedtimeOn = true, bedtimeStartMin = 600, bedtimeEndMin = 600), 600))
    }

    @Test
    fun bedtimeBeatsTheOtherRules() {
        val rules = KidsRules(dailyLimitMin = 10, bedtimeOn = true)
        val s = watch(rules, WatchState(), 10, t0)
        assertEquals(Verdict.Bedtime(rules.bedtimeEndMin), KidsGuard.check(rules, s, t0 + 10 * MINUTE, day, 21 * 60))
    }

    @Test
    fun parentTimeBeatsEverythingThenRulesReturn() {
        val rules = KidsRules(dailyLimitMin = 10, breakEveryMin = 5, bedtimeOn = true)
        var s = watch(rules, WatchState(), 10, t0)
        val now = t0 + 10 * MINUTE
        s = KidsGuard.giveTime(s, 15, now, day)
        assertEquals(Verdict.Allowed, KidsGuard.check(rules, s, now, day, 21 * 60))
        // Watching during parent time starts no break.
        s = watch(rules, s, 14, now)
        assertEquals(0L, s.breakUntil)
        assertEquals(0L, s.sinceBreakMs)
        assertEquals(24 * MINUTE, s.watchedTodayMs)
        assertEquals(Verdict.Allowed, KidsGuard.check(rules, s, now + 14 * MINUTE, day, noon))
        assertEquals(Verdict.TimeUp, KidsGuard.check(rules, s, now + 15 * MINUTE, day, noon))
    }

    @Test
    fun breakNeverLongerThanOneBreakAfterClockChange() {
        val rules = KidsRules(breakEveryMin = 20, breakLengthMin = 5)
        val s = WatchState(day = day, breakUntil = t0 + 600 * MINUTE)
        assertEquals(Verdict.Break(t0 + 5 * MINUTE), KidsGuard.check(rules, s, t0, day, noon))
    }
}
