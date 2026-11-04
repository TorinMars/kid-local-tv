package com.kidlocaltv.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.kidlocaltv.R
import com.kidlocaltv.core.VideoLibrary
import com.kidlocaltv.data.AppPrefs
import com.kidlocaltv.server.ImportServer
import com.kidlocaltv.server.NetworkInfo
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shown on first launch (and later via long-press OK / Menu on the player).
 * Runs the import web server and shows the address + QR code to open on a phone or computer.
 */
class SetupActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private var server: ImportServer? = null
    private var serverPort = -1
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var urlText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var countText: TextView
    private lateinit var doneButton: Button

    private val isManageMode: Boolean get() = intent.getBooleanExtra(EXTRA_MANAGE, false)

    /** Library scans read many files; on a slow TV they froze this screen when run on the main thread. */
    private val scanner: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanQueued = AtomicBoolean(false)
    /** Video count from the newest finished scan; -1 until the first scan is done. */
    private var lastCount = -1

    private val refreshTick = object : Runnable {
        override fun run() {
            refreshAddress()
            refreshCount()
            handler.postDelayed(this, 3_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        prefs = AppPrefs(this)

        urlText = findViewById(R.id.setup_url)
        qrImage = findViewById(R.id.setup_qr)
        statusText = findViewById(R.id.setup_status)
        countText = findViewById(R.id.setup_count)
        doneButton = findViewById(R.id.setup_done)

        findViewById<TextView>(R.id.setup_title).setText(
            if (isManageMode) R.string.manage_title else R.string.setup_title
        )
        doneButton.setText(if (isManageMode) R.string.back_to_watching else R.string.start_watching)
        doneButton.setOnClickListener { finishSetup() }
    }

    override fun onStart() {
        super.onStart()
        startServer()
        handler.post(refreshTick)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshTick)
        server?.stopServer()
        server = null
        super.onStop()
    }

    override fun onDestroy() {
        scanner.shutdownNow()
        super.onDestroy()
    }

    private fun startServer() {
        if (server != null) return
        val root = prefs.libraryRoot
        // Try a few ports in case 8080 is taken by another app on the TV.
        for (port in ImportServer.DEFAULT_PORT until ImportServer.DEFAULT_PORT + 10) {
            val candidate = ImportServer(
                port = port,
                root = root,
                sources = prefs.networkSources,
                page = { assets.open("web/index.html") },
                onLibraryChanged = { handler.post { refreshCount() } },
            )
            try {
                candidate.startServer()
                server = candidate
                serverPort = port
                return
            } catch (e: IOException) {
                Log.w(TAG, "Port $port unavailable", e)
            }
        }
        statusText.setText(R.string.server_failed)
    }

    private var lastUrl: String? = null

    private fun refreshAddress() {
        val ip = NetworkInfo.localIpAddress()
        if (server == null) return
        if (ip == null) {
            lastUrl = null
            urlText.text = "—"
            qrImage.visibility = View.INVISIBLE
            statusText.setText(R.string.no_network)
            return
        }
        val url = "http://$ip:$serverPort"
        statusText.setText(R.string.server_ready)
        if (url == lastUrl) return
        lastUrl = url
        urlText.text = url
        qrImage.setImageBitmap(QrCode.render(url, resources.getDimensionPixelSize(R.dimen.qr_size)))
        qrImage.visibility = View.VISIBLE
    }

    /** Scans in the background and shows the result; a scan already waiting to run covers this call. */
    private fun refreshCount() {
        if (!scanQueued.compareAndSet(false, true)) return
        try {
            scanner.execute {
                scanQueued.set(false)
                val library = prefs.scanLibrary()
                handler.post { if (!isDestroyed) showCount(library) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            scanQueued.set(false) // the screen is closing
        }
    }

    private fun showCount(library: VideoLibrary) {
        val count = library.size
        lastCount = count
        countText.text = if (count == 0) {
            getString(R.string.count_none)
        } else {
            resources.getQuantityString(R.plurals.count_videos, count, count, library.folders.size)
        }
        val wasEnabled = doneButton.isEnabled
        doneButton.isEnabled = count > 0
        if (!wasEnabled && doneButton.isEnabled) doneButton.requestFocus()
    }

    private fun finishSetup() {
        // The button is only enabled once a scan found videos; use that result instead of rescanning here.
        if (lastCount <= 0) return
        prefs.setupComplete = true
        if (!isManageMode) {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        finish()
    }

    companion object {
        private const val TAG = "SetupActivity"
        private const val EXTRA_MANAGE = "manage"

        fun intent(context: Context, manage: Boolean): Intent =
            Intent(context, SetupActivity::class.java).putExtra(EXTRA_MANAGE, manage)
    }
}
