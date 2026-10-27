package sk.brock.nahravac

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var tvTikTok: TextView
    private lateinit var tvA11y: TextView
    private lateinit var tvMacro: TextView
    private lateinit var btnRunAll: Button

    private var channels: List<Channel> = emptyList()
    private var channelsError: String? = null
    private val states = LinkedHashMap<String, ChannelState>()

    /** Načítanie epizód (sieť, 2 vlákna) a sťahovanie videí (po jednom). */
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val downloader: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** Kľúče epizód, ktoré sa práve sťahujú / sťahovanie zlyhalo. */
    private val downloading = HashSet<String>()
    private val failed = HashSet<String>()

    /** Čo sa má spustiť po udelení povolenia úložiska (Android 8-9). */
    private var afterStorage: (() -> Unit)? = null
    private var macroSteps: List<Step> = emptyList()

    // ------------------------------------------------------------------
    // Životný cyklus
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        tvTikTok = findViewById(R.id.tvTikTok)
        tvA11y = findViewById(R.id.tvA11y)
        tvMacro = findViewById(R.id.tvMacro)
        btnRunAll = findViewById(R.id.btnRunAll)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnA11ySettings).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btnShowMacro).setOnClickListener { showMacro() }
        findViewById<Button>(R.id.btnClearMacro).setOnClickListener { confirmClearMacro() }
        btnRunAll.setOnClickListener { onRunAllClicked() }

        findViewById<CheckBox>(R.id.cbConfirm).apply {
            isChecked = Store.confirmBeforePost(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Store.setConfirmBeforePost(this@MainActivity, checked) }
        }

        // Skrytá ladiaca funkcia: dlhé podržanie hlavičky = výpis stromu obrazovky TikToku, stavu služby = zdieľať výpis.
        tvTikTok.setOnLongClickListener {
            requestDump()
            true
        }
        tvA11y.setOnLongClickListener {
            shareLastDump()
            true
        }

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
        updateHeader()
        render() // stav sa mohol zmeniť, kým appka bola na pozadí
    }

    override fun onDestroy() {
        executor.shutdownNow()
        downloader.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Hlavička a sekcia automatického nahrávania
    // ------------------------------------------------------------------

    private fun updateHeader() {
        val pkg = TikTokShare.installedPackage(this)
        tvTikTok.text = if (pkg != null) getString(R.string.tiktok_pkg, pkg) else getString(R.string.tiktok_missing)

        val a11yOn = isAccessibilityServiceEnabled()
        tvA11y.setText(if (a11yOn) R.string.a11y_status_on else R.string.a11y_status_off)

        macroSteps = Macro.load(this)
        if (macroSteps.isEmpty()) {
            tvMacro.setText(R.string.macro_none)
        } else {
            var text = getString(R.string.macro_count, Macro.stepsText(macroSteps.size))
            if (macroSteps.none { it.type == StepType.ACCOUNT }) text += "\n" + getString(R.string.warn_no_account_step)
            tvMacro.text = text
        }
    }

    private fun showMacro() {
        val steps = Macro.load(this)
        val text = if (steps.isEmpty()) {
            getString(R.string.macro_none)
        } else {
            steps.mapIndexed { i, s -> Macro.describe(s, i + 1) }.joinToString("\n")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_dialog_title)
            .setMessage(text)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    private fun confirmClearMacro() {
        if (Macro.load(this).isEmpty()) {
            toast(getString(R.string.macro_none))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_text)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                Macro.clear(this)
                updateHeader()
                render()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** Pre každý kanál (v poradí z channels.json) najnovší diel, ktorý ešte nie je označený ako hotový. */
    private fun newestNotDone(): List<Episode> {
        val items = ArrayList<Episode>()
        for (ch in channels) {
            val st = states[ch.name]
            if (st is ChannelState.Ok) {
                val newest = st.episodes.firstOrNull() ?: continue
                if (!Store.isDone(this, newest.key)) items.add(newest)
            }
        }
        return items
    }

    /**
     * Jediné tlačidlo: skontroluje službu dostupnosti a TikTok, obnoví zoznamy všetkých kanálov a potom
     * buď (bez naučeného postupu) ponúkne naučenie na prvom novom diele, alebo nahrá nové diely všetkých kanálov.
     */
    private fun onRunAllClicked() {
        if (!isAccessibilityServiceEnabled() || TikTokAccessibilityService.instance == null) {
            toast(getString(R.string.toast_a11y_not_enabled), long = true)
            openAccessibilitySettings()
            return
        }
        if (TikTokShare.installedPackage(this) == null) {
            toast(getString(R.string.toast_tiktok_missing), long = true)
            return
        }
        btnRunAll.isEnabled = false
        btnRunAll.setText(R.string.loading)
        refresh {
            btnRunAll.isEnabled = true
            btnRunAll.setText(R.string.btn_run_all)
            if (Macro.load(this).isEmpty()) {
                val first = newestNotDone().firstOrNull()
                if (first == null) {
                    toast(getString(R.string.toast_no_new))
                } else {
                    toast(getString(R.string.toast_learn_first), long = true)
                    learnEpisode(first)
                }
            } else {
                runNewEpisodes()
            }
        }
    }

    /** Pre každý kanál najnovší diel, ktorý ešte nie je označený ako hotový. */
    private fun runNewEpisodes() {
        val items = newestNotDone()
        if (items.isEmpty()) {
            toast(getString(R.string.toast_no_new))
            return
        }
        startAuto(items)
    }

    /** Spustí automatické nahrávanie (stiahnutie + prehranie postupu) pre dané epizódy. */
    private fun startAuto(episodes: List<Episode>) {
        val svc = TikTokAccessibilityService.instance
        if (svc == null) {
            toast(getString(R.string.toast_a11y_not_enabled), long = true)
            openAccessibilitySettings()
            return
        }
        if (Macro.load(this).isEmpty()) {
            toast(getString(R.string.toast_no_macro), long = true)
            return
        }
        if (TikTokShare.installedPackage(this) == null) {
            toast(getString(R.string.toast_tiktok_missing), long = true)
            return
        }
        withStorage {
            val started = svc.runAll(
                episodes.map { Replayer.Item(it, it.text) },
                Store.confirmBeforePost(this),
            )
            toast(getString(if (started) R.string.toast_auto_started else R.string.toast_busy), long = true)
        }
    }

    // ------------------------------------------------------------------
    // Načítanie a vykreslenie
    // ------------------------------------------------------------------

    /** Obnoví epizódy všetkých podporovaných kanálov; [onComplete] sa zavolá na hlavnom vlákne, keď sa všetky dokončili (úspech aj chyba). */
    private fun refresh(onComplete: (() -> Unit)? = null) {
        states.clear()
        channels.forEach { states[it.name] = ChannelState.Loading }
        render()
        val supported = channels.filter { Repo.isSupported(it) }
        if (supported.isEmpty()) {
            if (onComplete != null) ui.post { if (!isDestroyed) onComplete() }
            return
        }
        var left = supported.size // mení sa len na hlavnom vlákne
        supported.forEach { ch ->
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
                        left--
                        if (left == 0) onComplete?.invoke()
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
            if (!Repo.isSupported(ch)) {
                container.addView(textLine(getString(R.string.unsupported_format) + ": " + ch.format))
                continue
            }
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

        val downloaded = Downloads.savedUri(this, ep) != null
        v.findViewById<TextView>(R.id.tvStatus).setText(
            when {
                ep.key in downloading -> R.string.status_downloading
                ep.key in failed -> R.string.status_failed
                downloaded -> R.string.status_downloaded
                else -> R.string.status_not_downloaded
            }
        )

        val done = Store.isDone(this, ep.key)
        v.findViewById<TextView>(R.id.tvDone).visibility = if (done) View.VISIBLE else View.GONE

        v.findViewById<Button>(R.id.btnDownload).setOnClickListener { downloadEpisode(ep) }
        v.findViewById<Button>(R.id.btnOpen).setOnClickListener { openInTikTok(ep) }

        // Kým nie je postup naučený, ponúkne sa učenie; potom sa dá učiť znova dlhým podržaním "Nahrať automaticky".
        val hasMacro = macroSteps.isNotEmpty()
        v.findViewById<Button>(R.id.btnLearn).apply {
            visibility = if (hasMacro) View.GONE else View.VISIBLE
            setOnClickListener { learnEpisode(ep) }
        }
        v.findViewById<Button>(R.id.btnAuto).apply {
            setOnClickListener { startAuto(listOf(ep)) }
            setOnLongClickListener {
                learnEpisode(ep)
                true
            }
        }

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
        withStorage {
            copyToClipboard(ep.text)
            toast(getString(R.string.toast_downloading), long = true)
            fetchInBackground(ep) { error ->
                if (error == null) {
                    toast(getString(R.string.toast_download_done))
                } else {
                    toast(getString(R.string.toast_download_error, describe(error)), long = true)
                }
            }
        }
    }

    /** Stiahne video do galérie na pozadí; [onDone] sa zavolá na hlavnom vlákne (null = úspech). */
    private fun fetchInBackground(ep: Episode, onDone: (Exception?) -> Unit) {
        downloading.add(ep.key)
        failed.remove(ep.key)
        render()
        downloader.execute {
            val error: Exception? = try {
                Downloads.fetchToGallery(this, ep)
                null
            } catch (e: Exception) {
                e
            }
            ui.post {
                if (isDestroyed) return@post
                downloading.remove(ep.key)
                if (error != null) failed.add(ep.key)
                render()
                onDone(error)
            }
        }
    }

    private fun describe(e: Exception): String =
        if (e.message.isNullOrBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: ${e.message}"

    // ------------------------------------------------------------------
    // Učenie postupu
    // ------------------------------------------------------------------

    private fun learnEpisode(ep: Episode) {
        if (!isAccessibilityServiceEnabled() || TikTokAccessibilityService.instance == null) {
            toast(getString(R.string.toast_a11y_not_enabled), long = true)
            openAccessibilitySettings()
            return
        }
        if (TikTokShare.installedPackage(this) == null) {
            toast(getString(R.string.toast_tiktok_missing), long = true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.learn_title)
            .setMessage(getString(R.string.learn_text, ep.channel.name, ep.channel.tiktok))
            .setPositiveButton(R.string.learn_start) { _, _ -> withStorage { startLearning(ep) } }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** Stiahne video, skopíruje popis a spustí nahrávanie klikov v TikToku. */
    private fun startLearning(ep: Episode) {
        fetchInBackground(ep) { error ->
            if (error != null) {
                toast(getString(R.string.toast_download_error, describe(error)), long = true)
            } else {
                copyToClipboard(ep.text)
                val started = TikTokAccessibilityService.instance?.startRecording(ep, ep.text) == true
                toast(getString(if (started) R.string.toast_learn_started else R.string.toast_learn_failed), long = true)
            }
        }
    }

    // ------------------------------------------------------------------
    // Otvoriť v TikToku (ručné zdieľanie)
    // ------------------------------------------------------------------

    private fun openInTikTok(ep: Episode) {
        if (TikTokShare.installedPackage(this) == null) {
            toast(getString(R.string.toast_tiktok_missing), long = true)
            return
        }
        val uri = Downloads.savedUri(this, ep)
        if (uri == null) {
            toast(getString(R.string.toast_need_download))
            return
        }
        copyToClipboard(ep.text)
        toast(getString(R.string.toast_account_hint, ep.channel.tiktok), long = true)
        try {
            TikTokShare.send(this, uri, getString(R.string.chooser_title))
        } catch (e: Exception) {
            toast(getString(R.string.toast_share_failed, describe(e)), long = true)
        }
    }

    // ------------------------------------------------------------------
    // Služba dostupnosti
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

    // --- skrytý výpis stromu obrazovky (ladenie) ---

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

    /** Spustí [block]; na Androide 8-9 najprv vypýta povolenie na zápis do úložiska (video ide do Movies). */
    private fun withStorage(block: () -> Unit) {
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            afterStorage = block
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        block()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            val action = afterStorage
            afterStorage = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                action?.invoke()
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
