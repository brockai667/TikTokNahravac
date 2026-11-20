package sk.brock.nahravac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Spustenie bez dotyku obrazovky: broadcast [ACTION] robí to isté ako veľké tlačidlo v appke (obnoví zoznamy
 * všetkých kanálov a nahrá najnovší nový diel každého). Beží len keď je zapnutá služba dostupnosti, inak len zapíše
 * do logu (tag [TAG]). Chránené signature povolením (aplikácia sk.brock.terminal je podpísaná rovnakým kľúčom);
 * pre `adb shell` pozri [RunAllShellReceiver].
 */
open class RunAllReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "sk.brock.nahravac.RUN_ALL"
        const val TAG = "NahravacRunAll"
        const val AUTO_ON = "sk.brock.nahravac.AUTO_ON"
        const val AUTO_OFF = "sk.brock.nahravac.AUTO_OFF"
        /** Pre Brock Terminal: odpoved cez usporiadany broadcast, resultData je "on" alebo "off". */
        const val AUTO_STATE = "sk.brock.nahravac.AUTO_STATE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            AUTO_ON, AUTO_OFF -> {
                val on = intent?.action == AUTO_ON
                Store.setAutoRun(context, on)
                Log.i(TAG, if (on) "auto: zapnute" else "auto: vypnute")
                return
            }
            AUTO_STATE -> {
                val on = Store.isAutoRun(context)
                if (isOrderedBroadcast) {
                    resultCode = android.app.Activity.RESULT_OK
                    resultData = if (on) "on" else "off"
                }
                Log.i(TAG, "auto stav: " + if (on) "zapnute" else "vypnute")
                return
            }
        }
        val app = context.applicationContext
        val svc = TikTokAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "RUN_ALL ignorovane: sluzba dostupnosti nie je zapnuta")
            return
        }
        if (TikTokShare.installedPackage(app) == null) {
            Log.w(TAG, "RUN_ALL ignorovane: TikTok nie je nainstalovany")
            return
        }
        // Sieť mimo hlavného vlákna; broadcast sa skončí hneď (proces drží služba dostupnosti).
        Thread {
            try {
                val items = AutoRun.collect(app, false)
                if (items.isEmpty()) {
                    Log.i(TAG, "RUN_ALL: ziadny novy diel")
                } else if (TikTokAccessibilityService.instance?.runAll(items) == true) {
                    Log.i(TAG, "RUN_ALL: spustene (${items.size})")
                } else {
                    Log.w(TAG, "RUN_ALL: sluzba je zaneprazdnena")
                }
            } catch (e: Exception) {
                Log.w(TAG, "RUN_ALL zlyhalo: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }
}

/** To isté pre `adb shell am broadcast -n ...` (shell má povolenie DUMP, nie naše signature povolenie). */
class RunAllShellReceiver : RunAllReceiver() {
    companion object {
        /** Skúška odoslania do kampane: extras url, caption, account (bez @), dry (default true), link (YouTube Short; potom netreba caption). */
        const val ACTION_SUBMIT_TEST = "sk.brock.nahravac.SUBMIT_TEST"
        /** Okamžitá kontrola príspevkov na odoslanie do kampane (Instagram/YouTube). */
        const val ACTION_SUBMITS_CHECK = "sk.brock.nahravac.SUBMITS_CHECK"
        /** Skúška nahratia z mobilu: extras platform (youtube|instagram), set (chatdiff|clutchdiff), dry (default true); voliteľne url/title/caption, keď feed nemá diel. */
        const val ACTION_UPLOAD_TEST = "sk.brock.nahravac.UPLOAD_TEST"
        /** Ladenie: strom okien balíka (extra pkg) do logu. */
        const val ACTION_SCREEN_LOG = "sk.brock.nahravac.SCREEN_LOG"
        const val ACTION_AUTO_ON = "sk.brock.nahravac.AUTO_ON"
        const val ACTION_AUTO_OFF = "sk.brock.nahravac.AUTO_OFF"
        const val ACTION_AUTO_CHECK = "sk.brock.nahravac.AUTO_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_AUTO_ON, ACTION_AUTO_OFF -> {
                val on = intent?.action == ACTION_AUTO_ON
                Store.setAutoRun(context, on)
                Log.i(TAG, if (on) "auto: zapnute" else "auto: vypnute")
                return
            }
            ACTION_AUTO_CHECK -> {
                AutoRun.check(context, "shell")
                return
            }
            ACTION_SUBMITS_CHECK -> {
                AutoRun.checkSubmits(context)
                return
            }
            ACTION_SCREEN_LOG -> {
                TikTokAccessibilityService.instance?.logTree(intent?.getStringExtra("pkg") ?: "com.google.android.youtube")
                return
            }
            ACTION_UPLOAD_TEST -> {
                val platform = intent?.getStringExtra("platform")?.trim()?.lowercase() ?: ""
                val set = intent?.getStringExtra("set")?.trim()?.lowercase() ?: ""
                val dry = intent?.getBooleanExtra("dry", true) ?: true
                val svc0 = TikTokAccessibilityService.instance
                if (svc0 == null) {
                    Log.w(TAG, "UPLOAD_TEST ignorovane: sluzba dostupnosti nie je zapnuta")
                } else if (platform !in PhonePosts.PLATFORMS || PhonePosts.target(set) == null) {
                    Log.w(TAG, "UPLOAD_TEST ignorovane: platform (youtube|instagram) alebo set (chatdiff|clutchdiff) nesedi")
                } else if (svc0.uploadTest(platform, set, dry, intent?.getStringExtra("url"), intent?.getStringExtra("title"), intent?.getStringExtra("caption"))) {
                    Log.i(TAG, "UPLOAD_TEST: spustene ($platform $set dry=$dry)")
                } else {
                    Log.w(TAG, "UPLOAD_TEST: sluzba je zaneprazdnena")
                }
                return
            }
            ACTION_SUBMIT_TEST -> {}
            else -> {
                super.onReceive(context, intent)
                return
            }
        }
        val svc = TikTokAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "SUBMIT_TEST ignorovane: sluzba dostupnosti nie je zapnuta")
            return
        }
        val url = intent.getStringExtra("url") ?: ""
        val caption = intent.getStringExtra("caption") ?: ""
        val account = intent.getStringExtra("account") ?: ""
        val dry = intent.getBooleanExtra("dry", true)
        val link = intent.getStringExtra("link")?.trim()?.ifEmpty { null }
        if (url.isBlank() || account.isBlank() || (link == null && caption.isBlank())) {
            Log.w(TAG, "SUBMIT_TEST ignorovane: chyba url, account alebo caption (pri link netreba caption)")
            return
        }
        if (svc.submitTest(url, caption, account, dry, link)) {
            Log.i(TAG, "SUBMIT_TEST: spustene (dry=$dry)")
        } else {
            Log.w(TAG, "SUBMIT_TEST: sluzba je zaneprazdnena")
        }
    }
}
