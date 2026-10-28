package sk.brock.nahravac

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private lateinit var tvSummary: TextView
    private lateinit var btnRunAll: Button
    private lateinit var tvLastDownload: TextView
    private lateinit var btnOpenGallery: Button

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

    /** Dialóg s priebehom sťahovania (null = nezobrazený). */
    private var downloadDialog: AlertDialog? = null

    /** Čo sa má spustiť po udelení povolenia úložiska (Android 8-9). */
    private var afterStorage: (() -> Unit)? = null
    private var macroSteps: List<Step> = emptyList()

    /** Veľké tlačidlo práve obnovuje zoznamy (text "Načítavam…" sa nemá prepísať). */
    private var runAllBusy = false

    /** Kanály s rozbalenými "Viac možností". */
    private val expanded = HashSet<String>()

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
        tvSummary = findViewById(R.id.tvSummary)
        btnRunAll = findViewById(R.id.btnRunAll)
        tvLastDownload = findViewById(R.id.tvLastDownload)
        btnOpenGallery = findViewById(R.id.btnOpenGallery)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        btnOpenGallery.setOnClickListener { openLastDownload() }
        findViewById<Button>(R.id.btnA11ySettings).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btnShowMacro).setOnClickListener { showMacro() }
        findViewById<Button>(R.id.btnClearMacro).setOnClickListener { confirmClearMacro() }
        findViewById<Button>(R.id.btnDiag).setOnClickListener { shareDiagnostics() }
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
        dismissDownloadDialog()
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
        val switchSteps = Macro.loadSwitch(this)
        val upload = if (macroSteps.isEmpty()) {
            getString(R.string.macro_none)
        } else {
            getString(R.string.macro_count, Macro.stepsText(macroSteps.size))
        }
        val switch = if (switchSteps.isEmpty()) {
            getString(R.string.switch_none)
        } else {
            getString(R.string.switch_count, Macro.stepsText(switchSteps.size))
        }
        tvMacro.text = upload + "\n" + switch
    }

    private fun showMacro() {
        val steps = Macro.load(this)
        val switchSteps = Macro.loadSwitch(this)
        val upload = if (steps.isEmpty()) {
            getString(R.string.macro_none)
        } else {
            steps.mapIndexed { i, s -> Macro.describe(s, i + 1, withScreen = true) }.joinToString("\n")
        }
        val switch = if (switchSteps.isEmpty()) {
            getString(R.string.switch_none)
        } else {
            switchSteps.mapIndexed { i, s -> Macro.describe(s, i + 1, withScreen = true) }.joinToString("\n")
        }
        val text = getString(R.string.macro_dialog_upload) + "\n" + upload + "\n\n" +
            getString(R.string.macro_dialog_switch) + "\n" + switch
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_dialog_title)
            .setMessage(text)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    private fun confirmClearMacro() {
        if (Macro.load(this).isEmpty() && Macro.loadSwitch(this).isEmpty()) {
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
        runAllBusy = true
        btnRunAll.setText(R.string.loading)
        refresh {
            btnRunAll.isEnabled = true
            runAllBusy = false
            updateSummary()
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

    /** Riadok "Posledné stiahnutie" + tlačidlo na otvorenie videa; bez stiahnutia sú oba skryté. */
    private fun updateLastDownload() {
        val summary = Store.getLastDownload(this)
        if (summary == null) {
            tvLastDownload.visibility = View.GONE
            btnOpenGallery.visibility = View.GONE
            return
        }
        val at = Store.getLastDownloadAt(this)
        val whenText = if (at > 0) " (" + SimpleDateFormat("d.M. HH:mm", Locale.ROOT).format(Date(at)) + ")" else ""
        tvLastDownload.text = getString(R.string.last_download, summary + whenText)
        tvLastDownload.visibility = View.VISIBLE
        btnOpenGallery.visibility = View.VISIBLE
    }

    private fun render() {
        updateLastDownload()
        updateSummary()
        container.removeAllViews()
        channelsError?.let {
            container.addView(textLine(getString(R.string.channels_error, it)))
            return
        }
        if (channels.isEmpty()) {
            container.addView(textLine(getString(R.string.no_channels)))
            return
        }
        for (ch in channels) container.addView(channelView(ch))
    }

    /**
     * Riadok nad veľkým tlačidlom ("Na nahratie: N (…)" / "Všetko je nahraté ✔") a text tlačidla s počtom videí.
     * Kým sa zoznamy načítavajú, ostane všeobecný text.
     */
    private fun updateSummary() {
        if (channelsError != null || channels.isEmpty()) {
            tvSummary.visibility = View.GONE
            return
        }
        tvSummary.visibility = View.VISIBLE
        val supported = channels.filter { Repo.isSupported(it) }
        val loading = supported.any { ch ->
            val st = states[ch.name]
            st == null || st == ChannelState.Loading
        }
        if (loading) {
            tvSummary.setText(R.string.summary_loading)
            if (!runAllBusy) btnRunAll.setText(R.string.btn_run_all)
            return
        }
        val pending = newestNotDone()
        var text = if (pending.isEmpty()) {
            getString(R.string.summary_all_done)
        } else {
            getString(R.string.summary_pending, pending.size, pending.joinToString(", ") { "${it.channel.name} #${it.number}" })
        }
        val errors = supported.filter { states[it.name] is ChannelState.Error }
        if (errors.isNotEmpty()) text += "\n" + getString(R.string.summary_errors, errors.joinToString(", ") { it.name })
        tvSummary.text = text
        if (!runAllBusy) btnRunAll.text = runAllText(pending.size)
    }

    /** "▶ Nahrať 1 video / 3 videá / 5 videí na TikTok". */
    private fun runAllText(n: Int): String {
        val word = when (n) {
            1 -> "video"
            in 2..4 -> "videá"
            else -> "videí"
        }
        return getString(R.string.btn_run_n, n, word)
    }

    private fun textLine(text: String): TextView = TextView(this).apply {
        this.text = text
        val pad = (6 * resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
    }

    /** Karta kanála: "názov · handle", poznámka o Bufferi, diely so stavom a prepínač "Viac možností". */
    private fun channelView(ch: Channel): View {
        val v = layoutInflater.inflate(R.layout.item_channel, container, false)
        v.findViewById<TextView>(R.id.tvChannel).text = getString(R.string.channel_title, ch.name, ch.tiktok)
        val since = Repo.formatShort(ch.since)
        if (since != null) {
            v.findViewById<TextView>(R.id.tvSince).apply {
                text = getString(R.string.channel_since, since)
                visibility = View.VISIBLE
            }
        }
        val box = v.findViewById<LinearLayout>(R.id.episodes)
        val more = v.findViewById<Button>(R.id.btnMore)
        more.visibility = View.GONE
        if (!Repo.isSupported(ch)) {
            box.addView(textLine(getString(R.string.unsupported_format) + ": " + ch.format))
            return v
        }
        when (val st = states[ch.name]) {
            null, ChannelState.Loading -> box.addView(textLine(getString(R.string.loading)))
            is ChannelState.Error -> box.addView(textLine(getString(R.string.load_error, st.message)))
            is ChannelState.Ok ->
                if (st.episodes.isEmpty()) {
                    box.addView(textLine(getString(R.string.no_episodes)))
                } else {
                    val open = ch.name in expanded
                    val moreBoxes = ArrayList<View>()
                    st.episodes.forEachIndexed { i, ep ->
                        val ev = episodeView(ep, newest = i == 0)
                        val mb = ev.findViewById<View>(R.id.moreBox)
                        mb.visibility = if (open) View.VISIBLE else View.GONE
                        moreBoxes.add(mb)
                        box.addView(ev)
                    }
                    more.visibility = View.VISIBLE
                    more.setText(if (open) R.string.btn_less else R.string.btn_more)
                    more.setOnClickListener {
                        val nowOpen = ch.name !in expanded
                        if (nowOpen) expanded.add(ch.name) else expanded.remove(ch.name)
                        moreBoxes.forEach { it.visibility = if (nowOpen) View.VISIBLE else View.GONE }
                        more.setText(if (nowOpen) R.string.btn_less else R.string.btn_more)
                    }
                }
        }
        return v
    }

    /** Jeden diel; [newest] = najnovší diel kanála (len ten sa nahráva). */
    private fun episodeView(ep: Episode, newest: Boolean): View {
        val v = layoutInflater.inflate(R.layout.item_episode, container, false)

        v.findViewById<TextView>(R.id.tvTitle).text = getString(R.string.episode_title, ep.number, ep.title)
        val due = Repo.formatDue(ep.due) ?: getString(R.string.due_unknown)
        v.findViewById<TextView>(R.id.tvMeta).text = getString(R.string.episode_due, due)

        val done = Store.isDone(this, ep.key)
        val chip = v.findViewById<TextView>(R.id.tvChip)
        when {
            done -> {
                val at = Store.doneAt(this, ep.key)
                chip.text = if (at > 0) {
                    getString(R.string.chip_done_at, SimpleDateFormat("d.M. HH:mm", Locale.ROOT).format(Date(at)))
                } else {
                    getString(R.string.chip_done)
                }
                styleChip(chip, getColor(R.color.done_green), getColor(R.color.chip_done_bg))
            }
            newest -> {
                chip.setText(R.string.chip_pending)
                styleChip(chip, getColor(R.color.chip_pending_text), getColor(R.color.chip_pending_bg))
            }
            else -> {
                chip.setText(R.string.chip_older)
                chip.setTextColor(getColor(R.color.status_gray))
                chip.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }

        val downloaded = Downloads.savedUri(this, ep) != null
        v.findViewById<TextView>(R.id.tvStatus).setText(
            when {
                ep.key in downloading -> R.string.status_downloading
                ep.key in failed -> R.string.status_failed
                downloaded -> R.string.status_downloaded
                else -> R.string.status_not_downloaded
            }
        )

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

    /** Farebný štítok stavu (zaoblené pozadie). */
    private fun styleChip(chip: TextView, textColor: Int, bgColor: Int) {
        val d = resources.displayMetrics.density
        chip.setTextColor(textColor)
        chip.background = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = 10 * d
        }
        chip.setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
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
                    val summary = Store.getLastDownload(this)
                    toast(
                        if (summary == null) getString(R.string.toast_download_done)
                        else getString(R.string.toast_download_done_info, summary),
                        long = true,
                    )
                } else {
                    toast(getString(R.string.toast_download_error, describe(error)), long = true)
                }
            }
        }
    }

    /**
     * Stiahne video do galérie na pozadí a ukazuje dialóg s priebehom (zatvorí sa pred [onDone]).
     * [onDone] sa zavolá na hlavnom vlákne (null = úspech).
     */
    private fun fetchInBackground(ep: Episode, onDone: (Exception?) -> Unit) {
        downloading.add(ep.key)
        failed.remove(ep.key)
        render()
        showDownloadDialog()
        downloader.execute {
            var lastPercent = -2 // mení sa len na vlákne sťahovania
            val error: Exception? = try {
                Downloads.fetchToGallery(this, ep) { percent ->
                    if (percent != lastPercent) {
                        lastPercent = percent
                        ui.post { if (!isDestroyed) updateDownloadDialog(percent) }
                    }
                }
                null
            } catch (e: Exception) {
                e
            }
            ui.post {
                if (isDestroyed) return@post
                dismissDownloadDialog()
                downloading.remove(ep.key)
                if (error != null) failed.add(ep.key)
                render()
                onDone(error)
            }
        }
    }

    private fun showDownloadDialog() {
        dismissDownloadDialog()
        downloadDialog = AlertDialog.Builder(this)
            .setTitle(R.string.download_dialog_title)
            .setMessage(R.string.download_dialog_wait)
            .setCancelable(false)
            .show()
    }

    private fun updateDownloadDialog(percent: Int) {
        downloadDialog?.setMessage(
            if (percent >= 0) getString(R.string.download_dialog_percent, percent) else getString(R.string.download_dialog_wait)
        )
    }

    private fun dismissDownloadDialog() {
        try {
            downloadDialog?.dismiss()
        } catch (e: Exception) {
            // okno už zaniklo spolu s aktivitou
        }
        downloadDialog = null
    }

    private fun describe(e: Exception): String =
        if (e.message.isNullOrBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: ${e.message}"

    /** Otvorí naposledy stiahnuté video v prehrávači / galérii (overenie, že ho systém vidí). */
    private fun openLastDownload() {
        val text = Store.getLastDownloadUri(this)
        if (text == null) {
            toast(getString(R.string.toast_no_last_download))
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(text), "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.toast_open_gallery_failed, describe(e)), long = true)
        }
    }

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
                toast(getString(R.string.toast_learn_downloaded))
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

    /** Pošle najnovších (najviac 5) diag-*.json z Download/TikTokNahravac (napr. WhatsApp, Gmail). */
    private fun shareDiagnostics() {
        if (Build.VERSION.SDK_INT < 29) {
            toast(getString(R.string.toast_diag_old_android), long = true)
            return
        }
        val uris = Diag.latest(this, 5)
        if (uris.isEmpty()) {
            toast(getString(R.string.toast_no_diag))
            return
        }
        val clip = ClipData.newRawUri("diag", uris[0])
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/json"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_diag_title)))
        } catch (e: Exception) {
            toast(describe(e), long = true)
        }
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
