package com.kidlocaltv.data

import android.content.Context
import com.kidlocaltv.core.KidsGuard
import com.kidlocaltv.core.KidsRules
import com.kidlocaltv.core.Verdict
import com.kidlocaltv.core.WatchState
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Parental rules, watch time and the parent PIN, in app-private storage.
 * The PIN is kept only as a salted SHA-256 hash. A forgotten PIN is reset by clearing the app's data.
 */
class KidsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("kids", Context.MODE_PRIVATE)

    var rules: KidsRules
        get() = KidsRules(
            dailyLimitMin = prefs.getInt("daily_limit", 0),
            breakEveryMin = prefs.getInt("break_every", 0),
            breakLengthMin = prefs.getInt("break_length", 10),
            bedtimeOn = prefs.getBoolean("bedtime_on", false),
            bedtimeStartMin = prefs.getInt("bedtime_start", 20 * 60 + 30),
            bedtimeEndMin = prefs.getInt("bedtime_end", 7 * 60),
        )
        set(r) {
            prefs.edit()
                .putInt("daily_limit", r.dailyLimitMin)
                .putInt("break_every", r.breakEveryMin)
                .putInt("break_length", r.breakLengthMin)
                .putBoolean("bedtime_on", r.bedtimeOn)
                .putInt("bedtime_start", r.bedtimeStartMin)
                .putInt("bedtime_end", r.bedtimeEndMin)
                .apply()
        }

    var state: WatchState
        get() = WatchState(
            day = prefs.getString("day", "") ?: "",
            watchedTodayMs = prefs.getLong("watched_today", 0),
            sinceBreakMs = prefs.getLong("since_break", 0),
            breakUntil = prefs.getLong("break_until", 0),
            lastWatchAt = prefs.getLong("last_watch", 0),
            parentUntil = prefs.getLong("parent_until", 0),
        )
        set(s) {
            prefs.edit()
                .putString("day", s.day)
                .putLong("watched_today", s.watchedTodayMs)
                .putLong("since_break", s.sinceBreakMs)
                .putLong("break_until", s.breakUntil)
                .putLong("last_watch", s.lastWatchAt)
                .putLong("parent_until", s.parentUntil)
                .apply()
        }

    /** The rules applied to now. */
    fun verdict(now: Long = System.currentTimeMillis()): Verdict =
        KidsGuard.check(rules, state, now, dayOf(now), minuteOfDay(now))

    /** Adds watching time that ended at [now]. */
    fun addWatched(deltaMs: Long, now: Long = System.currentTimeMillis()) {
        state = KidsGuard.watched(rules, state, deltaMs, now, dayOf(now))
    }

    fun giveTime(minutes: Int, now: Long = System.currentTimeMillis()) {
        state = KidsGuard.giveTime(state, minutes, now, dayOf(now))
    }

    fun resetToday(now: Long = System.currentTimeMillis()) {
        state = KidsGuard.resetToday(state, dayOf(now))
    }

    fun watchedTodayMs(now: Long = System.currentTimeMillis()): Long =
        KidsGuard.forDay(state, dayOf(now)).watchedTodayMs

    // ---- PIN ----

    val hasPin: Boolean get() = prefs.contains("pin_hash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        prefs.edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString("pin_salt", null) ?: return false
        val expected = prefs.getString("pin_hash", null) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), hash(salt, pin).toByteArray())
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        fun dayOf(now: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

        fun minuteOfDay(now: Long): Int {
            val c = Calendar.getInstance().apply { timeInMillis = now }
            return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        }

        fun clock(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
    }
}
