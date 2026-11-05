package com.kidlocaltv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kidlocaltv.R
import com.kidlocaltv.core.KidsRules
import com.kidlocaltv.core.Verdict
import com.kidlocaltv.data.KidsStore
import java.util.Date

/**
 * The parent's screen, opened from the player by holding OK or pressing Menu.
 *
 * First a 4-digit PIN (chosen here the first time), entered with the D-pad or the number keys.
 * Then the settings: daily watch time, breaks, bedtime, extra time now, the manage screen and
 * changing the PIN. Every change is saved at once; the player applies it when it comes back.
 */
class ParentActivity : Activity() {

    private enum class Stage { ENTER, CREATE, CONFIRM, SETTINGS }

    private lateinit var kids: KidsStore
    private lateinit var root: FrameLayout
    private var stage = Stage.ENTER

    // PIN entry
    private val digits = IntArray(PIN_LENGTH)
    private val touched = BooleanArray(PIN_LENGTH)
    private var pos = 0
    private var firstPin = ""
    private var wrongTries = 0
    private var lockedUntil = 0L
    private var boxes: List<TextView> = emptyList()
    private var pinTitle: TextView? = null
    private var pinMessage: TextView? = null

    // Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kids = KidsStore(this)
        root = FrameLayout(this).apply { setBackgroundResource(R.color.bg) }
        setContentView(root)
        showPin(if (kids.hasPin) Stage.ENTER else Stage.CREATE, null)
    }

    // ---- PIN ----

    private fun showPin(next: Stage, message: String?) {
        stage = next
        digits.fill(0)
        touched.fill(false)
        pos = 0
        root.removeAllViews()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        pinTitle = text(
            getString(
                when (next) {
                    Stage.CREATE -> R.string.pin_create
                    Stage.CONFIRM -> R.string.pin_confirm
                    else -> R.string.pin_enter
                }
            ), 34f, bold = true
        ).also { column.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        boxes = List(PIN_LENGTH) {
            text("", 44f, bold = true).apply {
                gravity = Gravity.CENTER
                row.addView(this, LinearLayout.LayoutParams(dp(88), dp(104)).apply { setMargins(dp(10), 0, dp(10), 0) })
            }
        }
        column.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(36) })

        pinMessage = text(message ?: "", 22f).apply { setTextColor(getColor(R.color.accent)) }
        column.addView(pinMessage, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        column.addView(text(getString(R.string.pin_hint), 18f, muted = true),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        if (next == Stage.CREATE) {
            column.addView(text(getString(R.string.pin_create_note), 16f, muted = true),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        root.addView(column, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        drawBoxes()
    }

    private fun drawBoxes() {
        boxes.forEachIndexed { i, box ->
            box.text = when {
                i == pos -> digits[i].toString()
                touched[i] -> "•"
                else -> "–"
            }
            // The box being edited is highlighted like a focused button; the others are plain.
            box.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (i == pos) getColor(R.color.accent) else BOX_COLOR)
            }
            box.setTextColor(if (i == pos) 0xFF1B1300.toInt() else getColor(R.color.text))
        }
    }

    private fun onPinKey(keyCode: Int): Boolean {
        if (SystemClock.elapsedRealtime() < lockedUntil) {
            showLockMessage()
            return true
        }
        val number = when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        when {
            number >= 0 -> { digits[pos] = number; touched[pos] = true; advance() }
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> { digits[pos] = (digits[pos] + 1) % 10; touched[pos] = true }
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { digits[pos] = (digits[pos] + 9) % 10; touched[pos] = true }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> if (pos > 0) pos--
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> if (pos < PIN_LENGTH - 1) { touched[pos] = true; pos++ }
            isOk(keyCode) -> { touched[pos] = true; advance() }
            else -> return false
        }
        if (stage != Stage.SETTINGS) drawBoxes()
        return true
    }

    private fun advance() {
        if (pos < PIN_LENGTH - 1) {
            pos++
            return
        }
        val pin = digits.joinToString("")
        when (stage) {
            Stage.ENTER -> if (kids.checkPin(pin)) {
                wrongTries = 0
                showSettings()
            } else {
                wrongTries++
                if (wrongTries >= MAX_TRIES) {
                    wrongTries = 0
                    lockedUntil = SystemClock.elapsedRealtime() + LOCK_MS
                    showPin(Stage.ENTER, null)
                    showLockMessage()
                } else {
                    showPin(Stage.ENTER, getString(R.string.pin_wrong))
                }
            }
            Stage.CREATE -> {
                firstPin = pin
                showPin(Stage.CONFIRM, null)
            }
            Stage.CONFIRM -> if (pin == firstPin) {
                kids.setPin(pin)
                firstPin = ""
                showSettings()
            } else {
                showPin(Stage.CREATE, getString(R.string.pin_mismatch))
            }
            Stage.SETTINGS -> Unit
        }
    }

    private fun showLockMessage() {
        val seconds = ((lockedUntil - SystemClock.elapsedRealtime()) / 1000 + 1).toInt()
        pinMessage?.text = getString(R.string.pin_locked, seconds)
    }

    // ---- settings ----

    /** Rows and labels that change when a setting or the status changes. */
    private val refreshers = mutableListOf<() -> Unit>()
    private var statusChip: TextView? = null
    private var watchedText: TextView? = null
    private var limitText: TextView? = null
    private var extraText: TextView? = null
    private var meter: MeterView? = null

    /**
     * Layout, for a 1280 x 720 dp TV:
     *  header (title, status chip) · "Today" card with a meter · a bar of actions (◀ ▶ move
     *  between them) · two cards of settings (▲ ▼ move, ◀ ▶ change the value).
     */
    private fun showSettings() {
        stage = Stage.SETTINGS
        root.removeAllViews()
        boxes = emptyList()
        refreshers.clear()

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(20))
        }

        // Header: title on the left, live status on the right.
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(getString(R.string.parent_title), 32f, bold = true))
        titles.addView(text(getString(R.string.parent_subtitle), 15f, muted = true))
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        statusChip = text("", 17f, bold = true).apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setTextColor(0xFFFFFFFF.toInt())
        }
        header.addView(statusChip)
        page.addView(header)

        // Today: minutes watched and a meter towards the daily limit.
        val today = card()
        val todayRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val todayTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        todayTexts.addView(text(getString(R.string.parent_today), 14f, muted = true))
        watchedText = text("", 26f, bold = true).also { todayTexts.addView(it) }
        todayRow.addView(todayTexts, LinearLayout.LayoutParams(dp(260), WRAP_CONTENT))
        val meterColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        meter = MeterView(this).also { meterColumn.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, dp(14))) }
        val meterTexts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        limitText = text("", 15f, muted = true).also { meterTexts.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        extraText = text("", 15f).apply { setTextColor(getColor(R.color.accent)) }.also { meterTexts.addView(it) }
        meterColumn.addView(meterTexts, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        todayRow.addView(meterColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        today.addView(todayRow)
        page.addView(today, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })

        // Actions: one row of buttons.
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val give = actionButton(actions, getString(R.string.parent_give_short), first = true) {
            kids.giveTime(GIVE_MINUTES)
            refreshAll()
        }
        actionButton(actions, getString(R.string.parent_reset_today)) {
            kids.resetToday()
            refreshAll()
        }
        actionButton(actions, getString(R.string.parent_manage)) {
            startActivity(SetupActivity.intent(this, manage = true))
            finish()
        }
        actionButton(actions, getString(R.string.parent_sources)) { startActivity(SourcesActivity.intent(this)) }
        actionButton(actions, getString(R.string.parent_change_pin)) { showPin(Stage.CREATE, null) }
        actionButton(actions, getString(R.string.parent_back)) { finish() }
        page.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).apply { topMargin = dp(18) })

        // Two cards of settings, side by side.
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val time = card()
        cardTitle(time, R.string.parent_card_time)
        val firstRow = choice(time, R.string.parent_daily, DAILY_CHOICES, { it.dailyLimitMin }, ::minutes) { r, v -> r.copy(dailyLimitMin = v) }
        choice(time, R.string.parent_break_every, BREAK_EVERY_CHOICES, { it.breakEveryMin }, ::minutes) { r, v -> r.copy(breakEveryMin = v) }
        choice(time, R.string.parent_break_length, BREAK_LENGTH_CHOICES, { it.breakLengthMin }, ::minutes,
            dimWhen = { it.breakEveryMin == 0 }) { r, v -> r.copy(breakLengthMin = v) }
        time.addView(text(getString(R.string.parent_break_note), 14f, muted = true), params(top = 8, start = 16))
        cards.addView(time, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))

        val bed = card()
        cardTitle(bed, R.string.parent_card_bedtime)
        choice(bed, R.string.parent_bedtime, listOf(0, 1), { if (it.bedtimeOn) 1 else 0 },
            { getString(if (it == 1) R.string.parent_on else R.string.parent_off) }) { r, v -> r.copy(bedtimeOn = v == 1) }
        choice(bed, R.string.parent_bedtime_start, TIME_CHOICES, { it.bedtimeStartMin }, KidsStore::clock,
            dimWhen = { !it.bedtimeOn }) { r, v -> r.copy(bedtimeStartMin = v) }
        choice(bed, R.string.parent_bedtime_end, TIME_CHOICES, { it.bedtimeEndMin }, KidsStore::clock,
            dimWhen = { !it.bedtimeOn }) { r, v -> r.copy(bedtimeEndMin = v) }
        bed.addView(text(getString(R.string.parent_bedtime_note), 14f, muted = true), params(top = 8, start = 16))
        cards.addView(bed, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(24) })
        page.addView(cards, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })

        page.addView(text(getString(R.string.parent_keys), 15f, muted = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.addView(page)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        refreshAll()
        firstRow.requestFocus()
        give.nextFocusDownId = firstRow.id
    }

    private fun refreshAll() {
        refreshers.forEach { it() }
        val now = System.currentTimeMillis()
        val rules = kids.rules
        val watched = (kids.watchedTodayMs(now) / 60_000).toInt()
        watchedText?.text = getString(R.string.parent_watched_big, watched)
        if (rules.dailyLimitMin > 0) {
            limitText?.text = getString(R.string.parent_of_limit, rules.dailyLimitMin)
            meter?.set(watched.toFloat() / rules.dailyLimitMin)
        } else {
            limitText?.text = getString(R.string.parent_no_limit)
            meter?.set(-1f)
        }
        val parentUntil = kids.state.parentUntil
        extraText?.text = if (parentUntil > now) getString(R.string.parent_extra_until, timeOf(parentUntil)) else ""

        val (label, color) = when (val v = kids.verdict(now)) {
            Verdict.Allowed -> getString(R.string.parent_now_allowed) to CHIP_OK
            Verdict.TimeUp -> getString(R.string.parent_now_time_up) to CHIP_STOP
            is Verdict.Bedtime -> getString(R.string.parent_now_bedtime, KidsStore.clock(v.endMin)) to CHIP_NIGHT
            is Verdict.Break -> getString(R.string.parent_now_break, timeOf(v.until)) to CHIP_BREAK
        }
        statusChip?.text = "●  $label"
        statusChip?.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(color)
        }
    }

    /**
     * A setting row: label on the left, "◀ value ▶" on the right; Left/Right change it, saved at
     * once. [dimWhen] fades the row when it has no effect (e.g. bedtime times while bedtime is off).
     */
    private fun choice(
        card: LinearLayout,
        label: Int,
        options: List<Int>,
        get: (KidsRules) -> Int,
        show: (Int) -> String,
        dimWhen: (KidsRules) -> Boolean = { false },
        set: (KidsRules, Int) -> KidsRules,
    ): View {
        val row = LinearLayout(this).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(16), 0, dp(10), 0)
        }
        val name = text(getString(label), 20f).apply {
            setTextColor(getColorStateList(R.color.button_text))
            isDuplicateParentStateEnabled = true
        }
        row.addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val value = text("", 19f, bold = true).apply {
            setTextColor(getColorStateList(R.color.button_text))
            setBackgroundResource(R.drawable.pill_bg)
            isDuplicateParentStateEnabled = true
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(5), dp(16), dp(5))
            minWidth = dp(170)
        }
        row.addView(value)
        card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).apply { topMargin = dp(4) })

        refreshers += {
            val rules = kids.rules
            value.text = "◀   ${show(get(rules))}   ▶"
            row.alpha = if (dimWhen(rules)) 0.45f else 1f
        }
        row.setOnKeyListener { _, keyCode, event ->
            val step = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                else -> 0
            }
            if (step == 0) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                val current = get(kids.rules)
                // Nearest option to the stored value, then one step; wraps around at the ends.
                val index = options.indices.minByOrNull { kotlin.math.abs(options[it] - current) } ?: 0
                kids.rules = set(kids.rules, options[(index + step).mod(options.size)])
                refreshAll()
            }
            true
        }
        return row
    }

    private fun actionButton(bar: LinearLayout, label: String, first: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this, null, 0, R.style.TvButton).apply {
            id = View.generateViewId()
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
            bar.addView(this, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                if (!first) marginStart = dp(12)
            })
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }

    private fun cardTitle(card: LinearLayout, title: Int) {
        card.addView(text(getString(title), 19f, bold = true).apply { setTextColor(getColor(R.color.accent)) },
            params(top = 0, start = 16).apply { bottomMargin = dp(6) })
    }

    private fun params(top: Int, start: Int = 0) =
        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(top); marginStart = dp(start) }

    /** A rounded bar: [fraction] of today's limit used; red once it is used up; empty with no limit. */
    private class MeterView(context: Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val box = android.graphics.RectF()
        private var fraction = -1f

        fun set(value: Float) {
            fraction = value
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = height / 2f
            paint.color = 0xFF2C3449.toInt()
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(box, r, r, paint)
            if (fraction <= 0f) return
            paint.color = if (fraction >= 1f) 0xFFE53935.toInt() else 0xFFFFB627.toInt()
            box.set(0f, 0f, maxOf(height.toFloat(), width * fraction.coerceAtMost(1f)), height.toFloat())
            canvas.drawRoundRect(box, r, r, paint)
        }
    }

    // ---- keys ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (stage != Stage.SETTINGS && keyCode != KeyEvent.KEYCODE_BACK && onPinKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun isOk(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    // ---- helpers ----

    private fun minutes(value: Int): String =
        if (value == 0) getString(R.string.parent_off) else getString(R.string.parent_minutes, value)

    private fun timeOf(wallMs: Long): String = DateFormat.getTimeFormat(this).format(Date(wallMs))

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, muted: Boolean = false) =
        TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(getColor(if (muted) R.color.muted else R.color.text))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PIN_LENGTH = 4
        private const val BOX_COLOR = 0xFF2C3449.toInt()
        private const val CHIP_OK = 0xFF2E7D32.toInt()
        private const val CHIP_BREAK = 0xFFEF6C00.toInt()
        private const val CHIP_STOP = 0xFFC62828.toInt()
        private const val CHIP_NIGHT = 0xFF3949AB.toInt()
        private const val MAX_TRIES = 5
        private const val LOCK_MS = 60_000L
        private const val GIVE_MINUTES = 15
        private val DAILY_CHOICES = listOf(0, 15, 30, 45, 60, 90, 120, 150, 180, 240)
        private val BREAK_EVERY_CHOICES = listOf(0, 10, 15, 20, 30, 45, 60)
        private val BREAK_LENGTH_CHOICES = listOf(5, 10, 15, 20, 30)
        private val TIME_CHOICES = (0 until 24 * 60 step 15).toList()

        fun intent(context: Context): Intent = Intent(context, ParentActivity::class.java)
    }
}
