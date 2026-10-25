package sk.brock.nahravac

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private sealed class ChannelState {
        object Loading : ChannelState()
        data class Ok(val episodes: List<Episode>) : ChannelState()
        data class Error(val message: String) : ChannelState()
    }

    private companion object {
        const val REQ_NOTIFICATIONS = 1
        const val REQ_STORAGE = 2
        const val EPISODES_PER_CHANNEL = 3
    }

    private lateinit var container: LinearLayout
    private var channels: List<Channel> = emptyList()
    private var channelsError: String? = null
    private val states = LinkedHashMap<String, ChannelState>()
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private var pendingDownload: Episode? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id <= 0 || !isOurDownload(id)) return
            val ok = Downloads.status(this@MainActivity, id) == DownloadManager.STATUS_SUCCESSFUL
            toast(getString(if (ok) R.string.toast_download_done else R.string.toast_download_failed))
            render()
        }
    }

    // ------------------------------------------------------------------
    // Životný cyklus
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnA11ySettings).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btnCalibrate).setOnClickListener { requestDump() }
        findViewById<Button>(R.id.btnShareDump).setOnClickListener { shareLastDump() }

        askNotificationPermission()
        try {
            channels = Repo.loadChannels(this)
        } catch (e: Exception) {
            channelsError = e.message ?: e.javaClass.simpleName
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        render() // stav sťahovania sa mohol zmeniť, kým appka bola na pozadí
    }

    override fun onPause() {
        unregisterReceiver(downloadReceiver)
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Načítanie a vykreslenie
    // ------------------------------------------------------------------

    private fun refresh() {
        states.clear()
        channels.forEach { states[it.name] = ChannelState.Loading }
        render()
        channels.forEach { ch ->
            executor.execute {
                val state: ChannelState = try {
                    ChannelState.Ok(Repo.fetchEpisodes(ch, EPISODES_PER_CHANNEL))
                } catch (e: Exception) {
                    ChannelState.Error(e.message ?: e.javaClass.simpleName)
                }
                ui.post {
                    if (!isDestroyed) {
                        states[ch.name] = state
                        render()
                    }
                }
            }
        }
    }

    private fun render() {
        container.removeAllViews()
        channelsError?.let {
            container.addView(textLine(getString(R.string.channels_error, it)))
            return
        }
        if (channels.isEmpty()) {
            container.addView(textLine(getString(R.string.no_channels)))
            return
        }
        for (ch in channels) {
            val header = textLine(getString(R.string.channel_header, ch.name, ch.tiktok)).apply {
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            container.addView(header)
            when (val st = states[ch.name]) {
                null, ChannelState.Loading -> container.addView(textLine(getString(R.string.loading)))
                is ChannelState.Error -> container.addView(textLine(getString(R.string.load_error, st.message)))
                is ChannelState.Ok ->
                    if (st.episodes.isEmpty()) {
                        container.addView(textLine(getString(R.string.no_episodes)))
                    } else {
                        st.episodes.forEach { container.addView(episodeView(it)) }
                    }
            }
        }
    }

    private fun textLine(text: String): TextView = TextView(this).apply {
        this.text = text
        val pad = (6 * resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
    }

    private fun episodeView(ep: Episode): View {
        val v = layoutInflater.inflate(R.layout.item_episode, container, false)

        v.findViewById<TextView>(R.id.tvTitle).text = getString(R.string.episode_title, ep.number, ep.title)
        val due = Repo.formatDue(ep.due) ?: getString(R.string.due_unknown)
        v.findViewById<TextView>(R.id.tvMeta).text = getString(R.string.episode_due, due)

        val status = Downloads.status(this, Store.getDownloadId(this, ep.key))
        v.findViewById<TextView>(R.id.tvStatus).setText(
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> R.string.status_downloaded
                DownloadManager.STATUS_FAILED -> R.string.status_failed
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> R.string.status_downloading
                else -> R.string.status_not_downloaded
            }
        )

        val done = Store.isDone(this, ep.key)
        v.findViewById<TextView>(R.id.tvDone).visibility = if (done) View.VISIBLE else View.GONE

        v.findViewById<Button>(R.id.btnDownload).setOnClickListener { downloadEpisode(ep) }
        v.findViewById<Button>(R.id.btnOpen).setOnClickListener { openInTikTok(ep) }
        v.findViewById<Button>(R.id.btnDone).apply {
            setText(if (done) R.string.btn_unmark_done else R.string.btn_mark_done)
            setOnClickListener {
                Store.setDone(this@MainActivity, ep.key, !done)
                render()
            }
        }
        return v
    }

    // ------------------------------------------------------------------
    // Stiahnuť + text
    // ------------------------------------------------------------------

    private fun downloadEpisode(ep: Episode) {
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = ep
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        copyToClipboard(ep.text)
        try {
            Downloads.enqueue(this, ep)
            toast(getString(R.string.toast_downloading), long = true)
        } catch (e: Exception) {
            toast(getString(R.string.toast_download_error, e.message ?: e.javaClass.simpleName), long = true)
        }
        render()
    }

    private fun isOurDownload(id: Long): Boolean =
        states.values.any { st ->
            st is ChannelState.Ok && st.episodes.any { Store.getDownloadId(this, it.key) == id }
        }

    // ------------------------------------------------------------------
    // Otvoriť v TikToku
    // ------------------------------------------------------------------

    private fun openInTikTok(ep: Episode) {
        val id = Store.getDownloadId(this, ep.key)
        if (Downloads.status(this, id) != DownloadManager.STATUS_SUCCESSFUL) {
            toast(getString(R.string.toast_need_download))
            return
        }
        val uri = Downloads.resolveShareUri(this, ep, id)
        if (uri == null) {
            toast(getString(R.string.toast_no_uri), long = true)
            return
        }
        copyToClipboard(ep.text)
        toast(getString(R.string.toast_account_hint, ep.channel.tiktok), long = true)
        TikTokShare.send(this, uri, getString(R.string.chooser_title))
    }

    // ------------------------------------------------------------------
    // Kalibrácia (služba dostupnosti)
    // ------------------------------------------------------------------

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, TikTokAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestDump() {
        if (!isAccessibilityServiceEnabled()) {
            toast(getString(R.string.toast_a11y_not_enabled), long = true)
            openAccessibilitySettings()
            return
        }
        Store.requestDump(this)
        TikTokAccessibilityService.instance?.startDumpWatch()
        toast(getString(R.string.toast_dump_requested, TikTokAccessibilityService.DUMP_DELAY_MS / 1000), long = true)
    }

    private fun shareLastDump() {
        val file = Dumps.latest(this)
        if (file == null) {
            toast(getString(R.string.toast_no_dump))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("dump", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_dump_title)))
    }

    // ------------------------------------------------------------------
    // Povolenia a pomocné funkcie
    // ------------------------------------------------------------------

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val ep = pendingDownload
            pendingDownload = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (ep != null) downloadEpisode(ep)
            } else {
                toast(getString(R.string.toast_storage_denied), long = true)
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("popis", text))
    }

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
