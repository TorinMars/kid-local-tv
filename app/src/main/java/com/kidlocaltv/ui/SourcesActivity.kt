package com.kidlocaltv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
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
    private lateinit var addButton: Button
    private lateinit var status: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sources = AppPrefs(this).networkSources

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(96), dp(32), dp(96), dp(32))
        }
        column.addView(text(getString(R.string.sources_title), 32f, bold = true))
        column.addView(text(getString(R.string.sources_intro), 18f, muted = true), params(top = 6))

        address = field(column, R.string.sources_address, getString(R.string.sources_address_hint))
        user = field(column, R.string.sources_user, "")
        password = field(column, R.string.sources_password, "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        folder = field(column, R.string.sources_folder, getString(R.string.sources_folder_hint)).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
        }

        addButton = button(column, getString(R.string.sources_add)) { addSource() }
        status = text("", 20f).apply { setTextColor(getColor(R.color.accent)) }
        column.addView(status, params(top = 10))

        column.addView(text(getString(R.string.sources_existing), 24f, bold = true), params(top = 24))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list, params(top = 4))
        button(column, getString(R.string.sources_done)) { finish() }

        setContentView(ScrollView(this).apply {
            setBackgroundResource(R.color.bg)
            addView(column)
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

    /** Reads the saved shares off the main thread, then shows one row per share. */
    private fun refreshList() {
        background {
            val rows = sources.sources().map { src ->
                Triple(src, sources.entries(src.id).size, sources.status(src.id).state)
            }
            handler.post { if (!isDestroyed) showList(rows) }
        }
    }

    private fun showList(rows: List<Triple<NetworkSources.Source, Int, String>>) {
        list.removeAllViews()
        if (rows.isEmpty()) {
            list.addView(text(getString(R.string.sources_none), 18f, muted = true), params(top = 6))
            return
        }
        for ((src, count, state) in rows) {
            val name = src.link.toString() + if (src.link.username.isEmpty()) "" else "  (${src.link.username})"
            list.addView(text(getString(R.string.sources_item, name, count, state), 18f), params(top = 12))
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            list.addView(buttons, params(top = 4))
            button(buttons, getString(R.string.sources_rescan), width = 220) {
                status.text = getString(R.string.sources_connecting, src.link.toString())
                background {
                    sources.scan(src.id)
                    handler.post {
                        if (isDestroyed) return@post
                        status.text = sources.status(src.id).state
                        refreshList()
                    }
                }
            }
            var armed = false
            button(buttons, getString(R.string.sources_remove), width = 220, start = 16) {
                // Press twice: removing a share by accident would empty the library.
                if (!armed) {
                    armed = true
                    (it as Button).text = getString(R.string.sources_remove) + " ?"
                } else {
                    background {
                        sources.remove(src.id)
                        handler.post { if (!isDestroyed) refreshList() }
                    }
                }
            }
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

    private fun field(column: LinearLayout, label: Int, hintText: String): EditText {
        column.addView(text(getString(label), 18f, muted = true), params(top = 14))
        return EditText(this).apply {
            setSingleLine()
            hint = hintText
            setHintTextColor(getColor(R.color.muted))
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setBackgroundResource(R.drawable.field_bg)
            setPadding(dp(18), 0, dp(18), 0)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            column.addView(this, LinearLayout.LayoutParams(dp(760), dp(56)).apply { topMargin = dp(4) })
        }
    }

    private fun button(parent: LinearLayout, label: String, width: Int = 760, start: Int = 0, onClick: (android.view.View) -> Unit): Button =
        Button(this, null, 0, R.style.TvButton).apply {
            text = label
            gravity = Gravity.CENTER
            setOnClickListener(onClick)
            parent.addView(this, LinearLayout.LayoutParams(dp(width), dp(56)).apply {
                topMargin = dp(12)
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
        fun intent(context: Context): Intent = Intent(context, SourcesActivity::class.java)
    }
}
