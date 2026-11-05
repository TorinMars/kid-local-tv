package com.kidlocaltv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kidlocaltv.R
import com.kidlocaltv.data.AppPrefs
import com.kidlocaltv.server.ImportServer
import com.kidlocaltv.server.NetworkSources
import com.kidlocaltv.server.SmbLink
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Adds and manages SMB network folders on the TV itself, with the remote and the TV's on-screen
 * keyboard: no phone needed. Does the same as the web page: saves the link and login, connects,
 * and lists the videos at once, so a wrong address or login shows straight away.
 *
 * Layout (1280 x 720 dp): header with a Back button; a card with the form on the left, a card
 * with the added folders on the right.
 */
class SourcesActivity : Activity() {

    private lateinit var sources: NetworkSources
    private val handler = Handler(Looper.getMainLooper())
    /** Connecting and scanning take seconds on a slow TV; never on the main thread. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var address: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var folder: EditText
    private lateinit var addButton: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sources = AppPrefs(this).networkSources

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(20))
        }

        // Header: title and intro on the left, Back on the right.
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(getString(R.string.sources_title), 32f, bold = true))
        titles.addView(text(getString(R.string.sources_intro), 15f, muted = true))
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(24) })
        button(header, getString(R.string.sources_done), width = 200) { finish() }
        page.addView(header)

        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // Left: the form.
        val form = card()
        cardTitle(form, getString(R.string.sources_add_title))
        address = field(form, R.string.sources_address, getString(R.string.sources_address_hint))
        user = field(form, R.string.sources_user, "")
        password = field(form, R.string.sources_password, "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        folder = field(form, R.string.sources_folder, getString(R.string.sources_folder_hint)).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        addButton = button(form, getString(R.string.sources_add), top = 16) { addSource() }
        status = text("", 17f).apply { setTextColor(getColor(R.color.accent)) }
        form.addView(status, params(top = 10))
        cards.addView(form, LinearLayout.LayoutParams(0, MATCH_PARENT, 1.15f))

        // Right: what is added already.
        val added = card()
        cardTitle(added, getString(R.string.sources_existing))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        added.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        cards.addView(added, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(24) })

        page.addView(cards, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        page.addView(text(getString(R.string.sources_keys), 15f, muted = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })

        setContentView(ScrollView(this).apply {
            setBackgroundResource(R.color.bg)
            isFillViewport = true
            addView(page)
        })
        address.requestFocus()
        refreshList()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun addSource() {
        val link = try {
            SmbLink.parse(SmbLink.typedOnTv(address.text.toString()))
                .withCredentials(user.text.toString(), password.text.toString(), null)
        } catch (e: IllegalArgumentException) {
            status.text = getString(R.string.sources_bad_address)
            address.requestFocus()
            return
        }
        val series = ImportServer.sanitizeFolder(folder.text.toString())
        status.text = getString(R.string.sources_connecting, link.toString())
        addButton.isEnabled = false
        background {
            val previous = sources.findSameLocation(link)
            val source = sources.add(link, series)
            val ok = sources.scanAdded(source, previous)
            val entries = sources.entries(source.id)
            val state = sources.status(source.id).state
            // A new folder that can't be opened isn't kept: the fields stay filled to fix and retry.
            if (!ok && previous == null) sources.remove(source.id)
            handler.post {
                if (isDestroyed) return@post
                addButton.isEnabled = true
                if (ok) {
                    status.text = getString(R.string.sources_added, entries.size, entries.map { it.folder }.distinct().size)
                    listOf(address, user, password, folder).forEach { it.text.clear() }
                } else {
                    status.text = getString(R.string.sources_failed, state.removePrefix("failed: "))
                }
                refreshList()
            }
        }
    }

    private class Row(val source: NetworkSources.Source, val videos: Int, val folders: Int, val state: String)

    /** Reads the saved shares off the main thread, then shows one block per share. */
    private fun refreshList() {
        background {
            val rows = sources.sources().map { src ->
                val entries = sources.entries(src.id)
                Row(src, entries.size, entries.map { it.folder }.distinct().size, sources.status(src.id).state)
            }
            handler.post { if (!isDestroyed) showList(rows) }
        }
    }

    private fun showList(rows: List<Row>) {
        list.removeAllViews()
        if (rows.isEmpty()) {
            list.addView(text(getString(R.string.sources_none), 18f, muted = true), params(top = 6))
            return
        }
        rows.forEachIndexed { i, row ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(ITEM_COLOR)
                }
                setPadding(dp(16), dp(12), dp(16), dp(14))
            }
            item.addView(text(row.source.link.toString(), 19f, bold = true).apply { setSingleLine() })
            val user = row.source.link.username.ifEmpty { getString(R.string.sources_guest) }
            item.addView(text(user, 15f, muted = true))

            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val color = when {
                row.state == "ok" -> DOT_OK
                row.state.startsWith("failed") -> DOT_FAILED
                else -> DOT_BUSY
            }
            line.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
            }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
            val summary = getString(R.string.sources_summary, row.videos, row.folders) +
                if (row.state == "ok") "" else "  ·  ${row.state}"
            line.addView(text(summary, 17f).apply { maxLines = 2 })
            item.addView(line, params(top = 8))

            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            item.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            button(buttons, getString(R.string.sources_rescan), weight = true, top = 12) {
                status.text = getString(R.string.sources_connecting, row.source.link.toString())
                background {
                    sources.scan(row.source.id)
                    val state = sources.status(row.source.id).state
                    handler.post {
                        if (isDestroyed) return@post
                        status.text = if (state == "ok") getString(R.string.sources_rescanned) else state
                        refreshList()
                    }
                }
            }
            var armed = false
            button(buttons, getString(R.string.sources_remove), weight = true, top = 12, start = 12) {
                // Press twice: removing a share by accident would empty the library.
                if (!armed) {
                    armed = true
                    (it as TextView).text = getString(R.string.sources_remove_confirm)
                } else {
                    background {
                        sources.remove(row.source.id)
                        handler.post { if (!isDestroyed) refreshList() }
                    }
                }
            }
            list.addView(item, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (i > 0) topMargin = dp(12)
            })
        }
    }

    // ---- helpers ----

    private fun background(task: () -> Unit) {
        try {
            worker.execute(task)
        } catch (e: RejectedExecutionException) {
            // The screen is closing.
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(24), dp(16), dp(24), dp(20))
    }

    private fun cardTitle(card: LinearLayout, title: String) {
        card.addView(text(title, 19f, bold = true).apply { setTextColor(getColor(R.color.accent)) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(6) })
    }

    private fun field(card: LinearLayout, label: Int, hintText: String): EditText {
        card.addView(text(getString(label), 15f, muted = true), params(top = 10))
        return EditText(this).apply {
            setSingleLine()
            hint = hintText
            setHintTextColor(getColor(R.color.muted))
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundResource(R.drawable.field_bg)
            setPadding(dp(16), 0, dp(16), 0)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // On this TV a text field kept the focus on Up/Down, so the remote could never leave
            // it. Move the focus ourselves.
            setOnKeyListener { v, keyCode, event ->
                val direction = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    else -> return@setOnKeyListener false
                }
                if (event.action == android.view.KeyEvent.ACTION_DOWN) v.focusSearch(direction)?.requestFocus()
                true
            }
            card.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply { topMargin = dp(4) })
        }
    }

    private fun button(
        parent: LinearLayout,
        label: String,
        width: Int = 0,
        weight: Boolean = false,
        top: Int = 0,
        start: Int = 0,
        onClick: (View) -> Unit,
    ): TextView = TextView(this, null, 0, R.style.TvButton).apply {
        text = label
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setOnClickListener(onClick)
        val lp = when {
            weight -> LinearLayout.LayoutParams(0, dp(50), 1f)
            width > 0 -> LinearLayout.LayoutParams(dp(width), dp(50))
            else -> LinearLayout.LayoutParams(MATCH_PARENT, dp(54))
        }
        parent.addView(this, lp.apply {
            topMargin = dp(top)
            marginStart = dp(start)
        })
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, muted: Boolean = false) =
        TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(getColor(if (muted) R.color.muted else R.color.text))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun params(top: Int) = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ITEM_COLOR = 0xFF232A3D.toInt()
        private const val DOT_OK = 0xFF43A047.toInt()
        private const val DOT_FAILED = 0xFFE53935.toInt()
        private const val DOT_BUSY = 0xFFFFB627.toInt()

        fun intent(context: Context): Intent = Intent(context, SourcesActivity::class.java)
    }
}
